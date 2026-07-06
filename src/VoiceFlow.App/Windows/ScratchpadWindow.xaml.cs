using System.IO;
using System.Windows;
using System.Windows.Threading;
using VoiceFlow.Core.Services;

namespace VoiceFlow.App.Windows;

/// <summary>
/// A lightweight always-on-top notepad. Dictate into it from anywhere; contents autosave
/// to %APPDATA%\VoiceFlow\scratchpad.txt with a short debounce.
/// </summary>
public partial class ScratchpadWindow : Window
{
    private static string NotePath => Path.Combine(SettingsService.AppDataDir, "scratchpad.txt");

    private readonly DispatcherTimer _saveTimer;
    private bool _loading = true;

    public ScratchpadWindow()
    {
        InitializeComponent();

        var area = SystemParameters.WorkArea;
        Left = area.Right - Width - 24;
        Top = area.Top + 80;

        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(800) };
        _saveTimer.Tick += (_, _) => { _saveTimer.Stop(); Save(); };

        try
        {
            if (File.Exists(NotePath))
                NoteBox.Text = File.ReadAllText(NotePath);
        }
        catch (IOException ex)
        {
            Log.Warn($"Scratchpad load failed: {ex.Message}");
        }
        _loading = false;

        Closing += (_, _) => Save();
    }

    private void NoteBox_TextChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _saveTimer.Stop();
        _saveTimer.Start();
    }

    private void Topmost_Click(object sender, RoutedEventArgs e)
    {
        Topmost = TopmostCheck.IsChecked == true;
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(NotePath, NoteBox.Text);
        }
        catch (IOException ex)
        {
            Log.Warn($"Scratchpad save failed: {ex.Message}");
        }
    }
}
