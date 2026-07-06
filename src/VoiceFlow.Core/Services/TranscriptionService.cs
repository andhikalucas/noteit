using System.Text;
using Whisper.net;

namespace VoiceFlow.Core.Services;

public record TranscriptionResult(string Text, string Language);

/// <summary>Raised when a model file exists but whisper.cpp can't load it (corrupt/truncated).</summary>
public class ModelLoadException : Exception
{
    public string ModelPath { get; }

    public ModelLoadException(string modelPath, Exception inner)
        : base($"Failed to load Whisper model '{Path.GetFileName(modelPath)}'.", inner)
    {
        ModelPath = modelPath;
    }
}

/// <summary>
/// Local speech-to-text via whisper.cpp (Whisper.net bindings). Keeps one factory alive
/// per model file; inference is serialized because whisper.cpp contexts are not thread safe.
/// </summary>
public sealed class TranscriptionService : IDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private WhisperFactory? _factory;
    private string? _loadedPath;

    public async Task<TranscriptionResult> TranscribeAsync(
        string modelPath,
        float[] samples,
        string language = "auto",
        string? initialPrompt = null,
        CancellationToken ct = default)
    {
        if (samples.Length < AudioRecorder.SampleRate / 4)
            return new TranscriptionResult("", language); // < 250 ms — nothing to hear

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var factory = GetFactory(modelPath);
            var builder = factory.CreateBuilder()
                .WithThreads(Math.Clamp(Environment.ProcessorCount / 2, 2, 8));

            if (string.IsNullOrEmpty(language) || language == "auto")
                builder = builder.WithLanguageDetection();
            else
                builder = builder.WithLanguage(language);

            if (!string.IsNullOrWhiteSpace(initialPrompt))
                builder = builder.WithPrompt(initialPrompt);

            var sb = new StringBuilder();
            var detected = language;

            await using var processor = builder.Build();
            await foreach (var segment in processor.ProcessAsync(samples, ct).ConfigureAwait(false))
            {
                sb.Append(segment.Text);
                if (!string.IsNullOrEmpty(segment.Language))
                    detected = segment.Language;
            }

            var text = CleanWhisperArtifacts(sb.ToString());
            return new TranscriptionResult(text, detected);
        }
        finally
        {
            _gate.Release();
        }
    }

    private WhisperFactory GetFactory(string modelPath)
    {
        if (_factory != null && _loadedPath == modelPath) return _factory;
        _factory?.Dispose();
        _factory = null;
        _loadedPath = null;

        Log.Info($"Loading Whisper model: {Path.GetFileName(modelPath)}");
        try
        {
            _factory = WhisperFactory.FromPath(modelPath);
        }
        catch (Exception ex)
        {
            throw new ModelLoadException(modelPath, ex);
        }
        _loadedPath = modelPath;
        return _factory;
    }

    /// <summary>Strips non-speech annotations Whisper sometimes emits, e.g. [BLANK_AUDIO], (music).</summary>
    public static string CleanWhisperArtifacts(string text)
    {
        text = System.Text.RegularExpressions.Regex.Replace(text, @"\[[^\]]*\]|\([^)]*\)|♪+", " ");
        text = System.Text.RegularExpressions.Regex.Replace(text, @"\s{2,}", " ");
        return text.Trim();
    }

    public void Dispose()
    {
        _factory?.Dispose();
        _gate.Dispose();
    }
}
