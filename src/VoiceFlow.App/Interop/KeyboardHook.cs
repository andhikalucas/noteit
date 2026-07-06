using System.Diagnostics;
using System.Runtime.InteropServices;
using VoiceFlow.Core.Services;
using static VoiceFlow.App.Interop.NativeMethods;

namespace VoiceFlow.App.Interop;

/// <summary>
/// Global low-level keyboard hook implementing the Wispr-style chords:
///   Ctrl+Win (hold)        → push-to-talk
///   Ctrl+Win+Space         → hands-free toggle
///   Ctrl+Win+Alt (hold)    → command mode
///   Esc                    → cancel active dictation
/// Any other key pressed while the chord is held (e.g. Ctrl+Win+Arrow = virtual desktop
/// switch) raises <see cref="ForeignKeyDown"/> so the controller can abort — OS shortcuts
/// must not produce accidental dictations.
/// Events fire on the hook thread; subscribers must marshal to the UI thread.
/// Injected (synthetic) key events are ignored so our own SendInput never re-triggers us.
/// </summary>
public sealed class KeyboardHook : IDisposable
{
    // KBDLLHOOKSTRUCT field offsets — read directly, no struct marshalling on the hot path.
    private const int OffsetVkCode = 0;
    private const int OffsetFlags = 8;

    private IntPtr _hookId = IntPtr.Zero;
    private readonly LowLevelKeyboardProc _proc; // kept alive to prevent GC of the delegate

    private bool _ctrl, _win;
    private bool _chordActive;
    private bool _spaceLatched;    // suppress key-repeat retriggers of hands-free
    private bool _swallowedSpace;  // pair the swallowed Space key-up with its key-down

    public event Action? ChordDown;
    public event Action? ChordUp;
    public event Action? CommandKeyJoined;
    public event Action? HandsFreeKey;
    public event Action? CancelKey;
    /// <summary>A non-chord key was pressed while Ctrl+Win is held — an OS shortcut, not dictation.</summary>
    public event Action? ForeignKeyDown;

    /// <summary>Queried to decide whether Esc should be swallowed.</summary>
    public Func<bool> IsSessionActive { get; set; } = () => false;

    public KeyboardHook()
    {
        _proc = HookCallback;
    }

    public void Install()
    {
        if (_hookId != IntPtr.Zero) return;
        using var process = Process.GetCurrentProcess();
        using var module = process.MainModule!;
        _hookId = SetWindowsHookEx(WH_KEYBOARD_LL, _proc, GetModuleHandle(module.ModuleName), 0);
        if (_hookId == IntPtr.Zero)
            throw new InvalidOperationException($"Keyboard hook failed: {Marshal.GetLastWin32Error()}");
        Log.Info("Keyboard hook installed");
    }

    private IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode < 0)
            return CallNextHookEx(_hookId, nCode, wParam, lParam);

        uint vk = (uint)Marshal.ReadInt32(lParam, OffsetVkCode);
        uint flags = (uint)Marshal.ReadInt32(lParam, OffsetFlags);
        if ((flags & LLKHF_INJECTED) != 0)
            return CallNextHookEx(_hookId, nCode, wParam, lParam);

        int msg = (int)wParam;
        bool isDown = msg is WM_KEYDOWN or WM_SYSKEYDOWN;
        bool isUp = msg is WM_KEYUP or WM_SYSKEYUP;
        bool swallow = false;

        // Heal stuck chord state: key-ups can be eaten by the UAC secure desktop, RDP,
        // or session switches. Cross-check against the real key state.
        if (_chordActive && !PhysicalChordHeld())
        {
            _ctrl = (GetAsyncKeyState(VK_LCONTROL) & 0x8000) != 0 || (GetAsyncKeyState(VK_RCONTROL) & 0x8000) != 0;
            _win = (GetAsyncKeyState(VK_LWIN) & 0x8000) != 0 || (GetAsyncKeyState(VK_RWIN) & 0x8000) != 0;
            _chordActive = false;
            SafeRaise(ChordUp);
        }

        if (isDown)
        {
            switch (vk)
            {
                case VK_LCONTROL or VK_RCONTROL: _ctrl = true; break;
                case VK_LWIN or VK_RWIN: _win = true; break;
            }

            bool isChordModifier = vk is VK_LCONTROL or VK_RCONTROL or VK_LWIN or VK_RWIN;

            if (!_chordActive && _ctrl && _win && isChordModifier)
            {
                _chordActive = true;
                SafeRaise(ChordDown);
            }
            else if (_chordActive && !isChordModifier)
            {
                switch (vk)
                {
                    case VK_LMENU or VK_RMENU:
                        SafeRaise(CommandKeyJoined);
                        break;
                    case VK_SPACE:
                        if (!_spaceLatched)
                        {
                            _spaceLatched = true;
                            SafeRaise(HandsFreeKey);
                        }
                        _swallowedSpace = true;
                        swallow = true; // don't let Space reach the focused app
                        break;
                    case VK_ESCAPE:
                        if (SafeQuery(IsSessionActive))
                        {
                            SafeRaise(CancelKey);
                            swallow = true;
                        }
                        break;
                    default:
                        SafeRaise(ForeignKeyDown); // Ctrl+Win+<other> is an OS shortcut
                        break;
                }
            }
            else if (!_chordActive && vk == VK_ESCAPE && SafeQuery(IsSessionActive))
            {
                SafeRaise(CancelKey);
                swallow = true;
            }
        }
        else if (isUp)
        {
            switch (vk)
            {
                case VK_LCONTROL or VK_RCONTROL: _ctrl = false; break;
                case VK_LWIN or VK_RWIN: _win = false; break;
                case VK_SPACE:
                    _spaceLatched = false;
                    if (_swallowedSpace)
                    {
                        _swallowedSpace = false;
                        swallow = true;
                    }
                    break;
            }

            if (_chordActive && (!_ctrl || !_win))
            {
                _chordActive = false;
                SafeRaise(ChordUp);
            }
        }

        return swallow ? (IntPtr)1 : CallNextHookEx(_hookId, nCode, wParam, lParam);
    }

    private static bool PhysicalChordHeld()
    {
        bool ctrl = (GetAsyncKeyState(VK_LCONTROL) & 0x8000) != 0 || (GetAsyncKeyState(VK_RCONTROL) & 0x8000) != 0;
        bool win = (GetAsyncKeyState(VK_LWIN) & 0x8000) != 0 || (GetAsyncKeyState(VK_RWIN) & 0x8000) != 0;
        return ctrl && win;
    }

    // The hook callback must never throw — an exception here can destabilize input globally.
    private static void SafeRaise(Action? handler)
    {
        try { handler?.Invoke(); }
        catch (Exception ex) { Log.Error("Hook subscriber threw", ex); }
    }

    private static bool SafeQuery(Func<bool> query)
    {
        try { return query(); }
        catch { return false; }
    }

    public void Dispose()
    {
        if (_hookId != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_hookId);
            _hookId = IntPtr.Zero;
        }
    }
}
