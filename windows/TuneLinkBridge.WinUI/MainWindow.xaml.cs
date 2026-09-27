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
    private readonly DispatcherQueue uiQueue;
    private readonly TrayService? tray;
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
    private readonly PairingDisclosure pairingDisclosure = new();
    private readonly TaskCompletionSource startupDialogOpened =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
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
    // Written and read on the UI thread only: the cause of a change the UI itself is making.
    private SecurityChangeCause pendingSecurityChange;
    private string? pendingSecurityDeviceName;
    private SecuritySnapshot shownSecurity;
    private SizeInt32 requestedClientSizeDip = new(DefaultClientWidthDip, DefaultClientHeightDip);

    internal MainWindow(BridgeRuntime? runtime, BridgeLaunchOptions launch)
    {
        this.runtime = runtime;
        this.launch = launch;
        InitializeComponent();
        uiQueue = DispatcherQueue;
        Title = UiStrings.Get("AppDisplayName", "TunesLink Bridge");
        ToolTipService.SetToolTip(SettingsButton, UiStrings.Get("SettingsToolTip", "Settings"));
        DevicesItems.ItemsSource = pairedPhones;
        contentReflowTransitions = ContentStack.ChildrenTransitions;
        BackdropWaveHost.SizeChanged += (_, _) => SyncBackdropWaveSize();
        LoadBrandImages();
        ConfigureWindow();
        // Isolated previews must not leave a second icon in the user's notification area.
        tray = launch.IsolatedPreview ? null : new TrayService(ShowFromExternalInstance, ExitApplication);

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
            restoringPreferences = false;
            if (!launch.Demo)
            {
                try { StartupRegistration.RepairEnabledPath(); }
                catch (Exception exception)
                {
                    BridgeDiagnostics.Record("startup.repair", exception);
                }
            }
            ReadOpenAtLogin();
            RefreshAll();
            shownSecurity = CaptureSecuritySnapshot(runtime);
            if (launch.UiState is "itunes-error" or "both-errors")
                SetProblem(new BridgeProblem(BridgeProblemKind.ITunesUnavailable,
                    UiStrings.Get("ItunesUnavailableTitle", "iTunes is unavailable"),
                    UiStrings.Get("ItunesUnavailableDetail", "Open iTunes on this PC to begin playback.")));
        }
        else
        {
            SetHeroText(new HeroPresentation(HeroMode.PairFirstPhone,
                UiStrings.Get("HeroUnavailableTitle", "Bridge unavailable."),
                UiStrings.Get("HeroUnavailableDetail", "Restart TunesLink Bridge to restore pairing and playback controls."), false));
            PairCodeText.Text = UiStrings.Get("PairingCodeUnavailable", "— — —");
            AutomationProperties.SetName(PairCodeText,
                UiStrings.Get("PairingCodeUnavailableAccessibleName", "Pairing code unavailable"));
            // No code exists without the bridge: nothing may count down or invite entry.
            PairExpiryText.Visibility = Visibility.Collapsed;
            PairCodeActions.Visibility = Visibility.Collapsed;
            CodeDetailText.Visibility = Visibility.Collapsed;
            AddressText.Text = UiStrings.Get("Unavailable", "Unavailable");
            CopyCodeButton.IsEnabled = false;
            CopyAddressButton.IsEnabled = false;
            PairedDevicesSection.Visibility = Visibility.Collapsed;
            PhoneCheckMark.Visibility = Visibility.Collapsed;
            BackdropRings.Visibility = Visibility.Collapsed;
            SetStatusChip(NetworkStatusIndicator, NetworkStatusText, healthy: false,
                UiStrings.Get("BridgeNotRunning", "Bridge not running"));
            ItunesStatusChip.Visibility = Visibility.Collapsed;
        }
        ApplyRuntimeAvailability();

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
            uiSettings.TextScaleFactorChanged += PresentationSettingsChanged;
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
                return;
            }
            // Minimizing deactivates before the window reports its new state; check again once
            // the minimize has settled.
            UpdateUiActivity();
            uiQueue.TryEnqueue(DispatcherQueuePriority.Low, UpdateUiActivity);
        };
        VisibilityChanged += (_, _) => UpdateUiActivity();
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

    /// <summary>Explains a failed start once the window can host a dialog; true means Retry.</summary>
    internal async Task<bool> ShowStartupFailureAsync(Exception exception, int port)
    {
        await WhenRootLoadedAsync();
        string detail = StartupFailurePresentation.Detail(
            StartupFailurePresentation.Classify(exception), port);
        ContentDialogResult result = await ShowDialogAsync(
            UiStrings.Get("StartupFailureTitle", "TunesLink couldn’t start"),
            detail,
            UiStrings.Get("TryAgain", "Try again"),
            UiStrings.Get("Close", "Close"),
            destructive: false,
            initialFocusOnPrimary: true,
            opened: () => startupDialogOpened.TrySetResult());
        return result == ContentDialogResult.Primary;
    }

    private Task WhenRootLoadedAsync()
    {
        if (RootGrid.IsLoaded && RootGrid.XamlRoot is not null) return Task.CompletedTask;
        TaskCompletionSource loaded = new(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnLoaded(object sender, RoutedEventArgs args)
        {
            RootGrid.Loaded -= OnLoaded;
            loaded.TrySetResult();
        }
        RootGrid.Loaded += OnLoaded;
        return loaded.Task;
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
        RemoveBackdropWave();
        ApplyRingPulsePolicy();
    }

    private void RemoveBackdropWave()
    {
        // Tearing the visual down (rather than hiding it) stops its endless compositor
        // animations while the window is hidden or minimized.
        if (backdropWave is null) return;
        ElementCompositionPreview.SetElementChildVisual(BackdropWaveHost, null);
        backdropWave.Dispose();
        backdropWave = null;
        backdropWaveRingStops.Clear();
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
