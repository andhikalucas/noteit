using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using VoiceFlow.Core.Models;

namespace VoiceFlow.Core.Services;

/// <summary>
/// Loads and saves <see cref="AppSettings"/> as JSON in the VoiceFlow app-data folder.
/// Thread safe. The LLM API key is protected with DPAPI (current user scope) so the
/// settings file never contains the plaintext key.
/// </summary>
public class SettingsService
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    // DPAPI extra entropy. Note the honest threat model: CurrentUser DPAPI protects the
    // key at rest and off-machine (backups, other accounts), NOT from code already
    // running as this user — that's inherent to any non-elevated desktop app.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("VoiceFlow.ApiKey.v1");

    private readonly object _gate = new();
    private readonly string _path;

    public AppSettings Current { get; private set; }

    public event Action<AppSettings>? Changed;

    public static string AppDataDir
    {
        get
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VoiceFlow");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public SettingsService(string? overridePath = null)
    {
        _path = overridePath ?? Path.Combine(AppDataDir, "settings.json");
        Current = Load();
    }

    private AppSettings Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var json = File.ReadAllText(_path);
                var loaded = JsonSerializer.Deserialize<AppSettings>(json, JsonOptions);
                if (loaded != null) return loaded;
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            Log.Warn($"Settings load failed, using defaults: {ex.Message}");
        }
        return new AppSettings();
    }

    /// <param name="notify">
    /// When false, the file is written but <see cref="Changed"/> is not raised. Used for
    /// high-frequency, non-behavioural updates (e.g. dragging the Flow Bar) that shouldn't
    /// wake every settings subscriber.
    /// </param>
    public void Save(bool notify = true)
    {
        lock (_gate)
        {
            var json = JsonSerializer.Serialize(Current, JsonOptions);
            // Write-then-rename so a crash mid-write never corrupts settings.
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, json);
            File.Move(tmp, _path, overwrite: true);
        }
        if (notify) Changed?.Invoke(Current);
    }

    public void Update(Action<AppSettings> mutate, bool notify = true)
    {
        lock (_gate) mutate(Current);
        Save(notify);
    }

    /// <summary>Stores the API key DPAPI-protected; pass null/empty to clear.</summary>
    public void SetApiKey(string? plaintext)
    {
        if (string.IsNullOrWhiteSpace(plaintext))
        {
            Update(s => s.ApiKeyProtected = null);
            return;
        }
        var protectedBytes = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(plaintext.Trim()), Entropy, DataProtectionScope.CurrentUser);
        Update(s => s.ApiKeyProtected = Convert.ToBase64String(protectedBytes));
    }

    /// <summary>Returns the decrypted API key, or null when unset or undecryptable.</summary>
    public string? GetApiKey()
    {
        var blob = Current.ApiKeyProtected;
        if (string.IsNullOrEmpty(blob)) return null;
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(blob), Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException)
        {
            Log.Warn($"API key unprotect failed: {ex.Message}");
            return null;
        }
    }

    public bool HasApiKey => !string.IsNullOrEmpty(Current.ApiKeyProtected);
}
