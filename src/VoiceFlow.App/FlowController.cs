using System.IO;
using System.Windows.Threading;
using VoiceFlow.App.Interop;
using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;

namespace VoiceFlow.App;

public enum FlowState
{
    Idle,
    Recording,
    Processing,
}

/// <summary>
/// The heart of the app: turns hotkey chords into record → transcribe → format → insert
/// cycles. All state transitions happen on the UI thread (hook events are marshalled here).
///
/// Chords (fixed, Wispr-style):
///   hold Ctrl+Win            → push-to-talk, release to insert
///   Ctrl+Win+Space           → hands-free toggle (tap Ctrl+Win or Space again to stop)
///   hold Ctrl+Win then Alt   → command mode (transform selection / generate by voice)
///   Esc                      → cancel
///
/// Safety: privacy checks (app blocklist, password fields) run at record start AND again
/// immediately before insertion, because focus can move while transcription runs. The
/// synthetic Enter of "press enter" is only sent when focus never left the original window.
/// </summary>
public sealed class FlowController : IDisposable
{
    private static readonly TimeSpan MinDictation = TimeSpan.FromMilliseconds(300);

    private readonly Dispatcher _dispatcher;
    private readonly SettingsService _settings;
    private readonly HistoryStore _store;
    private readonly AudioRecorder _recorder = new();
    private readonly TranscriptionService _transcriber = new();
    private readonly CommandInterpreter _commands = new();
    private readonly LlmClient _llm = new();
    private readonly KeyboardHook _hook = new();
    private readonly SoundFx _sounds = new();

    private DictationMode _mode;
    private DateTime _recordStartUtc;
    private ForegroundInfo _target = ForegroundInfo.Unknown;
    private DispatcherTimer? _handsFreeTimer;
    private bool _warnedNearCap;
    private int _sessionId; // invalidates stale async privacy checks
    private CancellationTokenSource? _processingCts;

    public FlowState State { get; private set; } = FlowState.Idle;
    public DictationMode Mode => _mode;
    public bool Enabled { get; set; } = true;

    public event Action? StateChanged;
    public event Action<float>? AudioLevel;
    /// <summary>Transient message for the Flow Bar ("Paused in this app", errors, …).</summary>
    public event Action<string>? Notify;

    public FlowController(Dispatcher dispatcher, SettingsService settings, HistoryStore store)
    {
        _dispatcher = dispatcher;
        _settings = settings;
        _store = store;

        _recorder.LevelChanged += level => _dispatcher.BeginInvoke(() => AudioLevel?.Invoke(level));
        _recorder.CaptureFailed += _ => _dispatcher.BeginInvoke(() =>
            AbortRecording("Microphone error — check Settings"));

        _hook.IsSessionActive = () => State != FlowState.Idle;
        _hook.ChordDown += () => _dispatcher.BeginInvoke(OnChordDown);
        _hook.ChordUp += () => _dispatcher.BeginInvoke(OnChordUp);
        _hook.CommandKeyJoined += () => _dispatcher.BeginInvoke(OnCommandKeyJoined);
        _hook.HandsFreeKey += () => _dispatcher.BeginInvoke(OnHandsFreeKey);
        _hook.CancelKey += () => _dispatcher.BeginInvoke(CancelActive);
        _hook.ForeignKeyDown += () => _dispatcher.BeginInvoke(OnForeignKey);
    }

    public void Start()
    {
        _hook.Install();
        WarmUpModel();
    }

    /// <summary>Loads the Whisper model in the background so the first dictation is fast.</summary>
    private void WarmUpModel()
    {
        var size = _settings.Current.ModelSize;
        if (!ModelDownloader.IsDownloaded(size)) return;
        _ = Task.Run(async () =>
        {
            try
            {
                var silence = new float[AudioRecorder.SampleRate / 2];
                await _transcriber.TranscribeAsync(ModelDownloader.GetPath(size), silence, "en");
                Log.Info("Model warm-up complete");
            }
            catch (Exception ex)
            {
                Log.Warn($"Model warm-up failed: {ex.Message}");
            }
        });
    }

    // ----- Hotkey handlers (UI thread) -----

    private void OnChordDown()
    {
        if (!Enabled || State == FlowState.Processing) return;

        if (State == FlowState.Recording && _mode == DictationMode.HandsFree)
        {
            StopAndProcess(); // tap Ctrl+Win to finish hands-free
            return;
        }

        if (State == FlowState.Idle)
            StartRecording(DictationMode.PushToTalk);
    }

    private void OnChordUp()
    {
        if (State == FlowState.Recording && _mode is DictationMode.PushToTalk or DictationMode.Command)
            StopAndProcess();
    }

    private void OnCommandKeyJoined()
    {
        if (State == FlowState.Recording && _mode == DictationMode.PushToTalk)
        {
            _mode = DictationMode.Command;
            StateChanged?.Invoke();
        }
    }

    private void OnHandsFreeKey()
    {
        switch (State)
        {
            case FlowState.Recording when _mode == DictationMode.PushToTalk:
                _mode = DictationMode.HandsFree; // seamless upgrade, keep recording
                StartHandsFreeTimer();
                StateChanged?.Invoke();
                break;
            case FlowState.Recording when _mode == DictationMode.HandsFree:
                StopAndProcess();
                break;
            case FlowState.Idle:
                StartRecording(DictationMode.HandsFree);
                break;
        }
    }

    /// <summary>An OS shortcut (Ctrl+Win+something) fired mid-chord — this was never a dictation.</summary>
    private void OnForeignKey()
    {
        if (State == FlowState.Recording && _mode is DictationMode.PushToTalk or DictationMode.Command)
        {
            _recorder.Abort();
            SetState(FlowState.Idle); // silent: the user is switching desktops, not dictating
        }
    }

    /// <summary>Flow Bar click / tray menu entry point.</summary>
    public void ToggleHandsFree()
    {
        if (State == FlowState.Recording)
        {
            StopAndProcess();
        }
        else if (State == FlowState.Idle)
        {
            StartRecording(DictationMode.HandsFree);
        }
    }

    public void CancelActive()
    {
        if (State == FlowState.Recording)
        {
            AbortRecording("Cancelled");
        }
        else if (State == FlowState.Processing)
        {
            _processingCts?.Cancel();
        }
    }

    private void AbortRecording(string message)
    {
        _recorder.Abort();
        StopHandsFreeTimer();
        SetState(FlowState.Idle);
        Flash(message);
    }

    // ----- Recording lifecycle -----

    private void StartRecording(DictationMode mode)
    {
        if (!Enabled) return; // tray "Pause VoiceFlow" gates every entry point

        var s = _settings.Current;

        if (!ModelDownloader.IsDownloaded(s.ModelSize))
        {
            Flash("Speech model not downloaded — open VoiceFlow");
            _sounds.PlayError(s.PlaySounds);
            return;
        }

        _target = ForegroundApp.Get();
        if (!ValidateTarget(_target, s, out var reason))
        {
            Flash(reason);
            return;
        }

        try
        {
            _recorder.Start(s.MicDeviceNumber);
        }
        catch (Exception ex)
        {
            Log.Error("Mic start failed", ex);
            Flash("Couldn't access the microphone");
            _sounds.PlayError(s.PlaySounds);
            return;
        }

        _mode = mode;
        _recordStartUtc = DateTime.UtcNow;
        _warnedNearCap = false;
        var session = ++_sessionId;
        if (mode == DictationMode.HandsFree) StartHandsFreeTimer();
        _sounds.PlayStart(s.PlaySounds);
        SetState(FlowState.Recording);

        // Password-field detection uses UI Automation, which can take a few hundred ms —
        // run it off-thread so the first spoken words aren't clipped, and abort if it trips.
        if (s.PauseInPasswordFields)
        {
            _ = Task.Run(() => FocusGuard.IsPasswordFieldFocused()).ContinueWith(t =>
            {
                if (t.Status == TaskStatus.RanToCompletion && t.Result)
                {
                    _dispatcher.BeginInvoke(() =>
                    {
                        if (State == FlowState.Recording && _sessionId == session)
                            AbortRecording("Flow won't dictate into password fields");
                    });
                }
            });
        }
    }

    /// <summary>Shared privacy gate, applied at record start and re-applied before insertion.</summary>
    private static bool ValidateTarget(ForegroundInfo target, AppSettings s, out string reason)
    {
        if (target.ProcessName.Length == 0 && (s.PauseInPasswordFields || s.PrivacyApps.Count > 0))
        {
            // Unidentifiable foreground process (elevated/exiting) — fail closed:
            // injection would be blocked by UIPI anyway, so be explicit instead of silent.
            reason = "Can't identify the focused app — dictation paused";
            return false;
        }
        if (IsPrivacyBlocked(target.ProcessName, s))
        {
            reason = "Flow is paused in this app";
            return false;
        }
        reason = "";
        return true;
    }

    private static bool IsPrivacyBlocked(string processName, AppSettings s)
    {
        return s.PrivacyApps.Any(app =>
            app.Trim().Length > 0 && processName.Contains(app.Trim(), StringComparison.OrdinalIgnoreCase));
    }

    private void StopAndProcess()
    {
        var (samples, duration) = _recorder.Stop();
        StopHandsFreeTimer();
        _sounds.PlayStop(_settings.Current.PlaySounds);

        if (duration < MinDictation)
        {
            SetState(FlowState.Idle); // accidental tap
            return;
        }

        var mode = _mode;
        var target = _target;
        SetState(FlowState.Processing);
        _ = ProcessAsync(samples, duration, mode, target);
    }

    private async Task ProcessAsync(float[] samples, TimeSpan duration, DictationMode mode, ForegroundInfo target)
    {
        _processingCts = new CancellationTokenSource();
        var ct = _processingCts.Token;
        try
        {
            var s = _settings.Current;
            var modelPath = ModelDownloader.GetPath(s.ModelSize);
            var dictionary = _store.GetDictionary();
            var prompt = TextFormatter.BuildWhisperPrompt(dictionary);

            // Grab the selection first for command mode — it's still highlighted right now.
            // Only if focus is still where the user started: a synthetic Ctrl+C into some
            // other window (e.g. a console) is harmful.
            string selection = "";
            if (mode == DictationMode.Command && ForegroundApp.Get().Hwnd == target.Hwnd)
                selection = await TextInjector.CopySelectionAsync();

            var tr = await _transcriber.TranscribeAsync(modelPath, samples, s.Language, prompt, ct);
            if (string.IsNullOrWhiteSpace(tr.Text))
            {
                Flash("Didn't catch that");
                return;
            }

            if (mode == DictationMode.Command)
            {
                await RunCommandAsync(tr, selection, duration, target, s, ct);
            }
            else
            {
                var matchedDict = new List<long>();
                var matchedSnips = new List<long>();
                var fmt = TextFormatter.Format(
                    tr.Text, s.Cleanup, dictionary, _store.GetSnippets(), tr.Language, matchedDict, matchedSnips);

                // A lone synthetic Enter with no text is never acceptable ("um, press enter").
                if (fmt.Text.Length == 0)
                {
                    Flash("Didn't catch that");
                    return;
                }

                await InsertValidatedAsync(fmt.Text, fmt.SendEnter, target, s);
                _store.IncrementDictionaryUses(matchedDict);
                _store.IncrementSnippetUses(matchedSnips);
                SaveHistory(tr.Text, fmt.Text, mode, duration, target, tr.Language);
            }
        }
        catch (OperationCanceledException)
        {
            Flash("Cancelled");
        }
        catch (ModelLoadException mex)
        {
            Log.Error("Model failed to load — quarantining", mex);
            try { File.Delete(mex.ModelPath); } catch (IOException) { /* locked; next run retries */ }
            Flash("Speech model was corrupted — re-download it in Settings");
            _sounds.PlayError(_settings.Current.PlaySounds);
        }
        catch (Exception ex)
        {
            Log.Error("Dictation pipeline failed", ex);
            Flash("Something went wrong — see logs");
            _sounds.PlayError(_settings.Current.PlaySounds);
        }
        finally
        {
            _processingCts?.Dispose();
            _processingCts = null;
            SetState(FlowState.Idle);
        }
    }

    /// <summary>
    /// Re-validates the insertion target right before synthesizing input: focus may have
    /// moved to a blocked app or password field while Whisper was working. The Enter key
    /// is only sent when focus never left the record-time window.
    /// </summary>
    private async Task InsertValidatedAsync(string text, bool sendEnter, ForegroundInfo recordTarget, AppSettings s)
    {
        var now = ForegroundApp.Get();
        if (!ValidateTarget(now, s, out _) ||
            (s.PauseInPasswordFields && FocusGuard.IsPasswordFieldFocused()))
        {
            Flash("Focus moved to a protected app — find your text in History");
            return;
        }

        bool sameWindow = now.Hwnd == recordTarget.Hwnd;
        if (!sameWindow && sendEnter)
            Log.Info("Suppressed auto-Enter: focus changed since recording started");

        await TextInjector.InsertTextAsync(
            text, s.InsertViaKeystrokes, s.RestoreClipboard, sendEnter && sameWindow);
    }

    private async Task RunCommandAsync(
        TranscriptionResult tr, string selection, TimeSpan duration,
        ForegroundInfo target, AppSettings s, CancellationToken ct)
    {
        CommandInterpreter.LlmComplete? llm = null;
        var apiKey = _settings.GetApiKey();
        if (!string.IsNullOrEmpty(apiKey))
            llm = (system, user, token) => _llm.CompleteAsync(s.ApiBaseUrl, apiKey, s.ApiModel, system, user, token);

        var result = await _commands.ExecuteAsync(tr.Text, selection, llm, ct);
        if (!result.Success || string.IsNullOrEmpty(result.Output))
        {
            Flash(result.Error ?? "Command failed");
            _sounds.PlayError(s.PlaySounds);
            return;
        }

        // Pasting while the original selection is still highlighted replaces it in-place.
        await InsertValidatedAsync(result.Output, sendEnter: false, target, s);
        SaveHistory(tr.Text, result.Output, DictationMode.Command, duration, target, tr.Language);
    }

    private void SaveHistory(
        string raw, string formatted, DictationMode mode,
        TimeSpan duration, ForegroundInfo target, string language)
    {
        try
        {
            _store.AddSession(new DictationSession
            {
                TimestampUtc = DateTime.UtcNow,
                RawText = raw,
                FormattedText = formatted,
                AppName = target.ProcessName,
                DurationMs = (int)duration.TotalMilliseconds,
                WordCount = formatted.Split(' ', '\n', '\t').Count(w => w.Length > 0),
                Language = language,
                Mode = mode,
            });
        }
        catch (Exception ex)
        {
            Log.Error("History save failed", ex);
        }
    }

    // ----- Hands-free session cap (matches Wispr: warn at 19 min, stop at 20) -----

    private void StartHandsFreeTimer()
    {
        StopHandsFreeTimer();
        _handsFreeTimer = new DispatcherTimer(DispatcherPriority.Background, _dispatcher)
        {
            Interval = TimeSpan.FromSeconds(15),
        };
        _handsFreeTimer.Tick += (_, _) =>
        {
            var elapsed = DateTime.UtcNow - _recordStartUtc;
            var max = TimeSpan.FromMinutes(Math.Clamp(_settings.Current.MaxHandsFreeMinutes, 1, 25));
            if (!_warnedNearCap && elapsed >= max - TimeSpan.FromMinutes(1))
            {
                _warnedNearCap = true;
                Flash("Hands-free stops in 1 minute");
            }
            if (elapsed >= max && State == FlowState.Recording)
                StopAndProcess();
        };
        _handsFreeTimer.Start();
    }

    private void StopHandsFreeTimer()
    {
        _handsFreeTimer?.Stop();
        _handsFreeTimer = null;
    }

    // ----- Helpers -----

    private void SetState(FlowState state)
    {
        State = state;
        StateChanged?.Invoke();
    }

    private void Flash(string message) => Notify?.Invoke(message);

    public void Dispose()
    {
        try { _processingCts?.Cancel(); } catch (ObjectDisposedException) { }
        _hook.Dispose();
        _recorder.Dispose();
        _transcriber.Dispose();
        _sounds.Dispose();
        _handsFreeTimer?.Stop();
    }
}
