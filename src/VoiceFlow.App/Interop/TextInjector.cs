using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using VoiceFlow.Core.Services;
using static VoiceFlow.App.Interop.NativeMethods;

namespace VoiceFlow.App.Interop;

/// <summary>
/// Inserts text into the focused control of whatever app is active — either by
/// clipboard paste (fast, default) or by simulating Unicode keystrokes (for apps
/// that block paste). Must be called from the UI (STA) thread for clipboard access.
///
/// Privacy: dictated text placed on the clipboard is tagged so Windows Clipboard
/// History (Win+V) and Cloud Clipboard sync skip it, and the clipboard is restored
/// or cleared after the paste.
/// </summary>
public static class TextInjector
{
    private static readonly ushort[] AllModifiers =
    {
        VK_LCONTROL, VK_RCONTROL, VK_LWIN, VK_RWIN,
        VK_LMENU, VK_RMENU, VK_LSHIFT, VK_RSHIFT,
    };

    /// <summary>Inserts <paramref name="text"/> at the caret of the focused app.</summary>
    public static async Task InsertTextAsync(string text, bool viaKeystrokes, bool restoreClipboard, bool pressEnter)
    {
        if (string.IsNullOrEmpty(text) && !pressEnter) return;

        await WaitForModifierReleaseAsync();
        ForceModifierKeyUps();

        if (!string.IsNullOrEmpty(text))
        {
            if (viaKeystrokes)
            {
                SendUnicodeText(text);
            }
            else
            {
                string? displaced = TryGetClipboardText();
                if (!TrySetClipboardText(text))
                {
                    // Clipboard is locked by another app — fall back to typing.
                    SendUnicodeText(text);
                }
                else
                {
                    await Task.Delay(30);
                    SendPaste();
                    // Give the target app time to consume the paste before restoring.
                    await Task.Delay(350);
                    if (restoreClipboard)
                    {
                        if (displaced != null)
                            TrySetClipboardText(displaced);
                        else
                            TryClearOurClipboardText(text);
                    }
                }
            }
        }

        if (pressEnter)
        {
            await Task.Delay(80);
            TapKey(VK_RETURN);
        }
    }

    /// <summary>
    /// Copies the current selection in the focused app (for Command Mode).
    /// Returns the selected text ("" when nothing is selected). The user's previous
    /// clipboard text is restored; non-text clipboard content (images, files) is
    /// preserved when no selection was copied.
    /// </summary>
    public static async Task<string> CopySelectionAsync()
    {
        await WaitForModifierReleaseAsync();
        ForceModifierKeyUps();

        string? previousText = TryGetClipboardText();
        bool hadNonText = previousText == null && ClipboardHasAnyData();

        // Sentinel lets us detect "no selection" — but never destroy an image/file
        // clipboard just to plant it.
        if (!hadNonText)
            TrySetClipboardText("");

        SendCtrlKey(VK_C);
        await Task.Delay(180); // allow the app to service WM_COPY

        string selection = TryGetClipboardText() ?? "";
        if (previousText != null)
            TrySetClipboardText(previousText);
        return selection;
    }

    // ----- Clipboard -----

    private static bool ClipboardHasAnyData()
    {
        try
        {
            return Clipboard.GetDataObject()?.GetFormats().Length > 0;
        }
        catch (COMException)
        {
            return false;
        }
    }

    private static string? TryGetClipboardText()
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                return Clipboard.ContainsText() ? Clipboard.GetText() : null;
            }
            catch (COMException)
            {
                Thread.Sleep(40);
            }
        }
        return null;
    }

    private static bool TrySetClipboardText(string text)
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                var data = new DataObject();
                data.SetText(text);
                // Keep dictations out of Win+V history and cloud clipboard sync.
                data.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream());
                data.SetData("CanIncludeInClipboardHistory", new MemoryStream(BitConverter.GetBytes(0)));
                data.SetData("CanUploadToCloudClipboard", new MemoryStream(BitConverter.GetBytes(0)));
                Clipboard.SetDataObject(data, copy: true);
                return true;
            }
            catch (COMException)
            {
                Thread.Sleep(40);
            }
        }
        Log.Warn("Clipboard unavailable after retries");
        return false;
    }

    /// <summary>Clears the clipboard only if it still holds the text we placed there.</summary>
    private static void TryClearOurClipboardText(string ourText)
    {
        try
        {
            if (Clipboard.ContainsText() && Clipboard.GetText() == ourText)
                Clipboard.Clear();
        }
        catch (COMException)
        {
            // Another app owns the clipboard now — nothing to clean up.
        }
    }

    // ----- Key synthesis -----

    private static async Task WaitForModifierReleaseAsync(int timeoutMs = 1500)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < deadline)
        {
            if (!AllModifiers.Any(vk => (GetAsyncKeyState(vk) & 0x8000) != 0))
                return;
            await Task.Delay(25);
        }
    }

    /// <summary>Synthesizes key-up for any modifier still held so our Ctrl+V isn't corrupted.</summary>
    private static void ForceModifierKeyUps()
    {
        var ups = AllModifiers
            .Where(vk => (GetAsyncKeyState(vk) & 0x8000) != 0)
            .Select(vk => KeyInput(vk, KEYEVENTF_KEYUP))
            .ToArray();
        if (ups.Length > 0)
            SendInputChecked(ups);
    }

    private static void SendPaste() => SendCtrlKey(VK_V);

    private static void SendCtrlKey(ushort key)
    {
        SendInputChecked(new[]
        {
            KeyInput(VK_LCONTROL, 0),
            KeyInput(key, 0),
            KeyInput(key, KEYEVENTF_KEYUP),
            KeyInput(VK_LCONTROL, KEYEVENTF_KEYUP),
        });
    }

    private static void TapKey(ushort vk)
    {
        SendInputChecked(new[] { KeyInput(vk, 0), KeyInput(vk, KEYEVENTF_KEYUP) });
    }

    private static void SendUnicodeText(string text)
    {
        var inputs = new List<INPUT>(text.Length * 2);
        foreach (var ch in text)
        {
            if (ch == '\r') continue;
            if (ch == '\n')
            {
                // Shift+Enter: newline as *data*, not a submit action — plain Enter
                // sends the message in most chat apps.
                inputs.Add(KeyInput(VK_LSHIFT, 0));
                inputs.Add(KeyInput(VK_RETURN, 0));
                inputs.Add(KeyInput(VK_RETURN, KEYEVENTF_KEYUP));
                inputs.Add(KeyInput(VK_LSHIFT, KEYEVENTF_KEYUP));
                continue;
            }
            inputs.Add(UnicodeInput(ch, 0));
            inputs.Add(UnicodeInput(ch, KEYEVENTF_KEYUP));
        }

        // Send in chunks so huge snippets don't overflow the input queue.
        const int chunk = 512;
        for (int i = 0; i < inputs.Count; i += chunk)
        {
            var slice = inputs.Skip(i).Take(chunk).ToArray();
            SendInputChecked(slice);
            Thread.Sleep(5);
        }
    }

    private static void SendInputChecked(INPUT[] inputs)
    {
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        if (sent != inputs.Length)
            Log.Warn($"SendInput injected {sent}/{inputs.Length} events (error {Marshal.GetLastWin32Error()})");
    }

    private static INPUT KeyInput(ushort vk, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT { wVk = vk, dwFlags = flags | (IsExtendedKey(vk) ? KEYEVENTF_EXTENDEDKEY : 0) },
        },
    };

    // Right-side modifiers and the Windows keys are extended keys; without the flag
    // their synthetic key-ups may not clear the real key state.
    private static bool IsExtendedKey(ushort vk) =>
        vk is VK_RCONTROL or VK_RMENU or VK_LWIN or VK_RWIN;

    private static INPUT UnicodeInput(char ch, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT { wVk = 0, wScan = ch, dwFlags = KEYEVENTF_UNICODE | flags },
        },
    };
}
