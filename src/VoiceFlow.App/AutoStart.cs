using Microsoft.Win32;
using VoiceFlow.Core.Services;

namespace VoiceFlow.App;

/// <summary>Registers/unregisters VoiceFlow in HKCU Run so it launches at login.</summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "VoiceFlow";

    public static void Set(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (key == null) return;
            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (exe != null)
                    key.SetValue(ValueName, $"\"{exe}\"");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex)
        {
            Log.Error("AutoStart update failed", ex);
        }
    }

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            // Only "enabled" if the value actually points at this executable —
            // a tampered/stale path is treated as disabled and rewritten on toggle.
            var value = key?.GetValue(ValueName) as string;
            var exe = Environment.ProcessPath;
            return value != null && exe != null &&
                   string.Equals(value.Trim('"'), exe, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}
