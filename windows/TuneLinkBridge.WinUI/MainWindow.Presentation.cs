using Microsoft.UI;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
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
        // Snapshots render the XAML tree only, so the backdrop must be a solid canvas there
        // and reflow transitions would be captured mid-flight.
        bool isolatedCapture = launch.VerifyLayout || launch.SnapshotPath is not null;
        bool materialsEnabled = policy.MaterialsEnabled && !isolatedCapture;
        ContentStack.ChildrenTransitions = policy.ReflowMotionEnabled && !isolatedCapture
            ? contentReflowTransitions : null;
        ringPulseAllowedByPolicy = policy.SpatialMotionEnabled && !isolatedCapture && !heroRasterActive;
        ApplyRingPulsePolicy(ringPulseAllowedByPolicy && heroReady);
        RootGrid.Background = materialsEnabled
            ? new SolidColorBrush(Colors.Transparent)
            : (Brush)Microsoft.UI.Xaml.Application.Current.Resources["CanvasBrush"];
        if (materialsEnabled)
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
        // Initial keyboard focus is timing-dependent; pointer-state focus draws no focus visual,
        // so captures stay deterministic.
        if (FocusManager.GetFocusedElement(RootGrid.XamlRoot) is Control focusedControl)
            focusedControl.Focus(FocusState.Pointer);
        await Task.WhenAny(
            Task.WhenAll(WaitForImageReadyAsync(TitleBarIcon), WaitForImageReadyAsync(HeroBadgeImage),
                WaitForImageReadyAsync(LaptopFrameImage), WaitForImageReadyAsync(PhoneFrameImage),
                WaitForImageReadyAsync(PhoneShadowImage)),
            Task.Delay(TimeSpan.FromSeconds(2)));
        ApplyVerificationTextScale();
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

    private void ApplyRingPulsePolicy(bool pulseEnabled)
    {
        // The backdrop rings march outward while a phone is connected; without motion the
        // static ring positions are shown instead (reduced motion, captures).
        ringPulseRunning = pulseEnabled;
        if (pulseEnabled) EnsureBackdropWave();
        if (backdropWave is not null) backdropWave.IsVisible = pulseEnabled;
        BackdropRings.Visibility = heroReady && !pulseEnabled ? Visibility.Visible : Visibility.Collapsed;
    }

    private static Task WaitForImageReadyAsync(Image image)
    {
        if (image.Source is not BitmapImage bitmap || bitmap.PixelWidth > 0)
            return Task.CompletedTask;
        TaskCompletionSource completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        image.ImageOpened += (_, _) => completion.TrySetResult();
        image.ImageFailed += (_, _) => completion.TrySetResult();
        if (bitmap.PixelWidth > 0) completion.TrySetResult();
        return completion.Task;
    }

    private void ApplyVerificationTextScale()
    {
        if (launch.TextScale == 1.0) return;
        foreach (DependencyObject descendant in Descendants(RootGrid))
        {
            if (descendant is TextBlock text)
                text.FontSize *= launch.TextScale;
        }
        UpdateStatusChipOrientation(RootGrid.ActualWidth);
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
            SetStatusChip(NetworkStatusIndicator, NetworkStatusText, healthy: false,
                UiStrings.Get("PrivateAddressUnavailable", "Private address unavailable"));
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
            SetStatusChip(NetworkStatusIndicator, NetworkStatusText, healthy: true,
                UiStrings.Get("LocalNetworkReady", "Local network ready"));
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
            SetStatusChip(ItunesStatusIndicator, ItunesStatusText, state.ITunesAvailable,
                state.ITunesAvailable
                    ? UiStrings.Get("ItunesReady", "iTunes ready")
                    : UiStrings.Get("OpenItunes", "Open iTunes"));
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
        // The status chips carry the visual state; the health map only deduplicates announcements.
        if (healthState.Set(problem, problem.Kind))
            Announce(problem.Title + ". " + problem.Detail, AutomationNotificationKind.Other);
    }

    private void ClearProblem(BridgeProblemKind kind) => healthState.Set(null, kind);

    private void ApplyHero(int pairedPhoneCount)
    {
        HeroPresentation hero = HeroPresentation.Create(pairedPhoneCount, pairingExpanded);
        pairingExpanded = hero.PairingExpanded;
        SetHeroText(hero);
        // The signal rings exist only while a phone is connected: static rings behind the devices,
        // and the outward pulse on top of them.
        heroReady = hero.Mode == HeroMode.Ready;
        ApplyRingPulsePolicy(ringPulseAllowedByPolicy && heroReady);
        PhoneCheckMark.Visibility = !heroRasterActive && heroReady
            ? Visibility.Visible : Visibility.Collapsed;
        PairingPanel.Visibility = hero.PairingExpanded ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.Visibility = hero.Mode == HeroMode.Ready
            && pairedPhoneCount < BridgeSecurity.MaxPairedDevices
            ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.IsChecked = hero.PairingExpanded;
        PairAnotherLabel.Text = hero.PairingExpanded
            ? UiStrings.Get("HidePairingCode", "Hide pairing code")
            : UiStrings.Get("PairAnotherPhone", "Pair another phone");
        PairAnotherGlyph.Glyph = hero.PairingExpanded ? "\uE70E" : "\uE8FA";
        AutomationProperties.SetName(PairAnotherButton, hero.PairingExpanded
            ? UiStrings.Get("HidePairingCode", "Hide pairing code")
            : UiStrings.Get("ShowPairingCode", "Show pairing code"));
    }

    private void SetHeroText(HeroPresentation hero)
    {
        HeroTitle.Inlines.Clear();
        string[] lines = hero.Title.Split('\n');
        HeroTitle.Inlines.Add(new Run { Text = lines[0] });
        if (lines.Length > 1)
        {
            HeroTitle.Inlines.Add(new LineBreak());
            HeroTitle.Inlines.Add(new Run
            {
                Text = lines[1],
                Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["HeroAccentBrush"]
            });
        }
        HeroDetail.Text = hero.Detail;
    }

    private void SetStatusChip(FontIcon indicator, TextBlock text, bool healthy, string message)
    {
        indicator.Glyph = healthy ? "\uEC61" : "\uEA39";
        indicator.Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources[
            healthy ? "SuccessBrush" : "DangerBrush"];
        text.Text = message;
        text.Foreground = (Brush)Microsoft.UI.Xaml.Application.Current.Resources[
            healthy ? "SecondaryTextBrush" : "DangerBrush"];
        UpdateStatusChipOrientation(RootGrid.ActualWidth);
    }

    private void UpdateStatusChipOrientation(double rootWidth)
    {
        if (rootWidth <= 0) return;
        double available = Math.Min(ContentStack.MaxWidth,
            rootWidth - PageContent.Padding.Left - PageContent.Padding.Right);
        double needed = HeaderStatusPanel.Spacing * Math.Max(0, HeaderStatusPanel.Children.Count - 1);
        foreach (UIElement chip in HeaderStatusPanel.Children)
        {
            chip.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            needed += chip.DesiredSize.Width;
        }
        HeaderStatusPanel.Orientation = needed <= available
            ? Orientation.Horizontal : Orientation.Vertical;
    }

}
