using System.Windows;
using System.Windows.Threading;
using VoiceFlow.App.Windows;
using VoiceFlow.Core.Services;
using WinForms = System.Windows.Forms;
using Drawing = System.Drawing;

namespace VoiceFlow.App;

public partial class App : Application
{
    private Mutex? _singleInstance;
    private SettingsService _settings = null!;
    private HistoryStore _store = null!;
    private FlowController _controller = null!;
    private FlowBarWindow? _flowBar;
    private MainWindow? _mainWindow;
    private ScratchpadWindow? _scratchpad;
    private WinForms.NotifyIcon? _tray;
    private Drawing.Icon? _trayIcon;
    private WinForms.ToolStripMenuItem? _pauseItem;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Crash handlers first — everything after this is observable.
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            Log.Error("Unhandled exception", args.ExceptionObject as Exception);
        DispatcherUnhandledException += (_, args) =>
        {
            Log.Error("Dispatcher exception", args.Exception);
            args.Handled = true; // keep the tray app alive
        };

        bool isNew;
        try
        {
            _singleInstance = new Mutex(true, @"Local\VoiceFlow_SingleInstance", out isNew);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or WaitHandleCannotBeOpenedException)
        {
            // Mutex exists but is inaccessible (squatted with a deny ACL) — treat as running.
            Log.Warn($"Single-instance mutex inaccessible: {ex.GetType().Name}");
            isNew = false;
        }
        if (!isNew)
        {
            MessageBox.Show("VoiceFlow is already running — look for the pill at the bottom of your screen.",
                "VoiceFlow", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        _settings = new SettingsService();
        _store = new HistoryStore();
        _controller = new FlowController(Dispatcher, _settings, _store);
        _controller.Start();

        SetupTray();

        if (_settings.Current.ShowFlowBar)
            ShowFlowBar();

        _settings.Changed += OnSettingsChanged;

        // First run (or missing model): open the main window so the user can set up.
        if (!_settings.Current.FirstRunCompleted ||
            !ModelDownloader.IsDownloaded(_settings.Current.ModelSize))
        {
            ShowMainWindow();
        }

        Log.Info("VoiceFlow started");
    }

    private void OnSettingsChanged(Core.Models.AppSettings s)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (s.ShowFlowBar) ShowFlowBar();
            else HideFlowBar();
            AutoStart.Set(s.LaunchAtLogin);
        });
    }

    private void ShowFlowBar()
    {
        _flowBar ??= new FlowBarWindow(_controller, _settings, QuitFromFlowBar);
        _flowBar.Show();
        _flowBar.SyncFromSettings();
    }

    private void HideFlowBar() => _flowBar?.Hide();

    /// <summary>The Flow Bar's × button quits VoiceFlow entirely (OnExit performs cleanup).</summary>
    private void QuitFromFlowBar()
    {
        Log.Info("Quit requested from Flow Bar close button");
        Shutdown();
    }

    public void ShowMainWindow()
    {
        if (_mainWindow == null)
        {
            _mainWindow = new MainWindow(_settings, _store, _controller);
            _mainWindow.Closed += (_, _) => _mainWindow = null;
            _mainWindow.Show();
        }
        else
        {
            _mainWindow.Show();
            if (_mainWindow.WindowState == WindowState.Minimized)
                _mainWindow.WindowState = WindowState.Normal;
            _mainWindow.Activate();
        }
    }

    public void ShowScratchpad()
    {
        if (_scratchpad == null)
        {
            _scratchpad = new ScratchpadWindow();
            _scratchpad.Closed += (_, _) => _scratchpad = null;
            _scratchpad.Show();
        }
        else
        {
            _scratchpad.Show();
            _scratchpad.Activate();
        }
    }

    // ----- Tray -----

    private void SetupTray()
    {
        _trayIcon = CreateTrayIcon();
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open VoiceFlow", null, (_, _) => Dispatcher.BeginInvoke(ShowMainWindow));
        menu.Items.Add("Scratchpad", null, (_, _) => Dispatcher.BeginInvoke(ShowScratchpad));
        menu.Items.Add("Start hands-free dictation", null, (_, _) => Dispatcher.BeginInvoke(() => _controller.ToggleHandsFree()));
        menu.Items.Add("Show Flow Bar", null, (_, _) => Dispatcher.BeginInvoke(() =>
            _settings.Update(s => { s.ShowFlowBar = true; s.FlowBarMinimized = false; })));
        menu.Items.Add(new WinForms.ToolStripSeparator());
        _pauseItem = new WinForms.ToolStripMenuItem("Pause VoiceFlow") { CheckOnClick = true };
        _pauseItem.CheckedChanged += (_, _) => _controller.Enabled = !_pauseItem.Checked;
        menu.Items.Add(_pauseItem);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => Dispatcher.BeginInvoke(() => Shutdown()));

        _tray = new WinForms.NotifyIcon
        {
            Icon = _trayIcon,
            Text = "VoiceFlow — hold Ctrl+Win to dictate",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.DoubleClick += (_, _) => Dispatcher.BeginInvoke(ShowMainWindow);
    }

    /// <summary>Draws the tray icon at runtime (purple rounded square + white mic).</summary>
    private static Drawing.Icon CreateTrayIcon()
    {
        using var bmp = new Drawing.Bitmap(32, 32);
        using (var g = Drawing.Graphics.FromImage(bmp))
        {
            g.SmoothingMode = Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var bg = new Drawing.SolidBrush(Drawing.Color.FromArgb(139, 92, 246));
            using var path = RoundedRect(new Drawing.Rectangle(1, 1, 30, 30), 9);
            g.FillPath(bg, path);

            using var white = new Drawing.SolidBrush(Drawing.Color.White);
            using var pen = new Drawing.Pen(Drawing.Color.White, 2.4f)
            { StartCap = Drawing.Drawing2D.LineCap.Round, EndCap = Drawing.Drawing2D.LineCap.Round };

            // Mic capsule
            using var capsule = RoundedRect(new Drawing.Rectangle(12, 6, 8, 13), 4);
            g.FillPath(white, capsule);
            // Cradle arc + stem
            g.DrawArc(pen, 9f, 9f, 14f, 13f, 20f, 140f);
            g.DrawLine(pen, 16f, 22.5f, 16f, 26f);
        }
        var hIcon = bmp.GetHicon();
        // Clone so we own the icon independent of the HICON's lifetime.
        using var tmp = Drawing.Icon.FromHandle(hIcon);
        var icon = (Drawing.Icon)tmp.Clone();
        DestroyIcon(hIcon);
        return icon;
    }

    private static Drawing.Drawing2D.GraphicsPath RoundedRect(Drawing.Rectangle r, int radius)
    {
        var path = new Drawing.Drawing2D.GraphicsPath();
        int d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr hIcon);

    protected override void OnExit(ExitEventArgs e)
    {
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
        }
        _trayIcon?.Dispose();
        _controller?.Dispose();
        _singleInstance?.Dispose();
        Log.Info("VoiceFlow exited");
        base.OnExit(e);
    }
}
