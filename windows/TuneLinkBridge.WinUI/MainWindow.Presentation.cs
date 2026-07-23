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

public sealed partial class MainWindow
{
    private void ConfigureWindow()
    {
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.Title = UiStrings.Get("AppDisplayName", "TunesLink Bridge");
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "tunelink.ico"));
        requestedClientSizeDip = launch.VerifyLayout || launch.SnapshotPath is not null
            ? ParseViewport(launch.Viewport)
                ?? new SizeInt32(DefaultClientWidthDip, DefaultClientHeightDip)
            : new SizeInt32(DefaultClientWidthDip, DefaultClientHeightDip);
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsMaximizable = false;
            presenter.IsResizable = false;
        }
        HideNativeMaximizeButton();
        ResizeWindowToRequestedSize();

        ApplySystemPresentationSettings();
        ApplyTitleBarColors();
    }

    private void PresentationSettingsChanged(UISettings sender, object args) =>
        DispatcherQueue.TryEnqueue(ApplySystemPresentationSettings);

    private void AccessibilitySettingsChanged(AccessibilitySettings sender, object args) =>
        DispatcherQueue.TryEnqueue(ApplySystemPresentationSettings);

    private void SystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs args) =>
        DispatcherQueue.TryEnqueue(ApplySystemPresentationSettings);

    private void ApplySystemPresentationSettings()
    {
        MotionPolicy policy = new(uiSettings.AnimationsEnabled,
            uiSettings.AdvancedEffectsEnabled, accessibilitySettings.HighContrast);
        animationsEnabled = policy.AnimationsEnabled;
        opacityFeedbackEnabled = policy.OpacityFeedbackEnabled;
        feedbackDurationMs = policy.FeedbackDurationMs;
        RootGrid.Background = policy.MaterialsEnabled
            ? new SolidColorBrush(Colors.Transparent)
            : (Brush)Microsoft.UI.Xaml.Application.Current.Resources["CanvasBrush"];
        if (policy.MaterialsEnabled)
        {
            if (SystemBackdrop is null)
            {
                try { SystemBackdrop = new MicaBackdrop(); }
                catch { SystemBackdrop = new DesktopAcrylicBackdrop(); }
            }
        }
        else
        {
            SystemBackdrop = null;
        }
        ApplyTitleBarColors();
    }

    private async void RootGrid_Loaded(object sender, RoutedEventArgs eventArgs)
    {
        if (!launch.VerifyLayout && launch.SnapshotPath is null) return;
        ApplyVerificationTextScale();
        RenderProblemsImmediately();
        RootGrid.UpdateLayout();
        if (AppWindow.Presenter is not OverlappedPresenter presenter
            || presenter.IsResizable
            || presenter.IsMaximizable
            || HasNativeMaximizeButton())
            throw new InvalidOperationException("TunesLink WinUI fixed-window verification failed.");
        if (RootGrid.ActualWidth <= 0 || HeroTitle.ActualHeight <= 0
            || (PairedDevicesSection.Visibility == Visibility.Visible
                && PairedDevicesSection.ActualWidth <= 0)
            || (PairingPanel.Visibility == Visibility.Visible && PairCodeText.ActualHeight <= 0))
            throw new InvalidOperationException("TunesLink WinUI layout verification failed.");
        if (RootGrid.ActualWidth < MinimumClientWidthDip - 1
            || RootGrid.ActualHeight < MinimumClientHeightDip - 1)
            throw new InvalidOperationException("TunesLink WinUI minimum viewport verification failed.");
        if (runtime is null && (PairAnotherButton.IsEnabled || CopyCodeButton.IsEnabled
            || CopyAddressButton.IsEnabled
            || DevicesItems.IsEnabled || KeepRunningToggle.IsEnabled || OpenAtLoginToggle.IsEnabled))
            throw new InvalidOperationException("TunesLink WinUI unavailable-state verification failed.");
        VerifyVisibleBoundsAndTargets();
        if (launch.SnapshotPath is not null) await CaptureSnapshotAsync(launch.SnapshotPath);
        Environment.ExitCode = 0;
        RootGrid.Loaded -= RootGrid_Loaded;
        verificationExitTimer = DispatcherQueue.CreateTimer();
        verificationExitTimer.Interval = TimeSpan.FromMilliseconds(100);
        verificationExitTimer.IsRepeating = false;
        verificationExitTimer.Tick += (_, _) =>
        {
            verificationExitTimer?.Stop();
            explicitExit = true;
            Close();
        };
        verificationExitTimer.Start();
    }

    private void ApplyVerificationTextScale()
    {
        if (launch.TextScale == 1.0) return;
        foreach (DependencyObject descendant in Descendants(RootGrid))
        {
            if (descendant is TextBlock text)
                text.FontSize *= launch.TextScale;
        }
        RootGrid.UpdateLayout();
    }

    private void VerifyVisibleBoundsAndTargets()
    {
        foreach (FrameworkElement element in Descendants(RootGrid).OfType<FrameworkElement>())
        {
            if (element.Visibility != Visibility.Visible || element.Opacity <= 0
                || element.ActualWidth <= 0
                || element.ActualHeight <= 0) continue;
            Rect bounds = element.TransformToVisual(RootGrid).TransformBounds(
                new Rect(0, 0, element.ActualWidth, element.ActualHeight));
            if (bounds.Left < -1 || bounds.Right > RootGrid.ActualWidth + 1)
                throw new InvalidOperationException($"TunesLink WinUI horizontal overflow: {element.Name ?? element.GetType().Name}.");
            if (element is Button && element.ActualHeight < 44)
                throw new InvalidOperationException($"TunesLink WinUI target is shorter than 44 DIPs: {element.Name ?? "button"}.");
        }
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int index = 0; index < count; index++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(root, index);
            yield return child;
            foreach (DependencyObject descendant in Descendants(child)) yield return descendant;
        }
    }

    private async Task CaptureSnapshotAsync(string path)
    {
        RenderTargetBitmap render = new();
        await render.RenderAsync(RootGrid);
        IBuffer pixels = await render.GetPixelsAsync();
        string fullPath = Path.GetFullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)
            ?? throw new InvalidOperationException("Snapshot path has no directory."));
        await using FileStream file = File.Create(fullPath);
        using IRandomAccessStream stream = file.AsRandomAccessStream();
        BitmapEncoder encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetPixelData(
            BitmapPixelFormat.Bgra8,
            BitmapAlphaMode.Premultiplied,
            (uint)render.PixelWidth,
            (uint)render.PixelHeight,
            96,
            96,
            pixels.ToArray());
        await encoder.FlushAsync();
    }

    private void ApplyTitleBarColors()
    {
        Color canvas = (Color)Microsoft.UI.Xaml.Application.Current.Resources["CanvasColor"];
        Color text = (Color)Microsoft.UI.Xaml.Application.Current.Resources["PrimaryTextColor"];
        Color muted = (Color)Microsoft.UI.Xaml.Application.Current.Resources["SecondaryTextColor"];
        AppWindow.TitleBar.BackgroundColor = Colors.Transparent;
        AppWindow.TitleBar.ForegroundColor = text;
        AppWindow.TitleBar.ButtonBackgroundColor = Colors.Transparent;
        AppWindow.TitleBar.ButtonForegroundColor = text;
        AppWindow.TitleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        AppWindow.TitleBar.ButtonInactiveForegroundColor = muted;
        AppWindow.TitleBar.ButtonHoverBackgroundColor = Color.FromArgb(32, text.R, text.G, text.B);
        AppWindow.TitleBar.ButtonHoverForegroundColor = text;
        if (accessibilitySettings.HighContrast)
            AppWindow.TitleBar.BackgroundColor = canvas;
    }

    private void RefreshAll()
    {
        RefreshPairing();
        RefreshAddress();
        RefreshDevices();
    }

    private void ApplyRuntimeAvailability()
    {
        RuntimeAvailabilityPresentation availability = RuntimeAvailabilityPresentation.Create(
            runtime is not null, copyableAddress is not null,
            runtime?.Security.Devices.Count ?? 0);
        PairAnotherButton.IsEnabled = availability.CanPairAnotherPhone;
        NewCodeButton.IsEnabled = availability.CanRequestNewCode;
        CopyCodeButton.IsEnabled = availability.CanCopyPairingCode;
        CopyAddressButton.IsEnabled = availability.CanCopyAddress;
        DevicesItems.IsEnabled = availability.CanManageDevices;
        ForgetAllButton.IsEnabled = availability.CanManageDevices;
        KeepRunningToggle.IsEnabled = availability.CanChangeRuntimeSettings;
        OpenAtLoginToggle.IsEnabled = availability.CanChangeRuntimeSettings;
    }

    private bool TryGetRuntime(string operation, out BridgeRuntime availableRuntime)
    {
        if (runtime is not null)
        {
            availableRuntime = runtime;
            return true;
        }
        availableRuntime = null!;
        _ = operation;
        Announce(UiStrings.Get("RuntimeActionUnavailable",
                "This action is unavailable because TunesLink did not start."),
            AutomationNotificationKind.ActionAborted);
        return false;
    }

    private void RefreshPairing()
    {
        if (runtime is null) return;
        pendingSecurityChange = SecurityChangeCause.AutomaticPairCodeRotation;
        bool rotatedAutomatically = runtime.Security.EnsureCurrentPairCode();
        if (!rotatedAutomatically && pendingSecurityChange == SecurityChangeCause.AutomaticPairCodeRotation)
            pendingSecurityChange = SecurityChangeCause.InitialRefresh;
        string code = runtime.Security.PairCode;
        PairCodeText.Text = code.Length == 6 ? code[..3] + " " + code[3..] : code;
        AutomationProperties.SetName(PairCodeText,
            UiStrings.Format("PairingCodeAccessibleName", "Pairing code {0}", string.Join(' ', code)));
        TimeSpan remaining = runtime.Security.PairCodeExpiresAt - DateTimeOffset.UtcNow;
        int seconds = Math.Max(0, (int)Math.Ceiling(remaining.TotalSeconds));
        PairExpiryText.Text = UiStrings.Format("PairingCodeExpiry", "Expires in {0}:{1:D2}",
            seconds / 60, seconds % 60);
        AutomationProperties.SetName(PairExpiryText,
            UiStrings.Format("PairingCodeExpiryAccessibleName",
                "Pairing code expires in {0} minutes and {1} seconds",
                seconds / 60, seconds % 60));
    }

    private void RefreshAddress()
    {
        if (runtime is null) return;
        NetworkAddressSelection selection = runtime.AddressSelector.Current;
        bool forcedUnavailable = launch.UiState is "network-error" or "both-errors";
        if (selection.Address is null || forcedUnavailable)
        {
            copyableAddress = null;
            AddressText.Text = UiStrings.Get("Unavailable", "Unavailable");
            NetworkStatusText.Text = UiStrings.Get("PrivateAddressUnavailable", "Private address unavailable");
            NetworkStatusText.Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["DangerBrush"];
            NetworkStatusIndicator.Fill = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["DangerBrush"];
            SetProblem(new BridgeProblem(BridgeProblemKind.NetworkUnavailable,
                UiStrings.Get("PrivateAddressUnavailable", "Private address unavailable"),
                forcedUnavailable
                    ? UiStrings.Get("PrivateAddressUnavailableDetail", "Connect this PC to a private local network.")
                    : selection.Diagnostic));
        }
        else
        {
            copyableAddress = NetworkAddressPresentation.Format(
                selection.Address.ToString(),
                runtime.Options.Port);
            AddressText.Text = copyableAddress;
            NetworkStatusText.Text = UiStrings.Get("LocalNetworkReady", "Local network ready");
            NetworkStatusText.Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["SecondaryTextBrush"];
            NetworkStatusIndicator.Fill = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["SuccessBrush"];
            ClearProblem(BridgeProblemKind.NetworkUnavailable);
        }
        AutomationProperties.SetHelpText(AddressText, selection.Diagnostic);
        ApplyRuntimeAvailability();
    }

    private void RefreshDevices()
    {
        if (runtime is null) return;
        List<BridgeSecurity.PairedDevice> devices = runtime.Security.Devices
            .OrderByDescending(device => device.LastSeenAt).ToList();
        PairedDevicesSection.Visibility = devices.Count == 0
            ? Visibility.Collapsed : Visibility.Visible;
        HashSet<string> currentTokens = devices.Select(device => device.TokenHash).ToHashSet();
        for (int index = pairedPhones.Count - 1; index >= 0; index--)
            if (!currentTokens.Contains(pairedPhones[index].TokenHash)) pairedPhones.RemoveAt(index);
        for (int targetIndex = 0; targetIndex < devices.Count; targetIndex++)
        {
            BridgeSecurity.PairedDevice device = devices[targetIndex];
            string detail = UiStrings.Format("DevicePairingDetail", "Paired {0:d} · {1}",
                device.PairedAt.ToLocalTime(), RelativeLastUsed(device.LastSeenAt));
            PairedPhonePresentation? existing = pairedPhones.FirstOrDefault(item => item.TokenHash == device.TokenHash);
            if (existing is null)
            {
                pairedPhones.Insert(targetIndex,
                    new PairedPhonePresentation(device.TokenHash, device.Name, detail));
            }
            else
            {
                existing.Update(device.Name, detail);
                int currentIndex = pairedPhones.IndexOf(existing);
                if (currentIndex != targetIndex) pairedPhones.Move(currentIndex, targetIndex);
            }
        }
        ForgetAllButton.Visibility = devices.Count >= 2 ? Visibility.Visible : Visibility.Collapsed;
        lastKnownDeviceCount = devices.Count;
        ApplyHero(devices.Count);
        ApplyRuntimeAvailability();
    }

    private static string RelativeLastUsed(DateTimeOffset timestamp)
    {
        TimeSpan elapsed = DateTimeOffset.UtcNow - timestamp;
        if (elapsed < TimeSpan.FromMinutes(2)) return UiStrings.Get("UsedJustNow", "Used just now");
        if (elapsed < TimeSpan.FromHours(1))
            return UiStrings.Format("UsedMinutesAgo", "Used {0} minutes ago", (int)elapsed.TotalMinutes);
        if (elapsed < TimeSpan.FromDays(1))
            return UiStrings.Format("UsedHoursAgo", "Used {0} hours ago", (int)elapsed.TotalHours);
        int days = Math.Max(1, (int)elapsed.TotalDays);
        return days == 1
            ? UiStrings.Get("UsedOneDayAgo", "Used 1 day ago")
            : UiStrings.Format("UsedDaysAgo", "Used {0} days ago", days);
    }

    private async Task RefreshStatusAsync()
    {
        if (runtime is null || statusRefreshRunning || !uiActivityActive) return;
        statusRefreshRunning = true;
        try
        {
            using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(7));
            bool forcedUnavailable = launch.UiState is "itunes-error" or "both-errors";
            PlaybackState state = await runtime.StateHub.GetStateAsync(timeout.Token);
            if (forcedUnavailable) state = state with { ITunesAvailable = false };
            ItunesStatusText.Text = state.ITunesAvailable
                ? UiStrings.Get("ItunesReady", "iTunes ready")
                : UiStrings.Get("OpenItunes", "Open iTunes");
            ItunesStatusText.Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources[
                state.ITunesAvailable ? "SecondaryTextBrush" : "DangerBrush"];
            ItunesStatusIndicator.Fill = (Brush)Microsoft.UI.Xaml.Application.Current.Resources[
                state.ITunesAvailable ? "SuccessBrush" : "DangerBrush"];
            if (!state.ITunesAvailable)
                SetProblem(new BridgeProblem(BridgeProblemKind.ITunesUnavailable,
                    UiStrings.Get("ItunesUnavailableTitle", "iTunes is unavailable"),
                    UiStrings.Get("ItunesUnavailableDetail", "Open iTunes on this PC to begin playback.")));
            else ClearProblem(BridgeProblemKind.ITunesUnavailable);
        }
        catch (Exception exception)
        {
            // A busy or restarting automation worker is not evidence that iTunes is closed.
            // Preserve the last authoritative status and let the next refresh retry.
            BridgeDiagnostics.Record("ui.itunes-status", exception);
        }
        finally { statusRefreshRunning = false; }
    }

    private void SetProblem(BridgeProblem problem)
    {
        bool changed = healthState.Set(problem, problem.Kind);
        if (!changed) return;
        _ = RenderProblemsAsync();
        Announce(problem.Title + ". " + problem.Detail, AutomationNotificationKind.Other);
    }

    private void ClearProblem(BridgeProblemKind kind)
    {
        if (healthState.Set(null, kind)) _ = RenderProblemsAsync();
    }

    private async Task RenderProblemsAsync()
    {
        int generation = ++problemPresentationGeneration;
        CancellationToken token = BeginProblemPresentation();
        IReadOnlyList<BridgeProblem> problems = healthState.Active;
        try
        {
            if (launch.VerifyLayout || launch.SnapshotPath is not null)
            {
                RenderProblemsImmediately();
                return;
            }
            if (problems.Count == 0)
            {
                await AnimateOpacityAsync(ProblemBanner, 0, feedbackDurationMs, token);
                if (generation == problemPresentationGeneration)
                {
                    ProblemBanner.Opacity = 0;
                    ProblemBanner.Visibility = Visibility.Collapsed;
                }
                return;
            }
            if (ProblemBanner.Visibility == Visibility.Collapsed)
            {
                ProblemItems.ItemsSource = problems;
                ProblemBanner.Opacity = 0;
                ProblemBanner.Visibility = Visibility.Visible;
                await AnimateOpacityAsync(ProblemBanner, 1, feedbackDurationMs, token);
                if (generation == problemPresentationGeneration) ProblemBanner.Opacity = 1;
                return;
            }
            await AnimateOpacityAsync(ProblemItems, 0, feedbackDurationMs / 2, token);
            if (generation != problemPresentationGeneration) return;
            ProblemItems.ItemsSource = problems;
            await AnimateOpacityAsync(ProblemItems, 1, feedbackDurationMs / 2, token);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        finally
        {
            if (generation == problemPresentationGeneration)
            {
                IReadOnlyList<BridgeProblem> current = healthState.Active;
                ProblemItems.ItemsSource = current;
                ProblemItems.Opacity = 1;
                ProblemBanner.Opacity = current.Count == 0 ? 0 : 1;
                ProblemBanner.Visibility = current.Count == 0
                    ? Visibility.Collapsed
                    : Visibility.Visible;
            }
        }
    }

    private CancellationToken BeginProblemPresentation()
    {
        if (problemPresentationCancellation is not null)
        {
            problemPresentationCancellation.Cancel();
            problemPresentationCancellation.Dispose();
        }
        problemPresentationCancellation = new CancellationTokenSource();
        return problemPresentationCancellation.Token;
    }

    private void RenderProblemsImmediately()
    {
        IReadOnlyList<BridgeProblem> problems = healthState.Active;
        ProblemItems.ItemsSource = problems;
        ProblemItems.Opacity = 1;
        ProblemBanner.Opacity = problems.Count == 0 ? 0 : 1;
        ProblemBanner.Visibility = problems.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ApplyHero(int pairedPhoneCount)
    {
        HeroPresentation hero = HeroPresentation.Create(pairedPhoneCount, pairingExpanded);
        pairingExpanded = hero.PairingExpanded;
        HeroTitle.Text = hero.Title;
        HeroDetail.Text = hero.Detail;
        PairingPanel.Visibility = hero.PairingExpanded ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.Visibility = hero.Mode == HeroMode.Ready
            && pairedPhoneCount < BridgeSecurity.MaxPairedDevices
            ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.IsChecked = hero.PairingExpanded;
        PairAnotherButton.Content = hero.PairingExpanded
            ? UiStrings.Get("HidePairingCode", "Hide pairing code")
            : UiStrings.Get("PairAnotherPhone", "Pair another phone");
        AutomationProperties.SetName(PairAnotherButton, hero.PairingExpanded
            ? UiStrings.Get("HidePairingCode", "Hide pairing code")
            : UiStrings.Get("ShowPairingCode", "Show pairing code"));
    }
}
