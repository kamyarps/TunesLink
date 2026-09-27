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
        bool largeText = Math.Max(uiSettings.TextScaleFactor, launch.TextScale) >= 1.5;
        HeroArtworkContainer.Visibility = largeText ? Visibility.Collapsed : Visibility.Visible;
        // The settings button overlays the illustration in the standard layout. Reserve its
        // own row when decoration is hidden so scaled headings never run behind the button.
        Grid.SetRow(SettingsButton, largeText ? 0 : 1);
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
        ApplyRingPulsePolicy();
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
            || DevicesItems.IsEnabled || KeepRunningToggle.IsEnabled || OpenAtLoginToggle.IsEnabled
            || ItunesStatusChip.Visibility != Visibility.Collapsed))
            throw new InvalidOperationException("TunesLink WinUI unavailable-state verification failed.");
        VerifyVisibleBoundsAndTargets();
        if (launch.Demo && OpenAtLoginToggle.IsEnabled)
            throw new InvalidOperationException("Preview must not expose persistent startup registration.");
        if (launch.TextScale >= 1.5 && HeroArtworkContainer.Visibility != Visibility.Collapsed)
            throw new InvalidOperationException("Large text must prioritize status and actions over decoration.");
        Rect heroBounds = HeroTitle.TransformToVisual(RootGrid).TransformBounds(
            new Rect(0, 0, HeroTitle.ActualWidth, HeroTitle.ActualHeight));
        Rect settingsBounds = SettingsButton.TransformToVisual(RootGrid).TransformBounds(
            new Rect(0, 0, SettingsButton.ActualWidth, SettingsButton.ActualHeight));
        if (heroBounds.Top < settingsBounds.Bottom && heroBounds.Bottom > settingsBounds.Top
            && heroBounds.Left < settingsBounds.Right && heroBounds.Right > settingsBounds.Left)
            throw new InvalidOperationException($"The settings button overlaps the hero heading: {settingsBounds}; {heroBounds}.");
        VerifyThemeResources();
        if (runtime is not null
            && runtime.Security.PairingOpen != (PairingPanel.Visibility == Visibility.Visible))
            throw new InvalidOperationException("Pairing must be open exactly while the code is on screen.");
        if (launch.UiState == "startup-failure")
        {
            await Task.WhenAny(startupDialogOpened.Task, Task.Delay(TimeSpan.FromSeconds(5)));
            if (!startupDialogOpened.Task.IsCompleted)
                throw new InvalidOperationException("The startup-failure dialog did not open.");
            // Let the dialog's open transition finish so the capture shows its settled state.
            await Task.Delay(1200);
            VerifyOpenDialogTargets();
        }
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

    private void ApplyRingPulsePolicy()
    {
        // The backdrop rings march outward while a phone is connected and the window is on
        // screen; without motion the static ring positions are shown instead (reduced motion,
        // captures), and a hidden or minimized window runs no compositor animation at all.
        bool pulseWanted = ringPulseAllowedByPolicy && heroReady;
        bool pulseEnabled = pulseWanted && uiActivityActive;
        ringPulseRunning = pulseEnabled;
        if (pulseEnabled) EnsureBackdropWave();
        else RemoveBackdropWave();
        BackdropRings.Visibility = heroReady && !pulseWanted ? Visibility.Visible : Visibility.Collapsed;
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

    private void VerifyThemeResources()
    {
        // The high-contrast dictionaries are only resolved when high contrast is on, which a
        // capture cannot switch; touching every entry still catches a broken key or brush.
        foreach (ResourceDictionary resources in new[]
                 {
                     Microsoft.UI.Xaml.Application.Current.Resources, PairAnotherButton.Resources
                 })
        {
            foreach (object theme in resources.ThemeDictionaries.Values)
            {
                if (theme is not ResourceDictionary dictionary) continue;
                foreach (KeyValuePair<object, object> entry in dictionary)
                    if (entry.Value is null)
                        throw new InvalidOperationException($"Theme resource {entry.Key} is empty.");
            }
        }
    }

    private void VerifyOpenDialogTargets()
    {
        IReadOnlyList<Microsoft.UI.Xaml.Controls.Primitives.Popup> popups =
            VisualTreeHelper.GetOpenPopupsForXamlRoot(RootGrid.XamlRoot);
        if (popups.Count == 0)
            throw new InvalidOperationException("The startup-failure dialog is not open.");
        foreach (Microsoft.UI.Xaml.Controls.Primitives.Popup popup in popups)
        {
            if (popup.Child is null) continue;
            foreach (Button button in Descendants(popup.Child).OfType<Button>())
            {
                if (button.Visibility == Visibility.Visible && button.ActualWidth > 0
                    && button.ActualHeight < 44)
                    throw new InvalidOperationException($"Dialog target is shorter than 44 DIPs: {button.Content}.");
            }
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
        byte[] pixels = (await render.GetPixelsAsync()).ToArray();
        // Rendering the page leaves out popups, so an open dialog is rendered on its own and
        // composited over the page (both premultiplied BGRA of the same window size). The
        // list is topmost first, so it is painted in reverse.
        foreach (Microsoft.UI.Xaml.Controls.Primitives.Popup popup in
                 VisualTreeHelper.GetOpenPopupsForXamlRoot(RootGrid.XamlRoot).Reverse())
        {
            if (popup.Child is not UIElement child) continue;
            RenderTargetBitmap overlay = new();
            await overlay.RenderAsync(child);
            byte[] layer = (await overlay.GetPixelsAsync()).ToArray();
            if (overlay.PixelWidth != render.PixelWidth || overlay.PixelHeight != render.PixelHeight)
                throw new InvalidOperationException("A dialog could not be composited into the snapshot.");
            for (int index = 0; index + 3 < pixels.Length; index += 4)
            {
                int inverseAlpha = 255 - layer[index + 3];
                for (int channel = 0; channel < 4; channel++)
                    pixels[index + channel] = (byte)Math.Min(255,
                        layer[index + channel] + (pixels[index + channel] * inverseAlpha + 127) / 255);
            }
        }
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
            pixels);
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
        OpenAtLoginToggle.IsEnabled = availability.CanChangeRuntimeSettings && !launch.Demo;
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
        AutomationProperties.SetName(PairExpiryText, TimePhrases.ExpiryAccessibleName(seconds));
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
                    : NetworkAddressPresentation.Help(selection)));
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
        // Without discovery a phone cannot find the bridge on its own; say so quietly on the
        // card that holds the address it must type instead. A preview's disabled port does
        // not count as a failure.
        bool discoveryUnavailable = copyableAddress is not null
            && (launch.UiState == "discovery-unavailable"
                || (runtime.Options.DiscoveryPort > 0 && !runtime.Server.DiscoveryAvailable));
        AddressNoteText.Text = UiStrings.Get("DiscoveryUnavailable",
            "Automatic discovery is unavailable. Enter this address on your phone.");
        AddressNoteText.Visibility = discoveryUnavailable ? Visibility.Visible : Visibility.Collapsed;
        AutomationProperties.SetHelpText(AddressText, NetworkAddressPresentation.Help(selection));
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
                device.PairedAt.ToLocalTime(),
                TimePhrases.RelativeLastUsed(DateTimeOffset.UtcNow - device.LastSeenAt));
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
        ApplyHero(devices.Count);
        ApplyRuntimeAvailability();
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
        HeroPresentation hero = pairingDisclosure.Update(pairedPhoneCount);
        SetHeroText(hero);
        // The signal rings exist only while a phone is connected: static rings behind the devices,
        // and the outward pulse on top of them.
        heroReady = hero.Mode == HeroMode.Paired;
        ApplyRingPulsePolicy();
        PhoneCheckMark.Visibility = !heroRasterActive && heroReady
            ? Visibility.Visible : Visibility.Collapsed;
        PairingPanel.Visibility = hero.PairingExpanded ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.Visibility = hero.Mode == HeroMode.Paired
            && pairedPhoneCount < BridgeSecurity.MaxPairedDevices
            ? Visibility.Visible : Visibility.Collapsed;
        PairAnotherButton.IsChecked = hero.PairingExpanded;
        PairAnotherLabel.Text = hero.PairingExpanded
            ? UiStrings.Get("HidePairingCode", "Hide pairing code")
            : UiStrings.Get("PairAnotherPhone", "Pair another phone");
        PairAnotherGlyph.Glyph = hero.PairingExpanded ? "\uE70E" : "\uE8FA";
        // The accessible name matches the visible label so voice control can target it.
        AutomationProperties.SetName(PairAnotherButton, PairAnotherLabel.Text);
        UpdatePairingOpen();
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
