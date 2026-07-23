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
    private void PairAnother_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("Pairing", out BridgeRuntime availableRuntime)) return;
        pairingExpanded = PairAnotherButton.IsChecked == true;
        ApplyHero(availableRuntime.Security.Devices.Count);
        if (pairingExpanded) PairingPanel.StartBringIntoView();
    }

    private void SecurityChanged()
    {
        SecurityChangeCause cause = pendingSecurityChange;
        string? deviceName = pendingSecurityDeviceName;
        pendingSecurityChange = SecurityChangeCause.InitialRefresh;
        pendingSecurityDeviceName = null;
        if (runtime is not null && cause == SecurityChangeCause.InitialRefresh)
        {
            IReadOnlyList<BridgeSecurity.PairedDevice> devices = runtime.Security.Devices;
            if (devices.Count > lastKnownDeviceCount)
            {
                cause = SecurityChangeCause.DevicePaired;
                deviceName = devices.OrderByDescending(device => device.PairedAt).FirstOrDefault()?.Name;
            }
        }
        DispatcherQueue.TryEnqueue(() =>
        {
            RefreshPairing();
            RefreshDevices();
            AnnounceSecurityChange(SecurityAnnouncementDecision.Create(cause,
                PairingPanel.Visibility == Visibility.Visible, deviceName));
        });
    }

    private void AnnounceSecurityChange(SecurityAnnouncementDecision decision)
    {
        string? message = decision.Kind switch
        {
            SecurityAnnouncementKind.PairingCodeRotated =>
                UiStrings.Get("PairingCodeRotated", "Pairing code rotated"),
            SecurityAnnouncementKind.NewPairingCodeGenerated =>
                UiStrings.Get("NewPairingCodeGenerated", "New pairing code generated"),
            SecurityAnnouncementKind.DevicePaired =>
                UiStrings.Format("DevicePairedAnnouncement", "{0} paired",
                    decision.DeviceName ?? UiStrings.Get("Phone", "Phone")),
            SecurityAnnouncementKind.DeviceForgotten =>
                UiStrings.Format("DeviceForgottenAnnouncement", "{0} forgotten",
                    decision.DeviceName ?? UiStrings.Get("Phone", "Phone")),
            SecurityAnnouncementKind.AllDevicesForgotten =>
                UiStrings.Get("AllDevicesForgottenAnnouncement", "All paired devices forgotten"),
            _ => null
        };
        if (message is not null)
            Announce(message, AutomationNotificationKind.ActionCompleted);
    }

    private void AddressChanged() => DispatcherQueue.TryEnqueue(RefreshAddress);

    private async void CopyPairCode_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("Copy pairing code", out BridgeRuntime availableRuntime)) return;
        await CopyWithFeedbackAsync(CopyCodeButton, CopyCodeContent, CopyCodeGlyph,
            CopyCodeLabel, availableRuntime.Security.PairCode,
            UiStrings.Get("PairingCode", "Pairing code"));
    }

    private async void CopyAddress_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("Copy address", out _) || copyableAddress is null)
        {
            Announce(UiStrings.Get("NoAddressToCopy", "No private network address is available to copy"),
                AutomationNotificationKind.ActionAborted);
            return;
        }
        await CopyWithFeedbackAsync(CopyAddressButton, CopyAddressContent, CopyAddressGlyph,
            CopyAddressLabel, copyableAddress, UiStrings.Get("Address", "Address"));
    }

    private async Task CopyWithFeedbackAsync(Button button, UIElement content, FontIcon icon,
                                             TextBlock label, string value, string name)
    {
        CancellationToken token = copyFeedback.Begin(button);
        try
        {
            DataPackage package = new();
            package.SetText(value);
            Clipboard.SetContent(package);
            await SwapCopyContentAsync(content, icon, label,
                UiStrings.Get("Copied", "Copied"), "\uE73E", token);
            Announce(UiStrings.Format("ValueCopiedAnnouncement", "{0} copied", name),
                AutomationNotificationKind.ActionCompleted);
            await Task.Delay(MotionTokens.FeedbackHoldMs, token);
            if (copyFeedback.IsCurrent(button, token))
                await SwapCopyContentAsync(content, icon, label,
                    UiStrings.Get("Copy", "Copy"), "\uE8C8", token);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("clipboard", exception);
            try
            {
                await SwapCopyContentAsync(content, icon, label,
                    UiStrings.Get("CopyFailed", "Failed"), "\uEA39", token);
                Announce(UiStrings.Format("CouldNotCopyValue", "Couldn’t copy {0}", name),
                    AutomationNotificationKind.ActionAborted);
                await Task.Delay(MotionTokens.FeedbackHoldMs, token);
                if (copyFeedback.IsCurrent(button, token))
                    await SwapCopyContentAsync(content, icon, label,
                        UiStrings.Get("Copy", "Copy"), "\uE8C8", token);
            }
            catch (OperationCanceledException) { }
        }
        finally { copyFeedback.Complete(button, token); }
    }

    private async Task SwapCopyContentAsync(UIElement content, FontIcon icon, TextBlock label,
                                            string nextLabel, string nextGlyph, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        int duration = animationsEnabled
            ? MotionTokens.SmallFeedbackMs
            : MotionTokens.ReducedFeedbackMs;
        if (opacityFeedbackEnabled)
            await AnimateOpacityAsync(content, 0, duration / 2, token);
        token.ThrowIfCancellationRequested();
        label.Text = nextLabel;
        icon.Glyph = nextGlyph;
        if (opacityFeedbackEnabled)
            await AnimateOpacityAsync(content, 1, duration / 2, token);
        else content.Opacity = 1;
    }

    private static async Task AnimateOpacityAsync(UIElement element, double opacity, int durationMs,
                                                  CancellationToken token = default)
    {
        token.ThrowIfCancellationRequested();
        TaskCompletionSource completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        DoubleAnimationUsingKeyFrames animation = new()
        {
            Duration = new Duration(TimeSpan.FromMilliseconds(durationMs))
        };
        animation.KeyFrames.Add(new SplineDoubleKeyFrame
        {
            Value = opacity,
            KeyTime = KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(durationMs)),
            KeySpline = new KeySpline
            {
                ControlPoint1 = new Point(MotionTokens.EaseOutX1, MotionTokens.EaseOutY1),
                ControlPoint2 = new Point(MotionTokens.EaseOutX2, MotionTokens.EaseOutY2)
            }
        });
        Storyboard storyboard = new();
        storyboard.Children.Add(animation);
        Storyboard.SetTarget(animation, element);
        Storyboard.SetTargetProperty(animation, "Opacity");
        void Completed(object? sender, object args)
        {
            storyboard.Stop();
            element.Opacity = opacity;
            completion.TrySetResult();
        }
        storyboard.Completed += Completed;
        using CancellationTokenRegistration registration = token.Register(() =>
        {
            double currentOpacity = element.Opacity;
            storyboard.Stop();
            element.Opacity = currentOpacity;
            completion.TrySetCanceled(token);
        });
        storyboard.Begin();
        try { await completion.Task; }
        finally { storyboard.Completed -= Completed; }
    }

    private void NewCode_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("New pairing code", out BridgeRuntime availableRuntime)) return;
        try
        {
            pendingSecurityChange = SecurityChangeCause.UserRequestedNewCode;
            availableRuntime.Security.RegeneratePairCode();
        }
        catch (Exception exception)
        {
            pendingSecurityChange = SecurityChangeCause.InitialRefresh;
            BridgeDiagnostics.Record("security.code.write", exception);
            Announce(UiStrings.Get("NewPairingCodeFailed", "A new pairing code could not be generated"),
                AutomationNotificationKind.ActionAborted);
        }
    }

    private async void ForgetDevice_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("Forget device", out BridgeRuntime availableRuntime)
            || sender is not Button { DataContext: PairedPhonePresentation phone } button) return;
        int removedIndex = pairedPhones.IndexOf(phone);
        ContentDialogResult result = await ShowDialogAsync(
            UiStrings.Format("ForgetDeviceTitle", "Forget {0}?", phone.Name),
            UiStrings.Get("ForgetDeviceDetail",
                "This phone will need the current six-digit pairing code to reconnect."),
            UiStrings.Get("Forget", "Forget"),
            UiStrings.Get("Cancel", "Cancel"),
            destructive: true,
            restoreFocus: button,
            restoreFocusAfterPrimary: false);
        if (result == ContentDialogResult.Primary)
        {
            pendingSecurityChange = SecurityChangeCause.DeviceForgotten;
            pendingSecurityDeviceName = phone.Name;
            PersistenceResult outcome = availableRuntime.Security.TryForgetDevice(phone.TokenHash);
            if (!outcome.Succeeded || !outcome.Changed)
            {
                pendingSecurityChange = SecurityChangeCause.InitialRefresh;
                pendingSecurityDeviceName = null;
                Announce(outcome.Succeeded
                        ? UiStrings.Format("DeviceAlreadyRemoved", "{0} was already removed", phone.Name)
                        : UiStrings.Format("DeviceForgetFailed", "{0} could not be forgotten", phone.Name),
                    outcome.Succeeded
                        ? AutomationNotificationKind.Other
                        : AutomationNotificationKind.ActionAborted);
                RefreshDevices();
            }
            else
            {
                RefreshDevices();
                RestoreFocusAfterDeviceRemoval(removedIndex);
            }
        }
    }

    private async void ForgetAll_Click(object sender, RoutedEventArgs eventArgs)
    {
        if (!TryGetRuntime("Forget all devices", out BridgeRuntime availableRuntime)) return;
        ContentDialogResult result = await ShowDialogAsync(
            UiStrings.Get("ForgetAllTitle", "Forget all paired devices?"),
            UiStrings.Get("ForgetAllDetail",
                "Every phone will need the current six-digit pairing code to reconnect."),
            UiStrings.Get("ForgetAllAction", "Forget all"),
            UiStrings.Get("Cancel", "Cancel"),
            destructive: true,
            restoreFocus: sender as Control,
            restoreFocusAfterPrimary: false);
        if (result == ContentDialogResult.Primary)
        {
            pendingSecurityChange = SecurityChangeCause.AllDevicesForgotten;
            PersistenceResult outcome = availableRuntime.Security.TryForgetAll();
            if (!outcome.Succeeded)
            {
                pendingSecurityChange = SecurityChangeCause.InitialRefresh;
                Announce(UiStrings.Get("ForgetAllFailed", "Paired devices could not be forgotten"),
                    AutomationNotificationKind.ActionAborted);
            }
            RefreshDevices();
            if (outcome.Succeeded) RestoreFocusAfterForgetAll();
        }
    }

    private void RestoreFocusAfterDeviceRemoval(int removedIndex)
    {
        DeviceFocusTarget target = DeviceFocusTarget.AfterRemoval(
            Math.Max(0, removedIndex), pairedPhones.Count,
            PairingPanel.Visibility == Visibility.Visible);
        DispatcherQueue.TryEnqueue(() =>
        {
            DevicesItems.UpdateLayout();
            if (target.Kind == DeviceFocusTargetKind.RemainingDevice
                && target.DeviceIndex >= 0 && target.DeviceIndex < pairedPhones.Count)
            {
                string tokenHash = pairedPhones[target.DeviceIndex].TokenHash;
                Button? action = Descendants(DevicesItems).OfType<Button>().FirstOrDefault(button =>
                    button.DataContext is PairedPhonePresentation item && item.TokenHash == tokenHash);
                if (action?.Focus(FocusState.Programmatic) == true) return;
            }
            if (target.Kind == DeviceFocusTargetKind.PairAnother
                && PairAnotherButton.Visibility == Visibility.Visible
                && PairAnotherButton.Focus(FocusState.Programmatic)) return;
            CopyCodeButton.Focus(FocusState.Programmatic);
        });
    }

    private void RestoreFocusAfterForgetAll() => DispatcherQueue.TryEnqueue(() =>
    {
        ApplyHero(0);
        CopyCodeButton.Focus(FocusState.Programmatic);
    });

    private async Task<ContentDialogResult> ShowDialogAsync(
        string title,
        string detail,
        string primary,
        string? close,
        bool destructive,
        Control? restoreFocus = null,
        bool restoreFocusAfterPrimary = true)
    {
        Control? previous = FocusManager.GetFocusedElement(RootGrid.XamlRoot) as Control;
        ContentDialog dialog = new()
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = title,
            Content = detail,
            PrimaryButtonText = primary,
            CloseButtonText = close ?? "",
            DefaultButton = destructive ? ContentDialogButton.Close : ContentDialogButton.Primary,
            CornerRadius = new CornerRadius(22),
            Background = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Microsoft.UI.Xaml.Application.Current.Resources["SeparatorBrush"],
            BorderThickness = new Thickness(1)
        };
        if (destructive)
        {
            dialog.PrimaryButtonStyle = (Style)Microsoft.UI.Xaml.Application.Current.Resources["DangerButtonStyle"];
            dialog.CloseButtonStyle = (Style)Microsoft.UI.Xaml.Application.Current.Resources["TonalButtonStyle"];
        }
        dialog.Opened += (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            Button? initial = FindButton(dialog, close ?? primary);
            initial?.Focus(FocusState.Programmatic);
        });
        ContentDialogResult result = await dialog.ShowAsync();
        if (result != ContentDialogResult.Primary || restoreFocusAfterPrimary)
            (restoreFocus ?? previous)?.Focus(FocusState.Programmatic);
        return result;
    }

    private static Button? FindButton(DependencyObject parent, string label)
    {
        int count = VisualTreeHelper.GetChildrenCount(parent);
        for (int index = 0; index < count; index++)
        {
            DependencyObject child = VisualTreeHelper.GetChild(parent, index);
            if (child is Button button && string.Equals(button.Content?.ToString(), label,
                    StringComparison.Ordinal)) return button;
            Button? nested = FindButton(child, label);
            if (nested is not null) return nested;
        }
        return null;
    }
}
