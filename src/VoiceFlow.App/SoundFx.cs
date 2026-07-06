using System.IO;
using System.Media;

namespace VoiceFlow.App;

/// <summary>
/// Subtle audio cues (start ping, stop tick, error buzz), synthesized at startup —
/// no bundled asset files needed.
/// </summary>
public sealed class SoundFx : IDisposable
{
    private readonly SoundPlayer _start;
    private readonly SoundPlayer _stop;
    private readonly SoundPlayer _error;

    public SoundFx()
    {
        _start = Create(880, 90);
        _stop = Create(620, 90);
        _error = Create(285, 170);
    }

    public void PlayStart(bool enabled) { if (enabled) TryPlay(_start); }
    public void PlayStop(bool enabled) { if (enabled) TryPlay(_stop); }
    public void PlayError(bool enabled) { if (enabled) TryPlay(_error); }

    public void Dispose()
    {
        _start.Dispose();
        _stop.Dispose();
        _error.Dispose();
    }

    private static void TryPlay(SoundPlayer player)
    {
        try { player.Play(); }
        catch { /* audio device issues must never break dictation */ }
    }

    private static SoundPlayer Create(double frequency, int durationMs)
    {
        const int sampleRate = 22050;
        int sampleCount = sampleRate * durationMs / 1000;
        int dataBytes = sampleCount * 2;

        var ms = new MemoryStream();
        using (var w = new BinaryWriter(ms, System.Text.Encoding.UTF8, leaveOpen: true))
        {
            // Standard 44-byte PCM WAV header.
            w.Write("RIFF"u8);
            w.Write(36 + dataBytes);
            w.Write("WAVE"u8);
            w.Write("fmt "u8);
            w.Write(16);
            w.Write((short)1);            // PCM
            w.Write((short)1);            // mono
            w.Write(sampleRate);
            w.Write(sampleRate * 2);      // byte rate
            w.Write((short)2);            // block align
            w.Write((short)16);           // bits per sample
            w.Write("data"u8);
            w.Write(dataBytes);

            for (int i = 0; i < sampleCount; i++)
            {
                double t = (double)i / sampleRate;
                // Gentle fade in/out to avoid clicks.
                double env = Math.Min(1.0, Math.Min(i / (sampleRate * 0.008), (sampleCount - i) / (sampleRate * 0.03)));
                double sample = Math.Sin(2 * Math.PI * frequency * t) * 0.18 * env;
                w.Write((short)(sample * short.MaxValue));
            }
        }

        ms.Position = 0;
        var player = new SoundPlayer(ms);
        player.Load();
        return player;
    }
}
