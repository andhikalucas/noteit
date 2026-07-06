using System.Diagnostics;
using System.Text;
using static VoiceFlow.App.Interop.NativeMethods;

namespace VoiceFlow.App.Interop;

public record ForegroundInfo(IntPtr Hwnd, string ProcessName, string WindowTitle)
{
    public static readonly ForegroundInfo Unknown = new(IntPtr.Zero, "", "");
}

/// <summary>Identifies the app that will receive dictated text (for insights + privacy pause).</summary>
public static class ForegroundApp
{
    public static ForegroundInfo Get()
    {
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return ForegroundInfo.Unknown;

            GetWindowThreadProcessId(hwnd, out var pid);
            string name = "";
            if (pid != 0)
            {
                using var process = Process.GetProcessById((int)pid);
                name = process.ProcessName;
            }

            var sb = new StringBuilder(256);
            GetWindowText(hwnd, sb, sb.Capacity);
            return new ForegroundInfo(hwnd, name, sb.ToString());
        }
        catch
        {
            // Elevated or exiting processes can deny access — callers treat "" as unidentifiable.
            return ForegroundInfo.Unknown;
        }
    }
}
