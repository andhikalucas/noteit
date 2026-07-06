using System.Text;

namespace VoiceFlow.Core.Services;

/// <summary>
/// Minimal rolling file logger (no external deps). Writes to %APPDATA%\VoiceFlow\logs\voiceflow.log,
/// rotating at ~1 MB. Never throws to callers and never logs dictated text content.
/// </summary>
public static class Log
{
    private const long MaxBytes = 1_000_000;
    private static readonly object Gate = new();
    private static string? _path;

    public static string LogPath
    {
        get
        {
            if (_path == null)
            {
                var dir = Path.Combine(SettingsService.AppDataDir, "logs");
                Directory.CreateDirectory(dir);
                _path = Path.Combine(dir, "voiceflow.log");
            }
            return _path;
        }
    }

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message, Exception? ex = null)
        => Write("ERROR", ex == null ? message : $"{message} :: {ex.GetType().Name}: {ex.Message}");

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                var file = new FileInfo(LogPath);
                if (file.Exists && file.Length > MaxBytes)
                {
                    var old = LogPath + ".1";
                    File.Delete(old);
                    File.Move(LogPath, old);
                }
                var line = $"{DateTime.UtcNow:yyyy-MM-dd HH:mm:ss.fff}Z [{level}] {message}{Environment.NewLine}";
                File.AppendAllText(LogPath, line, Encoding.UTF8);
            }
        }
        catch
        {
            // Logging must never take the app down.
        }
    }
}
