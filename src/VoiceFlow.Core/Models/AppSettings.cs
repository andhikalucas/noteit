namespace VoiceFlow.Core.Models;

public enum CleanupLevel
{
    /// <summary>Raw transcription, no edits at all.</summary>
    None = 0,
    /// <summary>Remove pure filler sounds (um, uh), fix spacing and capitalization.</summary>
    Light = 1,
    /// <summary>Light + parenthetical fillers ("you know", "I mean"), stutter/word repeats.</summary>
    Medium = 2,
    /// <summary>Medium + hedge words ("basically", "sort of"), aggressive tidy-up.</summary>
    High = 3,
}

public enum WhisperModelSize
{
    Tiny = 0,
    Base = 1,
    Small = 2,
}

/// <summary>
/// User settings persisted as JSON under %APPDATA%\VoiceFlow\settings.json.
/// The LLM API key is never stored in this file in plaintext — see <see cref="ApiKeyProtected"/>.
/// </summary>
public class AppSettings
{
    // Audio / recognition
    public int MicDeviceNumber { get; set; } = -1; // -1 = system default (WAVE_MAPPER)
    public WhisperModelSize ModelSize { get; set; } = WhisperModelSize.Base;
    /// <summary>BCP-47-ish two letter code understood by Whisper, or "auto" for detection.</summary>
    public string Language { get; set; } = "auto";

    // Formatting
    public CleanupLevel Cleanup { get; set; } = CleanupLevel.Medium;

    // Behaviour
    public bool PlaySounds { get; set; } = true;
    public bool LaunchAtLogin { get; set; }
    /// <summary>When true, insert text by simulating keystrokes instead of clipboard paste.</summary>
    public bool InsertViaKeystrokes { get; set; }
    public bool RestoreClipboard { get; set; } = true;
    public bool ShowFlowBar { get; set; } = true;
    public int MaxHandsFreeMinutes { get; set; } = 20;

    // Flow Bar overlay position/state. Left/Top are null until the user drags it, after
    // which it stays pinned there (in DIPs); otherwise it auto-centers on the active monitor.
    public double? FlowBarLeft { get; set; }
    public double? FlowBarTop { get; set; }
    public bool FlowBarMinimized { get; set; }

    // Privacy
    /// <summary>Process names (without .exe) in which Flow refuses to record or insert.</summary>
    public List<string> PrivacyApps { get; set; } = new()
    {
        "keepass", "keepassxc", "1password", "bitwarden",
    };
    public bool PauseInPasswordFields { get; set; } = true;

    // Optional LLM for Command Mode / Transforms (OpenAI-compatible endpoint).
    /// <summary>DPAPI-protected API key, base64. Null when not configured.</summary>
    public string? ApiKeyProtected { get; set; }
    public string ApiBaseUrl { get; set; } = "https://api.openai.com/v1";
    public string ApiModel { get; set; } = "gpt-4o-mini";

    public bool FirstRunCompleted { get; set; }
}
