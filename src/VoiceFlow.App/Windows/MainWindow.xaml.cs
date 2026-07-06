using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;

namespace VoiceFlow.App.Windows;

public partial class MainWindow : Window
{
    private record HistoryRow(long Id, string Header, string Text);
    private record DictRow(long Id, string Phrase, string Detail, bool Starred);
    private record SnippetRow(long Id, string Trigger, string Preview, string UsesText);
    private record AppRow(string Name, string Detail);

    private static readonly (string Code, string Name)[] Languages =
    {
        ("auto", "Auto-detect"), ("en", "English"), ("es", "Spanish"), ("fr", "French"),
        ("de", "German"), ("it", "Italian"), ("pt", "Portuguese"), ("nl", "Dutch"),
        ("pl", "Polish"), ("ru", "Russian"), ("uk", "Ukrainian"), ("tr", "Turkish"),
        ("ar", "Arabic"), ("hi", "Hindi"), ("id", "Indonesian"), ("ja", "Japanese"),
        ("ko", "Korean"), ("zh", "Chinese"), ("vi", "Vietnamese"), ("th", "Thai"),
    };

    private readonly SettingsService _settings;
    private readonly HistoryStore _store;
    private readonly FlowController _controller;
    private readonly ModelDownloader _downloader = new();
    private readonly System.Windows.Threading.DispatcherTimer _searchDebounce = new()
    {
        Interval = TimeSpan.FromMilliseconds(250),
    };
    private bool _loading = true;
    private bool _downloading;

    public MainWindow(SettingsService settings, HistoryStore store, FlowController controller)
    {
        InitializeComponent();
        _settings = settings;
        _store = store;
        _controller = controller;

        _searchDebounce.Tick += (_, _) => { _searchDebounce.Stop(); RefreshHistory(); };

        PopulateSettingsControls();
        RefreshAll();
        _loading = false;

        _controller.StateChanged += OnFlowStateChanged;
        Closed += (_, _) =>
        {
            _controller.StateChanged -= OnFlowStateChanged;
            if (!_settings.Current.FirstRunCompleted)
                _settings.Update(s => s.FirstRunCompleted = true);
        };
    }

    private void OnFlowStateChanged()
    {
        // After a dictation completes, live-refresh whatever list is on screen.
        if (_controller.State == FlowState.Idle)
        {
            RefreshRecent();
            if (HistoryPanel.Visibility == Visibility.Visible) RefreshHistory();
            if (InsightsPanel.Visibility == Visibility.Visible) RefreshInsights();
        }
    }

    // ----- Navigation -----

    private void Nav_Checked(object sender, RoutedEventArgs e)
    {
        if (HomePanel == null) return; // during InitializeComponent
        var tag = (string)((RadioButton)sender).Tag;
        HomePanel.Visibility = tag == "Home" ? Visibility.Visible : Visibility.Collapsed;
        HistoryPanel.Visibility = tag == "History" ? Visibility.Visible : Visibility.Collapsed;
        DictionaryPanel.Visibility = tag == "Dictionary" ? Visibility.Visible : Visibility.Collapsed;
        SnippetsPanel.Visibility = tag == "Snippets" ? Visibility.Visible : Visibility.Collapsed;
        InsightsPanel.Visibility = tag == "Insights" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPanel.Visibility = tag == "Settings" ? Visibility.Visible : Visibility.Collapsed;

        switch (tag)
        {
            case "History": RefreshHistory(); break;
            case "Dictionary": RefreshDictionary(); break;
            case "Snippets": RefreshSnippets(); break;
            case "Insights": RefreshInsights(); break;
        }
    }

    private void RefreshAll()
    {
        RefreshRecent();
        RefreshDictionary();
        RefreshSnippets();
        RefreshInsights();
        RefreshModelState();
    }

    // ----- Settings controls -----

    private void PopulateSettingsControls()
    {
        var s = _settings.Current;

        foreach (var (number, name) in AudioRecorder.ListDevices())
            MicCombo.Items.Add(new ComboBoxItem { Content = name, Tag = number });
        SelectByTag(MicCombo, s.MicDeviceNumber);

        foreach (var (code, name) in Languages)
            LangCombo.Items.Add(new ComboBoxItem { Content = name, Tag = code });
        SelectByTag(LangCombo, s.Language);

        foreach (var level in new[] { CleanupLevel.None, CleanupLevel.Light, CleanupLevel.Medium, CleanupLevel.High })
            CleanupCombo.Items.Add(new ComboBoxItem { Content = level.ToString(), Tag = level });
        SelectByTag(CleanupCombo, s.Cleanup);

        foreach (var size in new[] { WhisperModelSize.Tiny, WhisperModelSize.Base, WhisperModelSize.Small })
            ModelCombo.Items.Add(new ComboBoxItem
            {
                Content = $"{size} (~{ModelDownloader.ApproxMb(size)} MB)",
                Tag = size,
            });
        SelectByTag(ModelCombo, s.ModelSize);

        SoundsCheck.IsChecked = s.PlaySounds;
        FlowBarCheck.IsChecked = s.ShowFlowBar;
        LaunchCheck.IsChecked = s.LaunchAtLogin;
        KeystrokesCheck.IsChecked = s.InsertViaKeystrokes;
        RestoreClipCheck.IsChecked = s.RestoreClipboard;
        PasswordPauseCheck.IsChecked = s.PauseInPasswordFields;
        PrivacyAppsBox.Text = string.Join(", ", s.PrivacyApps);
        ApiUrlBox.Text = s.ApiBaseUrl;
        ApiModelBox.Text = s.ApiModel;
        ApiStatusText.Text = _settings.HasApiKey ? "A key is saved (encrypted)." : "No key saved.";
        DataPathText.Text = $"Stored in {SettingsService.AppDataDir}";
    }

    private static void SelectByTag(ComboBox combo, object tag)
    {
        foreach (ComboBoxItem item in combo.Items)
        {
            if (Equals(item.Tag, tag))
            {
                combo.SelectedItem = item;
                return;
            }
        }
        if (combo.Items.Count > 0) combo.SelectedIndex = 0;
    }

    private void Setting_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _settings.Update(s =>
        {
            if (MicCombo.SelectedItem is ComboBoxItem mic) s.MicDeviceNumber = (int)mic.Tag;
            if (LangCombo.SelectedItem is ComboBoxItem lang) s.Language = (string)lang.Tag;
            if (CleanupCombo.SelectedItem is ComboBoxItem cl) s.Cleanup = (CleanupLevel)cl.Tag;
            if (ModelCombo.SelectedItem is ComboBoxItem model) s.ModelSize = (WhisperModelSize)model.Tag;
            s.PlaySounds = SoundsCheck.IsChecked == true;
            s.ShowFlowBar = FlowBarCheck.IsChecked == true;
            s.LaunchAtLogin = LaunchCheck.IsChecked == true;
            s.InsertViaKeystrokes = KeystrokesCheck.IsChecked == true;
            s.RestoreClipboard = RestoreClipCheck.IsChecked == true;
            s.PauseInPasswordFields = PasswordPauseCheck.IsChecked == true;
        });
        RefreshModelState();
    }

    private void SavePrivacyApps_Click(object sender, RoutedEventArgs e)
    {
        var apps = PrivacyAppsBox.Text
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(a => a.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        _settings.Update(s => s.PrivacyApps = apps);
        PrivacyAppsBox.Text = string.Join(", ", apps);
    }

    private void SaveApi_Click(object sender, RoutedEventArgs e)
    {
        var url = ApiUrlBox.Text.Trim();
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps)
        {
            ApiStatusText.Text = "Base URL must start with https://";
            return;
        }
        _settings.Update(s =>
        {
            s.ApiBaseUrl = url;
            s.ApiModel = ApiModelBox.Text.Trim();
        });
        if (ApiKeyBox.Password.Length > 0)
        {
            _settings.SetApiKey(ApiKeyBox.Password);
            ApiKeyBox.Clear();
        }
        ApiStatusText.Text = _settings.HasApiKey ? "Saved. Key is encrypted with DPAPI." : "Settings saved (no key).";
    }

    private void ClearApi_Click(object sender, RoutedEventArgs e)
    {
        _settings.SetApiKey(null);
        ApiKeyBox.Clear();
        ApiStatusText.Text = "Key removed.";
    }

    private void OpenDataFolder_Click(object sender, RoutedEventArgs e)
    {
        Process.Start(new ProcessStartInfo
        {
            FileName = SettingsService.AppDataDir,
            UseShellExecute = true,
        });
    }

    // ----- Model download -----

    private void RefreshModelState()
    {
        var size = _settings.Current.ModelSize;
        bool downloaded = ModelDownloader.IsDownloaded(size);
        ModelStateText.Text = downloaded
            ? $"Model \"{size}\" is ready — transcription runs fully offline."
            : $"Model \"{size}\" is not downloaded yet (~{ModelDownloader.ApproxMb(size)} MB).";
        DownloadModelBtn.Visibility = downloaded ? Visibility.Collapsed : Visibility.Visible;
        ModelBanner.Visibility = downloaded ? Visibility.Collapsed : Visibility.Visible;
        SidebarModelText.Text = downloaded ? $"Model: {size} ✓" : "Model: not downloaded";
    }

    private async void DownloadModel_Click(object sender, RoutedEventArgs e)
    {
        if (_downloading) return;
        _downloading = true;
        var size = _settings.Current.ModelSize;

        BannerProgress.Visibility = Visibility.Visible;
        ModelProgress.Visibility = Visibility.Visible;
        BannerDownloadBtn.IsEnabled = false;
        DownloadModelBtn.IsEnabled = false;

        var progress = new Progress<double>(p =>
        {
            BannerProgress.Value = p * 100;
            ModelProgress.Value = p * 100;
            BannerPercent.Text = $"{p * 100:0}%";
        });

        try
        {
            await _downloader.EnsureAsync(size, progress);
            BannerPercent.Text = "Done!";
            _settings.Update(s => s.FirstRunCompleted = true);
        }
        catch (Exception ex)
        {
            Log.Error("Model download failed", ex);
            BannerPercent.Text = "Download failed — check your connection and try again.";
            BannerDetail.Text = ex.Message;
        }
        finally
        {
            _downloading = false;
            BannerDownloadBtn.IsEnabled = true;
            DownloadModelBtn.IsEnabled = true;
            BannerProgress.Visibility = Visibility.Collapsed;
            ModelProgress.Visibility = Visibility.Collapsed;
            RefreshModelState();
        }
    }

    // ----- Home / recent -----

    private void RefreshRecent()
    {
        var rows = _store.GetRecentSessions(5)
            .Select(s => new HistoryRow(s.Id, FormatHeader(s), s.FormattedText))
            .ToList();
        RecentList.ItemsSource = rows;
        NoRecentText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static string FormatHeader(DictationSession s)
    {
        var local = s.TimestampUtc.ToLocalTime();
        var when = local.Date == DateTime.Today ? $"Today {local:HH:mm}"
                 : local.Date == DateTime.Today.AddDays(-1) ? $"Yesterday {local:HH:mm}"
                 : local.ToString("MMM d, HH:mm");
        var app = string.IsNullOrEmpty(s.AppName) ? "" : $"  ·  {s.AppName}";
        var mode = s.Mode switch
        {
            DictationMode.HandsFree => "  ·  hands-free",
            DictationMode.Command => "  ·  command",
            _ => "",
        };
        var lang = string.IsNullOrEmpty(s.Language) || s.Language == "en" ? "" : $"  ·  {s.Language}";
        return $"{when}{app}{mode}{lang}  ·  {s.WordCount} words";
    }

    // ----- History -----

    private void RefreshHistory()
    {
        var rows = _store.GetRecentSessions(200, HistorySearch.Text)
            .Select(s => new HistoryRow(s.Id, FormatHeader(s), s.FormattedText))
            .ToList();
        HistoryList.ItemsSource = rows;
        NoHistoryText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void HistorySearch_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_loading) return;
        _searchDebounce.Stop();
        _searchDebounce.Start();
    }

    private void CopyHistory_Click(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is HistoryRow row)
            Clipboard.SetDataObject(row.Text, true);
    }

    private void DeleteHistory_Click(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is HistoryRow row)
        {
            _store.DeleteSession(row.Id);
            RefreshHistory();
            RefreshRecent();
        }
    }

    private void ClearHistory_Click(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(this,
            "Delete all dictation history? This can't be undone.",
            "VoiceFlow", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (confirm == MessageBoxResult.Yes)
        {
            _store.ClearHistory();
            RefreshHistory();
            RefreshRecent();
            RefreshInsights();
        }
    }

    // ----- Dictionary -----

    private void RefreshDictionary()
    {
        var rows = _store.GetDictionary()
            .Select(d => new DictRow(
                d.Id,
                d.Phrase,
                (d.Misheard != null ? $"fixes “{d.Misheard}”" : "") +
                (d.Uses > 0 ? $"   used {d.Uses}×" : ""),
                d.Starred))
            .ToList();
        DictList.ItemsSource = rows;
        NoDictText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void AddDictionary_Click(object sender, RoutedEventArgs e)
    {
        var phrase = DictPhraseBox.Text.Trim();
        if (phrase.Length == 0) return;
        _store.AddDictionaryEntry(phrase, DictMisheardBox.Text);
        DictPhraseBox.Clear();
        DictMisheardBox.Clear();
        RefreshDictionary();
    }

    private void StarDictionary_Click(object sender, RoutedEventArgs e)
    {
        var toggle = (ToggleButton)sender;
        if (toggle.Tag is DictRow row)
            _store.SetDictionaryStar(row.Id, toggle.IsChecked == true);
    }

    private void DeleteDictionary_Click(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is DictRow row)
        {
            _store.DeleteDictionaryEntry(row.Id);
            RefreshDictionary();
        }
    }

    // ----- Snippets -----

    private void RefreshSnippets()
    {
        var rows = _store.GetSnippets()
            .Select(s => new SnippetRow(
                s.Id,
                s.Trigger,
                s.Content.Length > 140 ? s.Content[..140] + "…" : s.Content,
                s.Uses > 0 ? $"used {s.Uses}×" : ""))
            .ToList();
        SnippetList.ItemsSource = rows;
        NoSnippetsText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void AddSnippet_Click(object sender, RoutedEventArgs e)
    {
        var trigger = SnippetTriggerBox.Text.Trim();
        var content = SnippetContentBox.Text;
        if (trigger.Length == 0 || content.Trim().Length == 0) return;
        _store.AddSnippet(trigger, content);
        SnippetTriggerBox.Clear();
        SnippetContentBox.Clear();
        RefreshSnippets();
    }

    private void DeleteSnippet_Click(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is SnippetRow row)
        {
            _store.DeleteSnippet(row.Id);
            RefreshSnippets();
        }
    }

    // ----- Insights -----

    private void RefreshInsights()
    {
        var insights = _store.GetInsights(30);
        StatWords.Text = insights.TotalWords.ToString("N0");
        StatWpm.Text = insights.AverageWpm.ToString("0");
        StatSessions.Text = insights.TotalSessions.ToString("N0");
        StatStreak.Text = insights.StreakDays.ToString();

        var rows = insights.TopApps
            .Select(a => new AppRow(a.AppName, $"{a.Words:N0} words · {a.Sessions} dictations"))
            .ToList();
        AppsList.ItemsSource = rows;
        NoAppsText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
}
