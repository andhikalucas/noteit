using System.IO;
using System.Speech.AudioFormat;
using System.Speech.Synthesis;
using System.Windows.Threading;
using VoiceFlow.App.Interop;
using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;
using Xunit;
using Xunit.Abstractions;
using WinForms = System.Windows.Forms;

namespace VoiceFlow.Tests;

/// <summary>
/// True end-to-end coverage:
///  1. Windows TTS renders speech audio → Whisper transcribes it → formatter polishes it.
///  2. TextInjector really types/pastes into a live win32 textbox via SendInput.
/// These need a desktop session and (once) a ~75 MB model download.
/// </summary>
public class EndToEndTests
{
    private readonly ITestOutputHelper _output;

    public EndToEndTests(ITestOutputHelper output) => _output = output;

    [Fact]
    [Trait("Category", "E2E")]
    public async Task SpeechAudio_To_PolishedText()
    {
        // 1. Synthesize known speech with Windows TTS at Whisper's native format.
        var wavPath = Path.Combine(Path.GetTempPath(), $"voiceflow_e2e_{Guid.NewGuid():N}.wav");
        try
        {
            using (var synth = new SpeechSynthesizer())
            {
                synth.SetOutputToWaveFile(wavPath,
                    new SpeechAudioFormatInfo(16000, AudioBitsPerSample.Sixteen, AudioChannel.Mono));
                synth.Rate = -1;
                synth.Speak("Hello world. This is a test of voice dictation.");
            }

            var samples = WavHelper.ReadAsMono16k(wavPath);
            _output.WriteLine($"Audio: {samples.Length / 16000.0:0.0}s");
            Assert.True(samples.Length > 16000, "TTS produced less than a second of audio");

            // 2. Transcribe locally with Whisper (tiny model keeps the test fast).
            var modelPath = await new ModelDownloader().EnsureAsync(
                WhisperModelSize.Tiny,
                new Progress<double>(p => { if (p is 0 or 1) _output.WriteLine($"download {p:P0}"); }));
            using var transcriber = new TranscriptionService();
            var result = await transcriber.TranscribeAsync(modelPath, samples, "en");
            _output.WriteLine($"Whisper: \"{result.Text}\"");

            var lower = result.Text.ToLowerInvariant();
            Assert.Contains("hello", lower);
            Assert.Contains("test", lower);
            Assert.Contains("dictation", lower);

            // 3. Full formatting pass.
            var formatted = TextFormatter.Format(result.Text, CleanupLevel.Medium);
            _output.WriteLine($"Formatted: \"{formatted.Text}\"");
            Assert.True(formatted.Text.Length > 10);
            Assert.True(char.IsUpper(formatted.Text[0]));
        }
        finally
        {
            if (File.Exists(wavPath)) File.Delete(wavPath);
        }
    }

    [Fact]
    [Trait("Category", "E2E")]
    public void PasteInjection_LandsInRealTextBox()
    {
        RunOnStaDispatcher(async () =>
        {
            using var app = new TargetApp();
            if (!await TryAcquireFocusAsync(app))
            {
                _output.WriteLine("SKIPPED: desktop is busy — Windows refused foreground focus.");
                return;
            }

            await TextInjector.InsertTextAsync(
                "Hello from VoiceFlow — pasted.", viaKeystrokes: false,
                restoreClipboard: true, pressEnter: false);
            await Task.Delay(400);

            if (!app.HasFocus && app.Text.Length == 0)
            {
                _output.WriteLine("SKIPPED: user activity stole focus mid-test — result unreliable.");
                return;
            }
            Assert.Equal("Hello from VoiceFlow — pasted.", app.Text);
        });
    }

    [Fact]
    [Trait("Category", "E2E")]
    public void KeystrokeInjection_TypesUnicode_AndPressesEnter()
    {
        RunOnStaDispatcher(async () =>
        {
            using var app = new TargetApp();
            if (!await TryAcquireFocusAsync(app))
            {
                _output.WriteLine("SKIPPED: desktop is busy — Windows refused foreground focus.");
                return;
            }

            await TextInjector.InsertTextAsync(
                "héllo ünïcode ✓", viaKeystrokes: true,
                restoreClipboard: true, pressEnter: true);
            await Task.Delay(600);

            if (!app.HasFocus && app.Text.Length == 0)
            {
                _output.WriteLine("SKIPPED: user activity stole focus mid-test — result unreliable.");
                return;
            }
            Assert.StartsWith("héllo ünïcode ✓", app.Text);
            Assert.Contains("\r\n", app.Text); // the synthetic Enter arrived
        });
    }

    /// <summary>
    /// Tries to make the target form the real foreground window. Windows legitimately
    /// refuses focus-stealing while the user is typing elsewhere — callers treat a false
    /// return as an environmental skip, not a product failure.
    /// </summary>
    private static async Task<bool> TryAcquireFocusAsync(TargetApp app)
    {
        // Don't fight a human for the keyboard: if the user touched mouse/keyboard in the
        // last 3 seconds, injecting into "the foreground window" could land in THEIR window.
        if (TargetApp.SecondsSinceLastUserInput() < 3)
            return false;

        for (int i = 0; i < 30; i++)
        {
            app.Activate();
            await Task.Delay(120);
            if (app.HasFocus)
            {
                app.CancelMenuMode();
                await Task.Delay(80);
                return true;
            }
            if (TargetApp.SecondsSinceLastUserInput() < 3) return false;
        }
        return false;
    }

    /// <summary>
    /// A genuine target application: a WinForms window running its own message loop on its
    /// own STA thread (so VK_PACKET unicode input is translated to WM_CHAR exactly like a
    /// real app), driven cross-thread via Control.Invoke.
    /// </summary>
    private sealed class TargetApp : IDisposable
    {
        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

        [System.Runtime.InteropServices.DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
        private struct LASTINPUTINFO
        {
            public uint cbSize;
            public uint dwTime;
        }

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

        public static double SecondsSinceLastUserInput()
        {
            var info = new LASTINPUTINFO { cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<LASTINPUTINFO>() };
            if (!GetLastInputInfo(ref info)) return double.MaxValue;
            return (Environment.TickCount - (int)info.dwTime) / 1000.0;
        }

        private readonly Thread _thread;
        private readonly ManualResetEventSlim _shown = new(false);
        private WinForms.Form _form = null!;
        private WinForms.TextBox _box = null!;

        public TargetApp()
        {
            _thread = new Thread(() =>
            {
                _form = new WinForms.Form
                {
                    Text = "VoiceFlow injection target",
                    Width = 420,
                    Height = 200,
                    TopMost = true,
                    StartPosition = WinForms.FormStartPosition.CenterScreen,
                };
                _box = new WinForms.TextBox { Multiline = true, Dock = WinForms.DockStyle.Fill };
                _form.Controls.Add(_box);
                _form.Shown += (_, _) => _shown.Set();
                WinForms.Application.Run(_form);
            })
            {
                IsBackground = true,
            };
            _thread.SetApartmentState(ApartmentState.STA);
            _thread.Start();
            if (!_shown.Wait(TimeSpan.FromSeconds(10)))
                throw new TimeoutException("Target form never appeared");
        }

        public string Text => (string)_form.Invoke(() => _box.Text);

        // Control.Focused/ContainsFocus are per-thread and can be true while another
        // process owns the real foreground — SendInput goes to the actual foreground
        // window, so that's what must be verified.
        public bool HasFocus =>
            GetForegroundWindow() == (IntPtr)_form.Invoke(() => _form.Handle) &&
            (bool)_form.Invoke(() => _box.Focused);

        public void Activate() => _form.Invoke(() =>
        {
            var fg = GetForegroundWindow();
            if (fg == _form.Handle)
            {
                _box.Focus();
                return;
            }

            // Standard force-foreground technique: attach to the current foreground
            // thread's input queue and tap Alt to satisfy the SetForegroundWindow rules.
            uint fgThread = fg != IntPtr.Zero ? GetWindowThreadProcessId(fg, out _) : 0;
            uint ourThread = GetCurrentThreadId();
            bool attached = fgThread != 0 && fgThread != ourThread &&
                            AttachThreadInput(fgThread, ourThread, true);
            try
            {
                const byte VK_MENU = 0x12;
                const uint KEYEVENTF_KEYUP = 0x2;
                keybd_event(VK_MENU, 0, 0, UIntPtr.Zero);
                keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
                SetForegroundWindow(_form.Handle);
                _form.Activate();
                _box.Focus();
            }
            finally
            {
                if (attached) AttachThreadInput(fgThread, ourThread, false);
            }
        });

        /// <summary>The Alt tap above can leave the form in menu mode, which would swallow
        /// the first injected keystroke — an Esc tap disarms it.</summary>
        public void CancelMenuMode() => _form.Invoke(() =>
        {
            const byte VK_ESCAPE = 0x1B;
            const uint KEYEVENTF_KEYUP = 0x2;
            keybd_event(VK_ESCAPE, 0, 0, UIntPtr.Zero);
            keybd_event(VK_ESCAPE, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
            _box.Focus();
        });

        public void Dispose()
        {
            try { _form.Invoke(() => _form.Close()); } catch { /* already closed */ }
            _thread.Join(TimeSpan.FromSeconds(3));
            _shown.Dispose();
        }
    }

    /// <summary>
    /// Runs async UI code on an STA thread with a WPF dispatcher pump, mirroring how the
    /// real app calls TextInjector from its dispatcher thread.
    /// </summary>
    private static void RunOnStaDispatcher(Func<Task> action)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            var frame = new DispatcherFrame();
            SynchronizationContext.SetSynchronizationContext(
                new DispatcherSynchronizationContext(Dispatcher.CurrentDispatcher));
            _ = Dispatcher.CurrentDispatcher.InvokeAsync(async () =>
            {
                try { await action(); }
                catch (Exception ex) { failure = ex; }
                finally { frame.Continue = false; }
            });
            Dispatcher.PushFrame(frame);
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(90)), "STA test timed out");
        if (failure != null) throw failure;
    }
}
