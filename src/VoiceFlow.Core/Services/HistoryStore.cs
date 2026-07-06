using Microsoft.Data.Sqlite;
using VoiceFlow.Core.Models;

namespace VoiceFlow.Core.Services;

/// <summary>
/// SQLite-backed store for dictation history, the personal dictionary and snippets.
/// All queries are parameterized. One store instance per app; connections are opened per call.
/// </summary>
public class HistoryStore
{
    private readonly string _connectionString;

    public HistoryStore(string? dbPath = null)
    {
        var path = dbPath ?? Path.Combine(SettingsService.AppDataDir, "voiceflow.db");
        _connectionString = new SqliteConnectionStringBuilder { DataSource = path }.ToString();
        Initialize();
    }

    private SqliteConnection Open()
    {
        var conn = new SqliteConnection(_connectionString);
        conn.Open();
        // Per-connection pragma: deleted history rows are zeroed, not left in free pages.
        using var pragma = conn.CreateCommand();
        pragma.CommandText = "PRAGMA secure_delete=ON";
        pragma.ExecuteNonQuery();
        return conn;
    }

    private void Initialize()
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_utc TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                formatted_text TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                word_count INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL DEFAULT '',
                mode INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS idx_history_ts ON history(ts_utc DESC);
            CREATE TABLE IF NOT EXISTS dictionary(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phrase TEXT NOT NULL UNIQUE,
                misheard TEXT NULL,
                starred INTEGER NOT NULL DEFAULT 0,
                uses INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS snippets(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trigger TEXT NOT NULL UNIQUE,
                content TEXT NOT NULL,
                uses INTEGER NOT NULL DEFAULT 0);
            """;
        cmd.ExecuteNonQuery();
    }

    // ---------- History ----------

    public long AddSession(DictationSession s)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO history(ts_utc, raw_text, formatted_text, app_name, duration_ms, word_count, language, mode)
            VALUES ($ts, $raw, $fmt, $app, $dur, $words, $lang, $mode);
            SELECT last_insert_rowid();
            """;
        cmd.Parameters.AddWithValue("$ts", s.TimestampUtc.ToString("o"));
        cmd.Parameters.AddWithValue("$raw", s.RawText);
        cmd.Parameters.AddWithValue("$fmt", s.FormattedText);
        cmd.Parameters.AddWithValue("$app", s.AppName);
        cmd.Parameters.AddWithValue("$dur", s.DurationMs);
        cmd.Parameters.AddWithValue("$words", s.WordCount);
        cmd.Parameters.AddWithValue("$lang", s.Language);
        cmd.Parameters.AddWithValue("$mode", (int)s.Mode);
        return (long)cmd.ExecuteScalar()!;
    }

    public List<DictationSession> GetRecentSessions(int limit = 50, string? search = null)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        if (string.IsNullOrWhiteSpace(search))
        {
            cmd.CommandText = "SELECT * FROM history ORDER BY ts_utc DESC LIMIT $lim";
        }
        else
        {
            cmd.CommandText = """
                SELECT * FROM history
                WHERE formatted_text LIKE $q ESCAPE '\' OR app_name LIKE $q ESCAPE '\'
                ORDER BY ts_utc DESC LIMIT $lim
                """;
            var escaped = search.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_");
            cmd.Parameters.AddWithValue("$q", $"%{escaped}%");
        }
        cmd.Parameters.AddWithValue("$lim", limit);

        var list = new List<DictationSession>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new DictationSession
            {
                Id = r.GetInt64(0),
                TimestampUtc = DateTime.Parse(r.GetString(1), null, System.Globalization.DateTimeStyles.RoundtripKind),
                RawText = r.GetString(2),
                FormattedText = r.GetString(3),
                AppName = r.GetString(4),
                DurationMs = r.GetInt32(5),
                WordCount = r.GetInt32(6),
                Language = r.GetString(7),
                Mode = (DictationMode)r.GetInt32(8),
            });
        }
        return list;
    }

    public void DeleteSession(long id)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM history WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void ClearHistory()
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM history";
        cmd.ExecuteNonQuery();
    }

    // ---------- Insights ----------

    public InsightsSummary GetInsights(int days = 30)
    {
        var sinceUtc = DateTime.UtcNow.AddDays(-days).ToString("o");
        var summary = new InsightsSummary();

        using var conn = Open();
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = """
                SELECT COUNT(*), COALESCE(SUM(word_count),0), COALESCE(SUM(duration_ms),0)
                FROM history WHERE ts_utc >= $since
                """;
            cmd.Parameters.AddWithValue("$since", sinceUtc);
            using var r = cmd.ExecuteReader();
            if (r.Read())
            {
                summary.TotalSessions = r.GetInt32(0);
                summary.TotalWords = r.GetInt32(1);
                var ms = r.GetInt64(2);
                summary.TotalMinutes = ms / 60000.0;
                summary.AverageWpm = ms > 0 ? summary.TotalWords / (ms / 60000.0) : 0;
            }
        }

        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = """
                SELECT app_name, COUNT(*), COALESCE(SUM(word_count),0)
                FROM history WHERE ts_utc >= $since AND app_name <> ''
                GROUP BY app_name ORDER BY SUM(word_count) DESC LIMIT 8
                """;
            cmd.Parameters.AddWithValue("$since", sinceUtc);
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                summary.TopApps.Add(new AppUsage
                {
                    AppName = r.GetString(0),
                    Sessions = r.GetInt32(1),
                    Words = r.GetInt32(2),
                });
            }
        }

        summary.StreakDays = ComputeStreak(conn);
        return summary;
    }

    private static int ComputeStreak(SqliteConnection conn)
    {
        // Distinct local dates with at least one dictation — dedupe in SQL so heavy
        // usage never truncates the streak window.
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT DISTINCT date(ts_utc, 'localtime') FROM history";
        var dates = new SortedSet<DateOnly>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            if (!r.IsDBNull(0) && DateOnly.TryParse(r.GetString(0), out var day))
                dates.Add(day);
        }
        if (dates.Count == 0) return 0;

        var today = DateOnly.FromDateTime(DateTime.Now);
        var cursor = dates.Contains(today) ? today
                   : dates.Contains(today.AddDays(-1)) ? today.AddDays(-1)
                   : default;
        if (cursor == default) return 0;

        int streak = 0;
        while (dates.Contains(cursor))
        {
            streak++;
            cursor = cursor.AddDays(-1);
        }
        return streak;
    }

    // ---------- Dictionary ----------

    public List<DictionaryEntry> GetDictionary()
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT id, phrase, misheard, starred, uses FROM dictionary ORDER BY starred DESC, uses DESC, phrase ASC";
        var list = new List<DictionaryEntry>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new DictionaryEntry
            {
                Id = r.GetInt64(0),
                Phrase = r.GetString(1),
                Misheard = r.IsDBNull(2) ? null : r.GetString(2),
                Starred = r.GetInt32(3) != 0,
                Uses = r.GetInt32(4),
            });
        }
        return list;
    }

    public void AddDictionaryEntry(string phrase, string? misheard = null, bool starred = false)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO dictionary(phrase, misheard, starred) VALUES ($p, $m, $s)
            ON CONFLICT(phrase) DO UPDATE SET misheard = $m, starred = $s
            """;
        cmd.Parameters.AddWithValue("$p", phrase.Trim());
        cmd.Parameters.AddWithValue("$m", (object?)NormalizeOptional(misheard) ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$s", starred ? 1 : 0);
        cmd.ExecuteNonQuery();
    }

    public void DeleteDictionaryEntry(long id)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM dictionary WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void SetDictionaryStar(long id, bool starred)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE dictionary SET starred = $s WHERE id = $id";
        cmd.Parameters.AddWithValue("$s", starred ? 1 : 0);
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void IncrementDictionaryUses(IEnumerable<long> ids) => IncrementUses("dictionary", ids);

    // ---------- Snippets ----------

    public List<Snippet> GetSnippets()
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT id, trigger, content, uses FROM snippets ORDER BY uses DESC, trigger ASC";
        var list = new List<Snippet>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new Snippet
            {
                Id = r.GetInt64(0),
                Trigger = r.GetString(1),
                Content = r.GetString(2),
                Uses = r.GetInt32(3),
            });
        }
        return list;
    }

    public void AddSnippet(string trigger, string content)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO snippets(trigger, content) VALUES ($t, $c)
            ON CONFLICT(trigger) DO UPDATE SET content = $c
            """;
        cmd.Parameters.AddWithValue("$t", trigger.Trim());
        cmd.Parameters.AddWithValue("$c", content);
        cmd.ExecuteNonQuery();
    }

    public void DeleteSnippet(long id)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM snippets WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public void IncrementSnippetUses(IEnumerable<long> ids) => IncrementUses("snippets", ids);

    private void IncrementUses(string table, IEnumerable<long> ids)
    {
        var idList = ids.ToList();
        if (idList.Count == 0) return;
        using var conn = Open();
        using var tx = conn.BeginTransaction();
        foreach (var id in idList)
        {
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            // Table name comes from a private whitelist above, never user input.
            cmd.CommandText = $"UPDATE {table} SET uses = uses + 1 WHERE id = $id";
            cmd.Parameters.AddWithValue("$id", id);
            cmd.ExecuteNonQuery();
        }
        tx.Commit();
    }

    private static string? NormalizeOptional(string? value)
    {
        var v = value?.Trim();
        return string.IsNullOrEmpty(v) ? null : v;
    }
}
