using System.IO;
using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;
using Xunit;

namespace VoiceFlow.Tests;

public class HistoryStoreTests : IDisposable
{
    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"voiceflow_test_{Guid.NewGuid():N}.db");
    private readonly HistoryStore _store;

    public HistoryStoreTests() => _store = new HistoryStore(_dbPath);

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        if (File.Exists(_dbPath)) File.Delete(_dbPath);
    }

    private static DictationSession Session(string text, string app = "notepad", int words = 0, int durationMs = 5000)
        => new()
        {
            TimestampUtc = DateTime.UtcNow,
            RawText = text,
            FormattedText = text,
            AppName = app,
            DurationMs = durationMs,
            WordCount = words > 0 ? words : text.Split(' ').Length,
            Language = "en",
            Mode = DictationMode.PushToTalk,
        };

    [Fact]
    public void AddAndFetchSession_Roundtrips()
    {
        var id = _store.AddSession(Session("hello world"));
        Assert.True(id > 0);
        var sessions = _store.GetRecentSessions();
        var s = Assert.Single(sessions);
        Assert.Equal("hello world", s.FormattedText);
        Assert.Equal("notepad", s.AppName);
        Assert.Equal(DictationMode.PushToTalk, s.Mode);
    }

    [Fact]
    public void Search_FindsByTextAndEscapesWildcards()
    {
        _store.AddSession(Session("the quarterly report is ready"));
        _store.AddSession(Session("100% done"));
        Assert.Single(_store.GetRecentSessions(50, "quarterly"));
        Assert.Single(_store.GetRecentSessions(50, "100%"));
        Assert.Empty(_store.GetRecentSessions(50, "%%%missing%%%"));
    }

    [Fact]
    public void DeleteAndClear_Work()
    {
        var id = _store.AddSession(Session("a"));
        _store.AddSession(Session("b"));
        _store.DeleteSession(id);
        Assert.Single(_store.GetRecentSessions());
        _store.ClearHistory();
        Assert.Empty(_store.GetRecentSessions());
    }

    [Fact]
    public void Insights_ComputesTotalsAndWpm()
    {
        _store.AddSession(Session("one two three four five six", words: 6, durationMs: 3000));
        _store.AddSession(Session("seven eight nine ten", words: 4, durationMs: 2000));
        var insights = _store.GetInsights(30);
        Assert.Equal(2, insights.TotalSessions);
        Assert.Equal(10, insights.TotalWords);
        Assert.Equal(120, insights.AverageWpm, 0); // 10 words / 5 s
        Assert.Equal(1, insights.StreakDays);
        var app = Assert.Single(insights.TopApps);
        Assert.Equal("notepad", app.AppName);
    }

    [Fact]
    public void Dictionary_CrudAndOrdering()
    {
        _store.AddDictionaryEntry("kubectl");
        _store.AddDictionaryEntry("Wispr", "whisper", starred: true);
        var entries = _store.GetDictionary();
        Assert.Equal(2, entries.Count);
        Assert.Equal("Wispr", entries[0].Phrase); // starred first
        Assert.Equal("whisper", entries[0].Misheard);

        _store.IncrementDictionaryUses(new[] { entries[1].Id });
        _store.SetDictionaryStar(entries[0].Id, false);
        _store.DeleteDictionaryEntry(entries[0].Id);
        var remaining = Assert.Single(_store.GetDictionary());
        Assert.Equal(1, remaining.Uses);
    }

    [Fact]
    public void Dictionary_UpsertsOnSamePhrase()
    {
        _store.AddDictionaryEntry("Nginx");
        _store.AddDictionaryEntry("Nginx", "engine x");
        var entry = Assert.Single(_store.GetDictionary());
        Assert.Equal("engine x", entry.Misheard);
    }

    [Fact]
    public void Snippets_CrudRoundtrip()
    {
        _store.AddSnippet("insert my email", "me@example.com");
        var snip = Assert.Single(_store.GetSnippets());
        Assert.Equal("me@example.com", snip.Content);
        _store.IncrementSnippetUses(new[] { snip.Id });
        Assert.Equal(1, _store.GetSnippets()[0].Uses);
        _store.DeleteSnippet(snip.Id);
        Assert.Empty(_store.GetSnippets());
    }
}

public class SettingsServiceTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"voiceflow_settings_{Guid.NewGuid():N}.json");

    public void Dispose()
    {
        if (File.Exists(_path)) File.Delete(_path);
    }

    [Fact]
    public void SaveAndReload_Roundtrips()
    {
        var svc = new SettingsService(_path);
        svc.Update(s =>
        {
            s.Cleanup = CleanupLevel.High;
            s.Language = "de";
            s.ModelSize = WhisperModelSize.Small;
        });

        var reloaded = new SettingsService(_path);
        Assert.Equal(CleanupLevel.High, reloaded.Current.Cleanup);
        Assert.Equal("de", reloaded.Current.Language);
        Assert.Equal(WhisperModelSize.Small, reloaded.Current.ModelSize);
    }

    [Fact]
    public void CorruptFile_FallsBackToDefaults()
    {
        File.WriteAllText(_path, "{not json!!");
        var svc = new SettingsService(_path);
        Assert.Equal(CleanupLevel.Medium, svc.Current.Cleanup);
    }

    [Fact]
    public void FlowBarPositionAndMinimized_Roundtrip()
    {
        var svc = new SettingsService(_path);
        Assert.Null(svc.Current.FlowBarLeft); // unset by default → auto-centering
        Assert.False(svc.Current.FlowBarMinimized);

        svc.Update(s => { s.FlowBarLeft = 640.5; s.FlowBarTop = 12.0; s.FlowBarMinimized = true; });

        var reloaded = new SettingsService(_path);
        Assert.Equal(640.5, reloaded.Current.FlowBarLeft);
        Assert.Equal(12.0, reloaded.Current.FlowBarTop);
        Assert.True(reloaded.Current.FlowBarMinimized);
    }

    [Fact]
    public void QuietUpdate_PersistsWithoutRaisingChanged()
    {
        var svc = new SettingsService(_path);
        int notifications = 0;
        svc.Changed += _ => notifications++;

        // Dragging the Flow Bar saves position on every drop — must not wake subscribers.
        svc.Update(s => s.FlowBarLeft = 100, notify: false);
        Assert.Equal(0, notifications);

        // A normal update still notifies.
        svc.Update(s => s.ShowFlowBar = false);
        Assert.Equal(1, notifications);

        // The quiet write still hit disk.
        Assert.Equal(100, new SettingsService(_path).Current.FlowBarLeft);
    }

    [Fact]
    public void ApiKey_IsDpapiProtected_AndRoundtrips()
    {
        var svc = new SettingsService(_path);
        svc.SetApiKey("sk-test-12345");

        // The settings file must never contain the plaintext key.
        var onDisk = File.ReadAllText(_path);
        Assert.DoesNotContain("sk-test-12345", onDisk);

        Assert.Equal("sk-test-12345", svc.GetApiKey());
        Assert.Equal("sk-test-12345", new SettingsService(_path).GetApiKey());

        svc.SetApiKey(null);
        Assert.Null(svc.GetApiKey());
    }
}
