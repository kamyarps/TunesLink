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

    private const int DefaultClientWidthDip = 480;
    private const int DefaultClientHeightDip = 580;
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
    private int problemPresentationGeneration;
    private CancellationTokenSource? problemPresentationCancellation;
    private bool disposed;
    private string? copyableAddress;
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
        DevicesItems.ItemsSource = pairedPhones;
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
            PairCodeText.Text = "— — —";
            AddressText.Text = UiStrings.Get("Unavailable", "Unavailable");
            CopyCodeButton.IsEnabled = false;
            CopyAddressButton.IsEnabled = false;
        }
        ApplyRuntimeAvailability();

        singleton?.Listen(ShowFromExternalInstance);
        Closed += Window_Closed;
        AppWindow.Closing += AppWindow_Closing;
        AppWindow.Changed += AppWindow_Changed;
        RootGrid.ActualThemeChanged += (_, _) => ApplyTitleBarColors();
        RootGrid.Loaded += RootGrid_Loaded;
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
}
