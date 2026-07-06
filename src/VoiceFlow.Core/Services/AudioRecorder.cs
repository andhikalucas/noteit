using NAudio.Wave;

namespace VoiceFlow.Core.Services;

/// <summary>
/// Captures microphone audio as 16 kHz mono float samples (what Whisper expects).
/// Uses WaveInEvent, which lets Windows resample from whatever the device natively does.
/// </summary>
public sealed class AudioRecorder : IDisposable
{
    public const int SampleRate = 16000;
    private const int MaxSeconds = 25 * 60; // hard safety cap
    private const int MaxSamples = SampleRate * MaxSeconds;

    private readonly object _gate = new();
    private readonly List<float> _samples = new(SampleRate * 60);
    private WaveInEvent? _waveIn;
    private ManualResetEventSlim? _stopped; // never disposed eagerly: NAudio's device thread may still Set() it

    public bool IsRecording { get; private set; }

    /// <summary>RMS level 0..1 of the latest audio buffer — drives the Flow Bar waveform.</summary>
    public event Action<float>? LevelChanged;

    /// <summary>Raised when the OS reports a capture failure (device unplugged etc.).</summary>
    public event Action<string>? CaptureFailed;

    public static List<(int Number, string Name)> ListDevices()
    {
        var list = new List<(int, string)> { (-1, "System default microphone") };
        for (int i = 0; i < WaveInEvent.DeviceCount; i++)
        {
            var caps = WaveInEvent.GetCapabilities(i);
            list.Add((i, caps.ProductName));
        }
        return list;
    }

    public void Start(int deviceNumber)
    {
        lock (_gate)
        {
            if (IsRecording) return;
            _samples.Clear();
            _stopped = new ManualResetEventSlim(false);

            var waveIn = new WaveInEvent
            {
                DeviceNumber = deviceNumber,
                WaveFormat = new WaveFormat(SampleRate, 16, 1),
                BufferMilliseconds = 50,
            };
            waveIn.DataAvailable += OnData;
            waveIn.RecordingStopped += OnStopped;
            try
            {
                waveIn.StartRecording();
            }
            catch
            {
                waveIn.DataAvailable -= OnData;
                waveIn.RecordingStopped -= OnStopped;
                waveIn.Dispose();
                _stopped = null;
                throw;
            }
            _waveIn = waveIn;
            IsRecording = true;
        }
        Log.Info("Recording started");
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        double sumSquares = 0;
        int count = e.BytesRecorded / 2;
        lock (_gate)
        {
            if (_samples.Count >= MaxSamples) return;
            for (int i = 0; i < count; i++)
            {
                short s = (short)(e.Buffer[2 * i] | (e.Buffer[2 * i + 1] << 8));
                float f = s / 32768f;
                _samples.Add(f);
                sumSquares += f * f;
            }
        }
        if (count > 0)
        {
            var rms = (float)Math.Sqrt(sumSquares / count);
            LevelChanged?.Invoke(Math.Min(1f, rms * 6f)); // scale up: speech RMS is typically ~0.05-0.2
        }
    }

    private void OnStopped(object? sender, StoppedEventArgs e)
    {
        _stopped?.Set();
        if (e.Exception != null)
        {
            Log.Error("Capture stopped with error", e.Exception);
            CaptureFailed?.Invoke(e.Exception.Message);
        }
    }

    /// <summary>Stops capture and returns the recorded samples.</summary>
    public (float[] Samples, TimeSpan Duration) Stop()
    {
        WaveInEvent? waveIn;
        ManualResetEventSlim? stopped;
        lock (_gate)
        {
            if (!IsRecording) return (Array.Empty<float>(), TimeSpan.Zero);
            IsRecording = false;
            waveIn = _waveIn;
            _waveIn = null;
            stopped = _stopped;
            _stopped = null;
        }

        try
        {
            waveIn?.StopRecording();
            stopped?.Wait(150); // brief wait for the final buffer; the tail is ≤50 ms anyway
        }
        catch (Exception ex)
        {
            Log.Error("StopRecording failed", ex);
        }
        finally
        {
            if (waveIn != null)
            {
                waveIn.DataAvailable -= OnData;
                waveIn.RecordingStopped -= OnStopped;
                waveIn.Dispose();
            }
            // stopped is intentionally left to the GC — the device thread may still touch it.
        }

        float[] samples;
        lock (_gate)
        {
            samples = _samples.ToArray();
            _samples.Clear();
        }
        return (samples, TimeSpan.FromSeconds((double)samples.Length / SampleRate));
    }

    /// <summary>Stops capture and throws the audio away (Esc / cancel).</summary>
    public void Abort()
    {
        Stop();
    }

    public void Dispose()
    {
        Abort();
    }
}
