using Microsoft.UI;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml.Hosting;
using System.Numerics;
using Microsoft.Win32;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Graphics;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using Windows.UI;
using Windows.UI.ViewManagement;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Runtime.InteropServices;
using System.Collections.ObjectModel;

namespace TunesLinkBridge;

public sealed partial class MainWindow : Microsoft.UI.Xaml.Window, IDisposable
{

    private const int DefaultClientWidthDip = 456;
    // ResizeClient excludes the caption strip that content extends into (~29 DIP), so the
    // visible XAML root ends up ~765 DIP: the one-phone state fits; taller states scroll.
    private const int DefaultClientHeightDip = 752;
    private const int MinimumClientWidthDip = 420;
    private const int MinimumClientHeightDip = 560;
    private const int WorkAreaInsetPixels = 24;
    private const int WindowStyleIndex = -16;
    private const long MaximizeBoxStyle = 0x00010000L;
    private const uint FrameStyleChangedFlags = 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020;

    private readonly BridgeRuntime? runtime;
    private readonly BridgeLaunchOptions launch;
    private readonly TrayService tray;
    private readonly DispatcherQueueTimer pairTimer;
    private readonly DispatcherQueueTimer statusTimer;
    private readonly DispatcherQueueTimer relativeTimer;
    private readonly UISettings uiSettings = new();
    private readonly AccessibilitySettings accessibilitySettings = new();
    private readonly BridgeHealthState healthState = new();
    private readonly CopyFeedbackCoordinator copyFeedback = new();
    private readonly ObservableCollection<PairedPhonePresentation> pairedPhones = new();
    private DispatcherQueueTimer? verificationExitTimer;
    private bool restoringPreferences;
    private bool explicitExit;
    private bool statusRefreshRunning;
    private bool animationsEnabled = true;
    private bool opacityFeedbackEnabled = true;
    private int feedbackDurationMs = MotionTokens.StatusCrossfadeMs;
    private bool pairingExpanded;
    private bool uiActivityActive;
    private bool suppressVisibilityLifecycle;
    private bool systemEventsSubscribed;
    private bool advancedEffectsSubscribed;
    private bool highContrastSubscribed;
    private bool ringPulseRunning;
    private bool ringPulseAllowedByPolicy;
    private bool heroReady;
    private bool heroRasterActive;
    private SpriteVisual? backdropWave;
    private readonly List<CompositionColorGradientStop> backdropWaveRingStops = new();
    private bool disposed;
    private string? copyableAddress;
    private Microsoft.UI.Xaml.Media.Animation.TransitionCollection? contentReflowTransitions;
    private SecurityChangeCause pendingSecurityChange;
    private string? pendingSecurityDeviceName;
    private int lastKnownDeviceCount;
    private SizeInt32 requestedClientSizeDip = new(DefaultClientWidthDip, DefaultClientHeightDip);

    internal MainWindow(BridgeRuntime? runtime, BridgeLaunchOptions launch,
        SingleInstanceCoordinator? singleton)
    {
        this.runtime = runtime;
        this.launch = launch;
        InitializeComponent();
        Title = "TunesLink Bridge";
        DevicesItems.ItemsSource = pairedPhones;
        contentReflowTransitions = ContentStack.ChildrenTransitions;
        LoadBrandImages();
        ConfigureWindow();
        tray = new TrayService(ShowFromExternalInstance, ExitApplication);

        pairTimer = DispatcherQueue.CreateTimer();
        pairTimer.Interval = TimeSpan.FromSeconds(1);
        pairTimer.Tick += (_, _) => RefreshPairing();
        statusTimer = DispatcherQueue.CreateTimer();
        statusTimer.Interval = TimeSpan.FromSeconds(3);
        statusTimer.Tick += async (_, _) => await RefreshStatusAsync();
        relativeTimer = DispatcherQueue.CreateTimer();
        relativeTimer.Interval = TimeSpan.FromMinutes(1);
        relativeTimer.Tick += (_, _) => RefreshDevices();

        if (runtime is not null)
        {
            runtime.Security.Changed += SecurityChanged;
            runtime.AddressSelector.Changed += AddressChanged;
            restoringPreferences = true;
            KeepRunningToggle.IsOn = runtime.Preferences.KeepRunningOnClose;
            try
            {
                StartupRegistration.RepairEnabledPath();
                OpenAtLoginToggle.IsOn = StartupRegistration.IsEnabled();
            }
            catch (Exception exception)
            {
                BridgeDiagnostics.Record("startup.read", exception);
            }
            restoringPreferences = false;
            RefreshAll();
            if (launch.UiState is "itunes-error" or "both-errors")
                SetProblem(new BridgeProblem(BridgeProblemKind.ITunesUnavailable,
                    UiStrings.Get("ItunesUnavailableTitle", "iTunes is unavailable"),
                    UiStrings.Get("ItunesUnavailableDetail", "Open iTunes on this PC to begin playback.")));
        }
        else
        {
            SetHeroText(HeroPresentation.Create(0, false));
            PairCodeText.Text = "— — —";
            AddressText.Text = UiStrings.Get("Unavailable", "Unavailable");
            CopyCodeButton.IsEnabled = false;
            CopyAddressButton.IsEnabled = false;
            PairedDevicesSection.Visibility = Visibility.Collapsed;
            PhoneCheckMark.Visibility = Visibility.Collapsed;
            BackdropRings.Visibility = Visibility.Collapsed;
            SetStatusChip(NetworkStatusIndicator, NetworkStatusText, healthy: false,
                UiStrings.Get("BridgeNotRunning", "Bridge not running"));
            SetStatusChip(ItunesStatusIndicator, ItunesStatusText, healthy: false,
                UiStrings.Get("BridgeNotRunning", "Bridge not running"));
        }
        ApplyRuntimeAvailability();

        singleton?.Listen(ShowFromExternalInstance);
        Closed += Window_Closed;
        AppWindow.Closing += AppWindow_Closing;
        AppWindow.Changed += AppWindow_Changed;
        RootGrid.ActualThemeChanged += (_, _) =>
        {
            UpdateHeroArtwork();
            ApplySystemPresentationSettings();
            UpdatePhoneShadowOpacity();
            UpdateBackdropWaveColor();
        };
        RootGrid.Loaded += RootGrid_Loaded;
        RootGrid.SizeChanged += (_, sizeArgs) =>
        {
            UpdateStatusChipOrientation(sizeArgs.NewSize.Width);
            Point ringCenter = new(sizeArgs.NewSize.Width / 2, 112);
            BackdropRingsBrush.Center = ringCenter;
            BackdropRingsBrush.GradientOrigin = ringCenter;
        };
        try
        {
            SystemEvents.UserPreferenceChanged += SystemPreferenceChanged;
            systemEventsSubscribed = true;
        }
        catch { }
        try
        {
            uiSettings.AdvancedEffectsEnabledChanged += PresentationSettingsChanged;
            advancedEffectsSubscribed = true;
        }
        catch (COMException) { }
        try
        {
            accessibilitySettings.HighContrastChanged += AccessibilitySettingsChanged;
            highContrastSubscribed = true;
        }
        catch (COMException) { }
        Activated += (_, args) =>
        {
            if (args.WindowActivationState != WindowActivationState.Deactivated)
            {
                ApplySystemPresentationSettings();
                UpdateUiActivity();
            }
        };
    }

    internal void InitializeHidden()
    {
        suppressVisibilityLifecycle = true;
        try
        {
            AppWindow.Show();
            AppWindow.Hide();
        }
        finally
        {
            suppressVisibilityLifecycle = false;
            StopUiActivity();
        }
    }

    internal async Task ShowStartupFailureAsync(string message)
    {
        await ShowDialogAsync(
            UiStrings.Get("StartupFailureTitle", "TunesLink couldn’t start"),
            message,
            UiStrings.Get("Close", "Close"),
            null,
            destructive: false);
    }

    private void LoadBrandImages()
    {
        // Single-file publishes extract Assets beside AppContext.BaseDirectory, not the exe,
        // so relative XAML image URIs would silently resolve to nothing.
        try
        {
            string assets = Path.Combine(AppContext.BaseDirectory, "Assets");
            string icon = Path.Combine(assets, "tunelink-app-icon-256.png");
            TitleBarIcon.Source = new BitmapImage(new Uri(icon));
            HeroBadgeImage.Source = new BitmapImage(new Uri(icon));
            // Device frames: MockUPhone (mockuphone.com), CC BY 3.0.
            LaptopFrameImage.Source = new BitmapImage(new Uri(Path.Combine(assets, "device-xps15.png")));
            PhoneFrameImage.Source = new BitmapImage(new Uri(Path.Combine(assets, "device-galaxy-s24-ultra.png")));
            // Pre-blurred silhouette of the phone frame (generated from its alpha channel).
            PhoneShadowImage.Source = new BitmapImage(new Uri(Path.Combine(assets, "device-galaxy-s24-ultra-shadow.png")));
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("ui.brand-images", exception);
        }
        UpdateHeroArtwork();
        UpdatePhoneShadowOpacity();
    }

    // The wave reaches well past the cards (the host spans the whole page) and each ring
    // fades out over its last stretch, so nothing ever meets a hard edge or pops on wrap.
    private const float BackdropWaveRadius = 540f;
    private const float BackdropWaveCenterY = 112f;
    private const double BackdropWaveCycleSeconds = 10.0;
    private const float BackdropWaveFadeStart = 0.55f;

    private void EnsureBackdropWave()
    {
        // The signal wave: four rings marching outward from the devices, animated entirely on
        // the compositor so it runs smoothly regardless of UI-thread work.
        if (backdropWave is not null) return;
        try
        {
            Visual hostVisual = ElementCompositionPreview.GetElementVisual(BackdropWaveHost);
            Compositor compositor = hostVisual.Compositor;
            CompositionRadialGradientBrush brush = compositor.CreateRadialGradientBrush();
            brush.MappingMode = CompositionMappingMode.Absolute;
            brush.EllipseRadius = new Vector2(BackdropWaveRadius, BackdropWaveRadius);
            brush.ColorStops.Add(compositor.CreateColorGradientStop(0f, Colors.Transparent));
            const int rings = 4;
            const float ringHalfWidth = 0.0042f;
            const double cycleSeconds = BackdropWaveCycleSeconds;
            CompositionEasingFunction linear = compositor.CreateLinearEasingFunction();
            Color ringColor = (Color)Microsoft.UI.Xaml.Application.Current.Resources["RingLineColor"];
            Color faded = Color.FromArgb(0, ringColor.R, ringColor.G, ringColor.B);
            for (int ring = 0; ring < rings; ring++)
            {
                CompositionColorGradientStop leading = compositor.CreateColorGradientStop(0f, Colors.Transparent);
                CompositionColorGradientStop line = compositor.CreateColorGradientStop(0f, ringColor);
                CompositionColorGradientStop trailing = compositor.CreateColorGradientStop(0f, Colors.Transparent);
                brush.ColorStops.Add(leading);
                brush.ColorStops.Add(line);
                brush.ColorStops.Add(trailing);
                backdropWaveRingStops.Add(line);
                TimeSpan delay = TimeSpan.FromSeconds(cycleSeconds * ring / rings);
                StartStopAnimation(compositor, leading, -ringHalfWidth, 1f - ringHalfWidth, cycleSeconds, delay, linear);
                StartStopAnimation(compositor, line, 0f, 1f, cycleSeconds, delay, linear);
                StartStopAnimation(compositor, trailing, ringHalfWidth, 1f + ringHalfWidth, cycleSeconds, delay, linear);
                ColorKeyFrameAnimation fade = compositor.CreateColorKeyFrameAnimation();
                fade.InsertKeyFrame(0f, ringColor, linear);
                fade.InsertKeyFrame(BackdropWaveFadeStart, ringColor, linear);
                fade.InsertKeyFrame(1f, faded, linear);
                fade.Duration = TimeSpan.FromSeconds(cycleSeconds);
                fade.DelayTime = delay;
                fade.DelayBehavior = AnimationDelayBehavior.SetInitialValueBeforeDelay;
                fade.IterationBehavior = AnimationIterationBehavior.Forever;
                line.StartAnimation("Color", fade);
            }
            SpriteVisual visual = compositor.CreateSpriteVisual();
            visual.Brush = brush;
            ElementCompositionPreview.SetElementChildVisual(BackdropWaveHost, visual);
            backdropWave = visual;
            BackdropWaveHost.SizeChanged += (_, _) => SyncBackdropWaveSize();
            SyncBackdropWaveSize();
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("ui.backdrop-wave", exception);
        }
    }

    private static void StartStopAnimation(Compositor compositor, CompositionColorGradientStop stop,
        float from, float to, double seconds, TimeSpan delay, CompositionEasingFunction easing)
    {
        ScalarKeyFrameAnimation animation = compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(0f, from, easing);
        animation.InsertKeyFrame(1f, to, easing);
        animation.Duration = TimeSpan.FromSeconds(seconds);
        animation.DelayTime = delay;
        animation.DelayBehavior = AnimationDelayBehavior.SetInitialValueBeforeDelay;
        animation.IterationBehavior = AnimationIterationBehavior.Forever;
        stop.StartAnimation("Offset", animation);
    }

    private void SyncBackdropWaveSize()
    {
        if (backdropWave is null) return;
        float width = (float)BackdropWaveHost.ActualWidth;
        float height = (float)BackdropWaveHost.ActualHeight;
        backdropWave.Size = new Vector2(width, height);
        if (backdropWave.Brush is CompositionRadialGradientBrush brush)
            brush.EllipseCenter = new Vector2(width / 2, BackdropWaveCenterY);
    }

    private void UpdateBackdropWaveColor()
    {
        // Ring colors are driven by running compositor animations, so a theme change rebuilds
        // the wave rather than poking the animated values.
        if (backdropWave is null) return;
        bool visible = backdropWave.IsVisible;
        ElementCompositionPreview.SetElementChildVisual(BackdropWaveHost, null);
        backdropWave.Dispose();
        backdropWave = null;
        backdropWaveRingStops.Clear();
        EnsureBackdropWave();
        if (backdropWave is not null) backdropWave.IsVisible = visible;
    }

    private void UpdatePhoneShadowOpacity() =>
        PhoneShadowImage.Opacity = RootGrid.ActualTheme == ElementTheme.Light ? 0.42 : 0.75;

    private void UpdateHeroArtwork()
    {
        // Optional pre-rendered hero artwork replaces the vector illustration when present.
        string name = RootGrid.ActualTheme == ElementTheme.Light ? "hero-light.png" : "hero-dark.png";
        string path = Path.Combine(AppContext.BaseDirectory, "Assets", name);
        bool available = File.Exists(path);
        if (available)
        {
            try
            {
                HeroRasterImage.Source = new BitmapImage(new Uri(path));
            }
            catch (Exception exception)
            {
                BridgeDiagnostics.Record("ui.hero-art", exception);
                available = false;
            }
        }
        heroRasterActive = available;
        HeroRasterImage.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
        if (available) PhoneCheckMark.Visibility = Visibility.Collapsed;
    }
}
