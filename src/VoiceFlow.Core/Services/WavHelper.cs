using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace VoiceFlow.Core.Services;

/// <summary>Utilities for turning WAV files into the 16 kHz mono float samples Whisper expects.</summary>
public static class WavHelper
{
    public static float[] ReadAsMono16k(string wavPath)
    {
        using var reader = new AudioFileReader(wavPath);
        ISampleProvider provider = reader;

        if (provider.WaveFormat.Channels > 1)
            provider = new StereoToMonoSampleProvider(provider) { LeftVolume = 0.5f, RightVolume = 0.5f };

        if (provider.WaveFormat.SampleRate != AudioRecorder.SampleRate)
            provider = new WdlResamplingSampleProvider(provider, AudioRecorder.SampleRate);

        var samples = new List<float>();
        var buffer = new float[AudioRecorder.SampleRate];
        int read;
        while ((read = provider.Read(buffer, 0, buffer.Length)) > 0)
            samples.AddRange(buffer.Take(read));
        return samples.ToArray();
    }
}
