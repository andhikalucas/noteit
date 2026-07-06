using System.Windows.Automation;
using VoiceFlow.Core.Services;

namespace VoiceFlow.App.Interop;

/// <summary>
/// Detects whether keyboard focus is currently in a password field, so Flow can refuse
/// to record/inject there (mirrors Wispr's banking/privacy protection).
///
/// Fails open by design: blocking all dictation whenever UI Automation is slow or denied
/// would make the app unusable — but every degradation is logged so it's observable.
/// </summary>
public static class FocusGuard
{
    public static bool IsPasswordFieldFocused(int timeoutMs = 400)
    {
        try
        {
            // UI Automation can occasionally block; keep it on a worker with a timeout.
            var task = Task.Run(() =>
            {
                var focused = AutomationElement.FocusedElement;
                if (focused == null) return false;
                var value = focused.GetCurrentPropertyValue(AutomationElement.IsPasswordProperty);
                return value is true;
            });
            if (!task.Wait(timeoutMs))
            {
                Log.Warn("Password-field check timed out — proceeding without it");
                return false;
            }
            return task.Result;
        }
        catch (Exception ex)
        {
            Log.Warn($"Password-field check failed ({ex.GetType().Name}) — proceeding without it");
            return false;
        }
    }
}
