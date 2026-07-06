using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using VoiceFlow.App.Interop;
using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;
using WinFormsScreen = System.Windows.Forms.Screen;

namespace VoiceFlow.App.Windows;

/// <summary>
/// The floating pill overlay. Never takes keyboard focus (WS_EX_NOACTIVATE) so interacting
/// with it while typing elsewhere doesn't derail the user. It can be:
///  • dragged anywhere on screen (position persisted; auto-centering stops once moved),
///  • minimized to a compact mic handle (click the handle to expand),
///  • closed via the × button, which quits VoiceFlow entirely.
/// On hover it smoothly expands to reveal the minimize/close controls.
/// A plain click (no drag) toggles hands-free dictation, exactly as before.
/// </summary>
public partial class FlowBarWindow : Window
{
    private const int WaveBarCount = 14;
    private const int IdleBarCount = 5;
    private const double DragThreshold = 4.0; // DIPs of movement before a press becomes a drag

    private static readonly Thickness NormalPadding = new(14, 7, 14, 7);
    private static readonly Thickness MinimizedPadding = new(7);
    private static readonly Duration RevealDuration = new(TimeSpan.FromMilliseconds(170));

    private readonly FlowController _controller;
    private readonly SettingsService _settings;
    private readonly Action _onQuit;
    private readonly DispatcherTimer _animTimer;
    private readonly Queue<float> _levels = new();
    private readonly Rectangle[] _waveRects = new Rectangle[WaveBarCount];

    private DateTime _recordingStarted;
    private DateTime _flashUntil = DateTime.MinValue;
    private double _phase;
    private double _controlsWidth; // natural width of the hover controls, measured lazily

    // Drag state
    private bool _pressed;
    private bool _dragging;
    private Point _grabPoint;

    private bool _minimized;
    private bool _userPositioned;

    public FlowBarWindow(FlowController controller, SettingsService settings, Action onQuit)
    {
        InitializeComponent();
        _controller = controller;
        _settings = settings;
        _onQuit = onQuit;

        for (int i = 0; i < IdleBarCount; i++)
        {
            IdleBars.Children.Add(new Rectangle
            {
                Width = 3,
                Height = 3 + (i % 3) * 2,
                RadiusX = 1.5,
                RadiusY = 1.5,
                Margin = new Thickness(1.5, 0, 1.5, 0),
                Fill = new SolidColorBrush(Color.FromRgb(0x6E, 0x6E, 0x7A)),
                VerticalAlignment = VerticalAlignment.Center,
            });
        }

        for (int i = 0; i < WaveBarCount; i++)
        {
            _waveRects[i] = new Rectangle
            {
                Width = 3,
                Height = 3,
                RadiusX = 1.5,
                RadiusY = 1.5,
                Margin = new Thickness(1.5, 0, 1.5, 0),
                Fill = new SolidColorBrush(Color.FromRgb(0xEC, 0xEC, 0xF1)),
                VerticalAlignment = VerticalAlignment.Center,
            };
            WaveBars.Children.Add(_waveRects[i]);
        }

        _controller.StateChanged += OnStateChanged;
        _controller.AudioLevel += OnAudioLevel;
        _controller.Notify += OnNotify;

        // Only runs while the bar is animating (recording/processing/flash) — an idle
        // tray app shouldn't wake the UI thread 20×/second.
        _animTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(50) };
        _animTimer.Tick += (_, _) => Animate();

        SourceInitialized += (_, _) => MakeNonActivating();
        SizeChanged += OnSizeChanged;
        Loaded += (_, _) => SyncFromSettings();

        MouseEnter += (_, _) => OnHover(true);
        MouseLeave += (_, _) => OnHover(false);
    }

    private void MakeNonActivating()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        var style = NativeMethods.GetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE).ToInt64();
        style |= NativeMethods.WS_EX_NOACTIVATE | NativeMethods.WS_EX_TOOLWINDOW;
        NativeMethods.SetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE, new IntPtr(style));
    }

    /// <summary>Applies persisted position + minimized state; called on load and on re-show.</summary>
    public void SyncFromSettings()
    {
        var s = _settings.Current;
        if (s.FlowBarLeft is double lx && s.FlowBarTop is double ly)
        {
            _userPositioned = true;
            Left = lx;
            Top = ly;
        }
        ApplyMinimized(s.FlowBarMinimized, persist: false);
        OnStateChanged();
        RepositionOrClamp();
    }

    // ----- Positioning -----

    private void RepositionOrClamp()
    {
        if (_userPositioned) ClampToScreen();
        else PlaceBottomCenter();
    }

    /// <summary>
    /// Bottom-center of the monitor hosting the foreground window (not just the primary),
    /// falling back to the primary work area on any hiccup.
    /// </summary>
    private void PlaceBottomCenter()
    {
        try
        {
            var hwnd = NativeMethods.GetForegroundWindow();
            var work = WinFormsScreen.FromHandle(hwnd).WorkingArea; // device pixels
            var t = DeviceToDip();
            var topLeft = t.Transform(new Point(work.Left, work.Top));
            var bottomRight = t.Transform(new Point(work.Right, work.Bottom));
            Left = topLeft.X + (bottomRight.X - topLeft.X - ActualWidth) / 2;
            Top = bottomRight.Y - ActualHeight - 14;
        }
        catch
        {
            var area = SystemParameters.WorkArea;
            Left = area.Left + (area.Width - ActualWidth) / 2;
            Top = area.Bottom - ActualHeight - 14;
        }
    }

    /// <summary>Keeps the whole bar within the work area of the monitor it currently sits on.</summary>
    private void ClampToScreen()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            var work = WinFormsScreen.FromHandle(hwnd).WorkingArea;
            var t = DeviceToDip();
            var topLeft = t.Transform(new Point(work.Left, work.Top));
            var bottomRight = t.Transform(new Point(work.Right, work.Bottom));
            double maxX = bottomRight.X - ActualWidth;
            double maxY = bottomRight.Y - ActualHeight;
            if (maxX >= topLeft.X) Left = Math.Max(topLeft.X, Math.Min(Left, maxX));
            if (maxY >= topLeft.Y) Top = Math.Max(topLeft.Y, Math.Min(Top, maxY));
        }
        catch
        {
            // Leave the position as-is if the monitor geometry can't be resolved.
        }
    }

    private Matrix DeviceToDip() =>
        PresentationSource.FromVisual(this)?.CompositionTarget?.TransformFromDevice ?? Matrix.Identity;

    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_userPositioned)
        {
            // Keep the visual center fixed as the content grows/shrinks (hover, state, minimize).
            if (e.PreviousSize.Width > 0)
                Left += (e.PreviousSize.Width - e.NewSize.Width) / 2;
            ClampToScreen();
        }
        else
        {
            PlaceBottomCenter();
        }
    }

    // ----- Drag vs click -----

    private void Pill_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (IsInteractive(e.OriginalSource)) return; // let the control buttons handle their own clicks
        _pressed = true;
        _dragging = false;
        _grabPoint = e.GetPosition(this);
        Pill.CaptureMouse();
    }

    private void Pill_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_pressed) return;
        var p = e.GetPosition(this);
        double dx = p.X - _grabPoint.X;
        double dy = p.Y - _grabPoint.Y;

        if (!_dragging && (Math.Abs(dx) > DragThreshold || Math.Abs(dy) > DragThreshold))
        {
            _dragging = true;
            Mouse.OverrideCursor = Cursors.SizeAll;
        }

        if (_dragging)
        {
            // Moving the window shifts its frame, so the grab point returns under the
            // cursor and the next delta stays small — self-correcting, DPI-safe.
            Left += dx;
            Top += dy;
        }
    }

    private void Pill_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_pressed) return;
        _pressed = false;
        Pill.ReleaseMouseCapture();
        Mouse.OverrideCursor = null;

        if (_dragging)
        {
            _dragging = false;
            _userPositioned = true;
            ClampToScreen();
            _settings.Update(s => { s.FlowBarLeft = Left; s.FlowBarTop = Top; }, notify: false);
        }
        else
        {
            OnPlainClick();
        }
    }

    private void OnPlainClick()
    {
        if (_minimized)
        {
            ApplyMinimized(false, persist: true);
            return;
        }
        if (_controller.State != FlowState.Processing)
            _controller.ToggleHandsFree();
    }

    /// <summary>True when the click landed on one of the pill's buttons, not the pill body.</summary>
    private static bool IsInteractive(object? source)
    {
        var d = source as DependencyObject;
        while (d != null)
        {
            if (d is System.Windows.Controls.Primitives.ButtonBase) return true;
            d = d is Visual or System.Windows.Media.Media3D.Visual3D
                ? VisualTreeHelper.GetParent(d)
                : (d as FrameworkElement)?.Parent;
        }
        return false;
    }

    // ----- Minimize / close -----

    private void Minimize_Click(object sender, RoutedEventArgs e) => ApplyMinimized(true, persist: true);

    private void Close_Click(object sender, RoutedEventArgs e) => _onQuit();

    private void ApplyMinimized(bool minimized, bool persist)
    {
        _minimized = minimized;
        NormalView.Visibility = minimized ? Visibility.Collapsed : Visibility.Visible;
        MinimizedView.Visibility = minimized ? Visibility.Visible : Visibility.Collapsed;
        Pill.Padding = minimized ? MinimizedPadding : NormalPadding;
        if (minimized) CollapseControlsInstant();

        if (persist)
            _settings.Update(s => s.FlowBarMinimized = minimized, notify: false);

        if (minimized) UpdateMinimizedVisual();
        else OnStateChanged();

        SyncAnimTimer();
        UpdateLayout();
        RepositionOrClamp();
    }

    // ----- Hover expand/collapse animation -----

    /// <summary>Smoothly grows the pill to reveal the minimize/close controls (or collapses them).</summary>
    private void AnimateControls(bool reveal)
    {
        if (_controlsWidth <= 0)
        {
            // Measure the controls' natural width once, unconstrained by the reveal clip.
            ControlCluster.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            _controlsWidth = ControlCluster.DesiredSize.Width;
        }

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var widthAnim = new DoubleAnimation(reveal ? _controlsWidth : 0, RevealDuration) { EasingFunction = ease };
        var fadeAnim = new DoubleAnimation(reveal ? 1 : 0, RevealDuration) { EasingFunction = ease };

        // Buttons only accept clicks once (almost) revealed; disable during collapse.
        ControlReveal.IsHitTestVisible = reveal;
        ControlReveal.BeginAnimation(WidthProperty, widthAnim);
        ControlReveal.BeginAnimation(OpacityProperty, fadeAnim);
    }

    /// <summary>Immediately hide the controls with no animation (used when minimizing).</summary>
    private void CollapseControlsInstant()
    {
        ControlReveal.BeginAnimation(WidthProperty, null);
        ControlReveal.BeginAnimation(OpacityProperty, null);
        ControlReveal.Width = 0;
        ControlReveal.Opacity = 0;
        ControlReveal.IsHitTestVisible = false;
    }

    private void UpdateMinimizedVisual()
    {
        MiniRecDot.Visibility = _controller.State == FlowState.Recording
            ? Visibility.Visible : Visibility.Collapsed;
        var micColor = _controller.State switch
        {
            FlowState.Recording => Color.FromRgb(0xE5, 0x48, 0x4D),
            FlowState.Processing => Color.FromRgb(0x8B, 0x5C, 0xF6),
            _ => Color.FromRgb(0xEC, 0xEC, 0xF1),
        };
        MiniMic.Fill = MiniMicCradle.Fill = new SolidColorBrush(micColor);
    }

    private void OnHover(bool over)
    {
        if (_minimized) return; // the handle has no hover controls
        AnimateControls(over);
    }

    // ----- State machine → visuals -----

    private void OnStateChanged()
    {
        if (_minimized)
        {
            UpdateMinimizedVisual();
            SyncAnimTimer();
            return;
        }

        switch (_controller.State)
        {
            case FlowState.Recording:
                if (_recordingStarted == DateTime.MinValue || RecordingView.Visibility != Visibility.Visible)
                    _recordingStarted = DateTime.UtcNow;
                ShowOnly(RecordingView);
                ModeChip.Visibility = _controller.Mode == DictationMode.Command
                    ? Visibility.Visible : Visibility.Collapsed;
                ModeChipText.Text = _controller.Mode switch
                {
                    DictationMode.Command => "Command",
                    _ => "",
                };
                break;
            case FlowState.Processing:
                ShowOnly(ProcessingView);
                _recordingStarted = DateTime.MinValue;
                break;
            default:
                _recordingStarted = DateTime.MinValue;
                if (DateTime.UtcNow < _flashUntil) return; // let the flash finish
                ShowOnly(IdleView);
                break;
        }
        SyncAnimTimer();
        UpdateLayout();
        RepositionOrClamp();
    }

    private void SyncAnimTimer()
    {
        bool animating = !_minimized &&
                      (RecordingView.Visibility == Visibility.Visible
                    || ProcessingView.Visibility == Visibility.Visible
                    || FlashView.Visibility == Visibility.Visible);
        if (animating && !_animTimer.IsEnabled) _animTimer.Start();
        else if (!animating && _animTimer.IsEnabled) _animTimer.Stop();
    }

    private void ShowOnly(UIElement element)
    {
        IdleView.Visibility = ReferenceEquals(element, IdleView) ? Visibility.Visible : Visibility.Collapsed;
        RecordingView.Visibility = ReferenceEquals(element, RecordingView) ? Visibility.Visible : Visibility.Collapsed;
        ProcessingView.Visibility = ReferenceEquals(element, ProcessingView) ? Visibility.Visible : Visibility.Collapsed;
        FlashView.Visibility = ReferenceEquals(element, FlashView) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnAudioLevel(float level)
    {
        _levels.Enqueue(level);
        while (_levels.Count > WaveBarCount) _levels.Dequeue();
    }

    private void OnNotify(string message)
    {
        _flashUntil = DateTime.UtcNow.AddSeconds(2.6);
        if (_minimized)
        {
            // Don't expand the handle for transient flashes; the tooltip carries the message.
            MinimizedView.ToolTip = message;
            return;
        }
        FlashView.Text = message;
        ShowOnly(FlashView);
        SyncAnimTimer();
        UpdateLayout();
        RepositionOrClamp();
    }

    private void Animate()
    {
        var now = DateTime.UtcNow;

        // Flash expiry returns the bar to its state-appropriate view.
        if (FlashView.Visibility == Visibility.Visible && now >= _flashUntil)
            OnStateChanged();

        if (RecordingView.Visibility == Visibility.Visible)
        {
            var levels = _levels.ToArray();
            for (int i = 0; i < WaveBarCount; i++)
            {
                float level = i < levels.Length ? levels[i] : 0f;
                _waveRects[i].Height = 3 + level * 19;
            }
            if (_recordingStarted != DateTime.MinValue)
            {
                var elapsed = now - _recordingStarted;
                ElapsedText.Text = $"{(int)elapsed.TotalMinutes}:{elapsed.Seconds:00}";
            }
        }
        else if (ProcessingView.Visibility == Visibility.Visible)
        {
            _phase += 0.28;
            Dot1.Opacity = 0.35 + 0.65 * (0.5 + 0.5 * Math.Sin(_phase));
            Dot2.Opacity = 0.35 + 0.65 * (0.5 + 0.5 * Math.Sin(_phase - 0.9));
            Dot3.Opacity = 0.35 + 0.65 * (0.5 + 0.5 * Math.Sin(_phase - 1.8));
        }
    }
}
