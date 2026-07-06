using System.Security.Cryptography;
using System.Text.RegularExpressions;
using VoiceFlow.Core.Models;

namespace VoiceFlow.Core.Services;

/// <summary>
/// Downloads Whisper GGML models from the official ggerganov/whisper.cpp Hugging Face repo
/// over HTTPS, verifying size and SHA-256 against the repo's LFS pointer before install.
/// </summary>
public class ModelDownloader
{
    private const string RepoBase = "https://huggingface.co/ggerganov/whisper.cpp";

    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromMinutes(30),
        DefaultRequestHeaders = { { "User-Agent", "VoiceFlow/1.0" } },
    };

    public static string ModelsDir
    {
        get
        {
            var dir = Path.Combine(SettingsService.AppDataDir, "models");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public static string FileNameFor(WhisperModelSize size) => size switch
    {
        WhisperModelSize.Tiny => "ggml-tiny.bin",
        WhisperModelSize.Small => "ggml-small.bin",
        _ => "ggml-base.bin",
    };

    public static int ApproxMb(WhisperModelSize size) => size switch
    {
        WhisperModelSize.Tiny => 75,
        WhisperModelSize.Small => 466,
        _ => 142,
    };

    public static string GetPath(WhisperModelSize size) => Path.Combine(ModelsDir, FileNameFor(size));

    public static bool IsDownloaded(WhisperModelSize size)
    {
        var fi = new FileInfo(GetPath(size));
        return fi.Exists && fi.Length > 1_000_000;
    }

    /// <summary>
    /// Ensures the model file exists locally, downloading and verifying it if needed.
    /// Progress is reported 0..1.
    /// </summary>
    public async Task<string> EnsureAsync(WhisperModelSize size, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        var path = GetPath(size);
        if (IsDownloaded(size)) return path;

        var file = FileNameFor(size);
        Log.Info($"Downloading model {file}");

        // 1. LFS pointer gives us the expected SHA-256 and byte size.
        var pointer = await Http.GetStringAsync($"{RepoBase}/raw/main/{file}", ct).ConfigureAwait(false);
        var shaMatch = Regex.Match(pointer, @"sha256:([0-9a-f]{64})");
        var sizeMatch = Regex.Match(pointer, @"size (\d+)");
        if (!shaMatch.Success || !sizeMatch.Success)
            throw new InvalidDataException("Could not read model checksum from Hugging Face.");
        var expectedSha = shaMatch.Groups[1].Value;
        var expectedSize = long.Parse(sizeMatch.Groups[1].Value);

        // 2. Stream the real file to a temp path, hashing as we go.
        var tmp = path + ".download";
        try
        {
            using var response = await Http.GetAsync(
                $"{RepoBase}/resolve/main/{file}", HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var input = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
            await using (var output = File.Create(tmp))
            {
                var buffer = new byte[81920];
                long total = 0;
                int read;
                while ((read = await input.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
                {
                    total += read;
                    // Never let a misbehaving server stream past the declared size (disk-fill guard).
                    if (total > expectedSize)
                        throw new InvalidDataException("Model download exceeded its declared size — aborting.");
                    await output.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                    hash.AppendData(buffer, 0, read);
                    progress?.Report(Math.Min(1.0, (double)total / expectedSize));
                }
            }

            // 3. Verify before moving into place.
            var actualSha = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
            var actualSize = new FileInfo(tmp).Length;
            if (actualSize != expectedSize || actualSha != expectedSha)
                throw new InvalidDataException(
                    $"Model download failed verification (size {actualSize}/{expectedSize}, sha match: {actualSha == expectedSha}).");

            File.Move(tmp, path, overwrite: true);
            Log.Info($"Model {file} downloaded and verified");
            return path;
        }
        catch
        {
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { /* best effort */ }
            throw;
        }
    }
}
