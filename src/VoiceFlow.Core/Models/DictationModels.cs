namespace VoiceFlow.Core.Models;

public enum DictationMode
{
    PushToTalk = 0,
    HandsFree = 1,
    Command = 2,
}

/// <summary>One completed dictation, persisted in history.</summary>
public class DictationSession
{
    public long Id { get; set; }
    public DateTime TimestampUtc { get; set; }
    public string RawText { get; set; } = "";
    public string FormattedText { get; set; } = "";
    public string AppName { get; set; } = "";
    public int DurationMs { get; set; }
    public int WordCount { get; set; }
    public string Language { get; set; } = "";
    public DictationMode Mode { get; set; }
}

public class DictionaryEntry
{
    public long Id { get; set; }
    /// <summary>The word/phrase Flow should know (e.g. "Wispr", "kubectl").</summary>
    public string Phrase { get; set; } = "";
    /// <summary>Optional: common misrecognition to auto-correct into <see cref="Phrase"/>.</summary>
    public string? Misheard { get; set; }
    public bool Starred { get; set; }
    public int Uses { get; set; }
}

public class Snippet
{
    public long Id { get; set; }
    /// <summary>Spoken cue, e.g. "insert scheduling link".</summary>
    public string Trigger { get; set; } = "";
    public string Content { get; set; } = "";
    public int Uses { get; set; }
}

public class AppUsage
{
    public string AppName { get; set; } = "";
    public int Sessions { get; set; }
    public int Words { get; set; }
}

public class InsightsSummary
{
    public int TotalSessions { get; set; }
    public int TotalWords { get; set; }
    public double TotalMinutes { get; set; }
    /// <summary>Average speaking words-per-minute across sessions.</summary>
    public double AverageWpm { get; set; }
    public int StreakDays { get; set; }
    public List<AppUsage> TopApps { get; set; } = new();
}

/// <summary>Result of the formatting pipeline.</summary>
public class FormatResult
{
    public string Text { get; set; } = "";
    /// <summary>True when the user ended the dictation with "press enter".</summary>
    public bool SendEnter { get; set; }
}
