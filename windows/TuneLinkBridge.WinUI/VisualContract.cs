namespace TunesLinkBridge;

internal static class VisualContract
{
    private sealed record Palette(
        uint Canvas,
        uint Surface,
        uint Primary,
        uint Secondary,
        uint Accent,
        uint BrandStart,
        uint BrandMid,
        uint BrandEnd,
        uint OnBrand,
        uint Success,
        uint Danger,
        uint SuccessTint,
        uint DangerTint,
        uint HeroAccentStart,
        uint HeroAccentMid,
        uint HeroAccentEnd);

    private static readonly Palette Dark = new(
        0xFF0C0C12, 0xFF16161E, 0xFFF7F7FA, 0xFF9C9CA8, 0xFFFF86A3,
        0xFFD91E5B, 0xFF7A4BE8, 0xFF2E63D9, 0xFFFFFFFF, 0xFF5AD978, 0xFFFF6B65,
        0xFF162B1D, 0xFF321518, 0xFFFF6B8F, 0xFF9D7BFF, 0xFF5EA0FF);
    private static readonly Palette Light = new(
        0xFFF6F6F9, 0xFFFFFFFF, 0xFF131316, 0xFF55555E, 0xFFB91C45,
        0xFFD91E5B, 0xFF7A4BE8, 0xFF2E63D9, 0xFFFFFFFF, 0xFF147A34, 0xFFB42318,
        0xFFE5F6EA, 0xFFFBEDEC, 0xFFD01A56, 0xFF7A4BE8, 0xFF2E63D9);

    public static void VerifyContrast()
    {
        foreach (Palette palette in new[] { Dark, Light })
        {
            Require(palette.Primary, palette.Canvas, 4.5, "primary on canvas");
            Require(palette.Primary, palette.Surface, 4.5, "primary on surface");
            Require(palette.Secondary, palette.Canvas, 4.5, "secondary on canvas");
            Require(palette.Secondary, palette.Surface, 4.5, "secondary on surface");
            Require(palette.Accent, palette.Canvas, 4.5, "accent on canvas");
            Require(palette.OnBrand, palette.BrandStart, 4.5, "on-brand on gradient start");
            Require(palette.OnBrand, palette.BrandMid, 4.5, "on-brand on gradient middle");
            Require(palette.OnBrand, palette.BrandEnd, 4.5, "on-brand on gradient end");
            Require(palette.Success, palette.Canvas, 4.5, "success on canvas");
            Require(palette.Danger, palette.Canvas, 4.5, "danger on canvas");
            Require(palette.Success, palette.SuccessTint, 4.5, "success on success tint");
            Require(palette.Danger, palette.DangerTint, 4.5, "danger on danger tint");
            Require(palette.Primary, palette.DangerTint, 4.5, "primary on danger tint");
            Require(palette.Secondary, palette.DangerTint, 4.5, "secondary on danger tint");
            Require(palette.HeroAccentStart, palette.Canvas, 4.5, "hero accent start on canvas");
            Require(palette.HeroAccentMid, palette.Canvas, 4.5, "hero accent middle on canvas");
            Require(palette.HeroAccentEnd, palette.Canvas, 4.5, "hero accent end on canvas");
        }
        VerifyPresentationModels();
    }

    private static void VerifyPresentationModels()
    {
        BridgeHealthState health = new();
        BridgeProblem network = new(BridgeProblemKind.NetworkUnavailable, "Network", "Unavailable");
        BridgeProblem itunes = new(BridgeProblemKind.ITunesUnavailable, "iTunes", "Unavailable");
        Ensure(health.Set(network, network.Kind), "first health transition is reported");
        Ensure(!health.Set(network, network.Kind), "identical health state is deduplicated");
        Ensure(health.Set(itunes, itunes.Kind) && health.Active.Count == 2,
            "simultaneous problems are retained");
        Ensure(health.Set(null, BridgeProblemKind.NetworkUnavailable) && health.Active.Count == 1,
            "resolving one problem preserves the other");

        HeroPresentation firstRun = HeroPresentation.Create(0, false);
        HeroPresentation ready = HeroPresentation.Create(1, false);
        HeroPresentation expanded = HeroPresentation.Create(1, true);
        HeroPresentation full = HeroPresentation.Create(BridgeSecurity.MaxPairedDevices, true);
        Ensure(firstRun.Mode == HeroMode.PairFirstPhone && firstRun.PairingExpanded,
            "first phone pairing is primary");
        Ensure(ready.Mode == HeroMode.Paired && !ready.PairingExpanded,
            "returning state prioritizes readiness");
        Ensure(expanded.PairingExpanded, "pair another phone expansion survives refresh");
        Ensure(!full.PairingExpanded, "pairing closes when the two-phone limit is reached");
        PairingDisclosure disclosure = new();
        Ensure(disclosure.Update(0).PairingExpanded, "first run discloses the pairing code automatically");
        Ensure(!disclosure.Update(1).PairingExpanded,
            "the automatic first-run disclosure closes when the first phone pairs");
        disclosure.SetUserExpanded(true);
        Ensure(disclosure.Update(1).PairingExpanded, "a user-opened disclosure survives refreshes");
        Ensure(!disclosure.Update(2).PairingExpanded && !disclosure.UserExpanded,
            "a newly paired phone closes a user-opened disclosure");
        Ensure(!disclosure.Update(1).PairingExpanded,
            "forgetting a phone does not reopen a disclosure the pairing closed");
        disclosure.SetUserExpanded(true);
        Ensure(disclosure.Update(0).PairingExpanded && !disclosure.Update(1).PairingExpanded,
            "re-pairing after forgetting every phone closes the disclosure again");

        Ensure(PairingWindowPolicy.AcceptsPairing(windowShown: true, minimized: false, pairingPanelVisible: true),
            "pairing is open while the code is on screen");
        Ensure(!PairingWindowPolicy.AcceptsPairing(windowShown: true, minimized: true, pairingPanelVisible: true),
            "a minimized window closes pairing");
        Ensure(!PairingWindowPolicy.AcceptsPairing(windowShown: false, minimized: false, pairingPanelVisible: true),
            "a window hidden to the notification area closes pairing");
        Ensure(!PairingWindowPolicy.AcceptsPairing(windowShown: true, minimized: false, pairingPanelVisible: false),
            "a collapsed pairing panel closes pairing");

        DateTimeOffset now = new(2026, 9, 27, 12, 0, 0, TimeSpan.Zero);
        SecuritySnapshot shown = new(1, now.AddDays(-1), "111111", now.AddMinutes(5));
        Ensure(SecurityChangeInference.Infer(shown, shown with { DeviceCount = 2, PairCode = "222222" }, now)
                == SecurityChangeCause.DevicePaired,
            "a new phone is recognised even though pairing also rotates the code");
        Ensure(SecurityChangeInference.Infer(shown, shown with { NewestPairedAt = now, PairCode = "222222" }, now)
                == SecurityChangeCause.DevicePaired,
            "a phone re-pairing in place is recognised");
        Ensure(SecurityChangeInference.Infer(shown, shown with { PairCode = "222222" }, now)
                == SecurityChangeCause.PairCodeReplacedAfterFailedAttempts,
            "an early code change is the wrong-guess replacement");
        Ensure(SecurityChangeInference.Infer(shown, shown with { PairCode = "222222" }, now.AddMinutes(6))
                == SecurityChangeCause.AutomaticPairCodeRotation,
            "a code change after expiry is the routine rotation");
        Ensure(SecurityChangeInference.Infer(shown, shown with { DeviceCount = 0, NewestPairedAt = null }, now)
                == SecurityChangeCause.InitialRefresh,
            "a phone unpairing itself is not announced as a pairing");
        Ensure(SecurityAnnouncementDecision.Create(
                SecurityChangeCause.PairCodeReplacedAfterFailedAttempts, pairingPanelVisible: true).Kind
            == SecurityAnnouncementKind.PairingCodeReplaced,
            "a visible wrong-guess replacement is explained, not called a new code");
        Ensure(!SecurityAnnouncementDecision.Create(
                SecurityChangeCause.PairCodeReplacedAfterFailedAttempts, pairingPanelVisible: false)
            .ShouldAnnounce,
            "a hidden wrong-guess replacement is silent");

        Ensure(StartupFailurePresentation.Classify(
                new System.Net.Sockets.SocketException((int)System.Net.Sockets.SocketError.AddressAlreadyInUse))
            == StartupFailureKind.PortInUse, "a taken port is recognised");
        Ensure(StartupFailurePresentation.Classify(
                new System.Net.Sockets.SocketException((int)System.Net.Sockets.SocketError.AccessDenied))
            == StartupFailureKind.PortBlocked, "a reserved port is recognised");
        Ensure(StartupFailurePresentation.Classify(new UnauthorizedAccessException())
            == StartupFailureKind.SettingsAccessDenied, "denied settings access is recognised");
        Ensure(StartupFailurePresentation.Classify(
                new System.Security.Cryptography.CryptographicException())
            == StartupFailureKind.SecurityIdentity, "a certificate failure is recognised");
        Ensure(StartupFailurePresentation.Classify(new IOException())
            == StartupFailureKind.SettingsUnavailable, "an unreadable settings file is recognised");
        Ensure(StartupFailurePresentation.Classify(new InvalidOperationException())
            == StartupFailureKind.Unknown, "unknown failures fall back to a generic reason");
        Ensure(StartupFailurePresentation.Detail(StartupFailureKind.PortInUse, 45832).Contains("45832",
                StringComparison.Ordinal)
            && StartupFailurePresentation.Detail(StartupFailureKind.PortInUse, 0).Contains(
                BridgeProtocol.DefaultPort.ToString(System.Globalization.CultureInfo.CurrentCulture),
                StringComparison.Ordinal),
            "the port message names the real listening port");

        Ensure(TimePhrases.RelativeLastUsed(TimeSpan.FromMinutes(61)) == "Used 1 hour ago"
            && TimePhrases.RelativeLastUsed(TimeSpan.FromHours(5)) == "Used 5 hours ago"
            && TimePhrases.RelativeLastUsed(TimeSpan.FromDays(1.5)) == "Used 1 day ago",
            "relative times agree in number");
        Ensure(TimePhrases.ExpiryAccessibleName(61) == "Pairing code expires in 1 minute and 1 second"
            && TimePhrases.ExpiryAccessibleName(600) == "Pairing code expires in 10 minutes"
            && TimePhrases.ExpiryAccessibleName(45) == "Pairing code expires in 45 seconds",
            "the spoken expiry agrees in number and omits empty parts");

        Ensure(StartupRegistration.IsApproved(null) && StartupRegistration.IsApproved(Array.Empty<byte>())
            && StartupRegistration.IsApproved("unexpected"),
            "a missing startup approval means enabled");
        Ensure(StartupRegistration.IsApproved(new byte[] { 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 })
            && StartupRegistration.IsApproved(new byte[] { 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }),
            "Explorer's enabled approvals are read as enabled");
        Ensure(!StartupRegistration.IsApproved(new byte[] { 0x03, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8 })
            && !StartupRegistration.IsApproved(new byte[] { 0x07, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8 }),
            "a startup entry disabled in Task Manager or Settings is read as disabled");
        byte[] enabledApproval = StartupRegistration.EnabledApprovalValue();
        Ensure(enabledApproval.Length == 12 && enabledApproval[0] == 0x02
            && enabledApproval.Skip(1).All(value => value == 0)
            && StartupRegistration.IsApproved(enabledApproval),
            "turning Open at login on writes Explorer's enabled approval");

        Ensure(!ready.Title.Contains("connected", StringComparison.OrdinalIgnoreCase)
            && !ready.Title.Contains("ready", StringComparison.OrdinalIgnoreCase),
            "a saved pairing does not claim a current connection or healthy backend");

        RuntimeAvailabilityPresentation unavailable =
            RuntimeAvailabilityPresentation.Create(runtimeAvailable: false, hasValidatedLanAddress: true);
        Ensure(!unavailable.CanRequestNewCode && !unavailable.CanPairAnotherPhone
            && !unavailable.CanManageDevices && !unavailable.CanChangeRuntimeSettings
            && !unavailable.CanCopyPairingCode && !unavailable.CanCopyAddress,
            "runtime failure disables every runtime-backed action");
        RuntimeAvailabilityPresentation noAddress =
            RuntimeAvailabilityPresentation.Create(runtimeAvailable: true, hasValidatedLanAddress: false);
        Ensure(noAddress.CanRequestNewCode && noAddress.CanPairAnotherPhone
            && noAddress.CanManageDevices && noAddress.CanChangeRuntimeSettings
            && noAddress.CanCopyPairingCode && !noAddress.CanCopyAddress,
            "an invalid LAN address disables only address copying");
        Ensure(!RuntimeAvailabilityPresentation.Create(true, true,
                BridgeSecurity.MaxPairedDevices).CanPairAnotherPhone,
            "pair another phone is disabled at the device limit");
        Ensure(RuntimeAvailabilityPresentation.Create(true, true).CanCopyAddress,
            "a validated LAN address can be copied when the runtime is available");
        Ensure(NetworkAddressPresentation.Format("192.168.1.20", 45832) == "192.168.1.20:45832",
            "a live bridge address includes its listening port");
        Ensure(NetworkAddressPresentation.Format("192.168.1.20", 0) == "192.168.1.20",
            "a preview never presents port zero as a usable endpoint");

        Ensure(!SecurityAnnouncementDecision.Create(
                SecurityChangeCause.AutomaticPairCodeRotation, pairingPanelVisible: false)
            .ShouldAnnounce,
            "hidden automatic code rotation is silent");
        Ensure(SecurityAnnouncementDecision.Create(
                SecurityChangeCause.AutomaticPairCodeRotation, pairingPanelVisible: true).Kind
            == SecurityAnnouncementKind.PairingCodeRotated,
            "visible automatic code rotation is announced specifically");
        Ensure(SecurityAnnouncementDecision.Create(
                SecurityChangeCause.UserRequestedNewCode, pairingPanelVisible: false).Kind
            == SecurityAnnouncementKind.NewPairingCodeGenerated,
            "user-requested code generation is announced once regardless of panel state");
        SecurityAnnouncementDecision forgotten = SecurityAnnouncementDecision.Create(
            SecurityChangeCause.DeviceForgotten, pairingPanelVisible: false, "Living room phone");
        Ensure(forgotten.Kind == SecurityAnnouncementKind.DeviceForgotten
            && forgotten.DeviceName == "Living room phone",
            "typed destructive announcements retain the device identity");

        Ensure(DeviceFocusTarget.AfterRemoval(1, 2, pairingPanelExpanded: false)
            == new DeviceFocusTarget(DeviceFocusTargetKind.RemainingDevice, 1),
            "device removal focuses the next surviving row");
        Ensure(DeviceFocusTarget.AfterRemoval(2, 2, pairingPanelExpanded: false)
            == new DeviceFocusTarget(DeviceFocusTargetKind.RemainingDevice, 1),
            "removing the final row focuses the previous surviving row");
        Ensure(DeviceFocusTarget.AfterRemoval(0, 0, pairingPanelExpanded: false).Kind
            == DeviceFocusTargetKind.PairAnother,
            "an empty collapsed device list focuses Pair Another");
        Ensure(DeviceFocusTarget.AfterRemoval(0, 0, pairingPanelExpanded: true).Kind
            == DeviceFocusTargetKind.PairingCode,
            "an empty expanded device list focuses the pairing code region");
        Ensure(DeviceFocusTarget.AfterForgetAll().Kind == DeviceFocusTargetKind.PairingCode,
            "Forget All focuses the first-pairing destination");

        MotionPolicy normalMotion = new(true, true, false);
        MotionPolicy reducedMotion = new(false, true, false);
        Ensure(normalMotion.SpatialMotionEnabled && normalMotion.ReflowMotionEnabled
            && normalMotion.OpacityFeedbackEnabled
            && normalMotion.FeedbackDurationMs == MotionTokens.StatusCrossfadeMs,
            "normal motion enables spatial reflow and standard feedback");
        Ensure(!reducedMotion.SpatialMotionEnabled && !reducedMotion.ReflowMotionEnabled
            && reducedMotion.OpacityFeedbackEnabled
            && reducedMotion.FeedbackDurationMs == MotionTokens.ReducedFeedbackMs,
            "reduced motion removes spatial movement but retains short opacity feedback");
        Ensure(!new MotionPolicy(true, true, true).MaterialsEnabled,
            "high contrast disables translucent materials");
        Ensure(MotionTokens.EaseOut == new CubicBezierToken(0.23, 1, 0.32, 1)
            && MotionTokens.EaseInOut == new CubicBezierToken(0.77, 0, 0.175, 1),
            "motion curves match the shared compositor contract exactly");

        using (PresentationGenerationCoordinator presentations = new())
        {
            PresentationGeneration first = presentations.Begin();
            PresentationGeneration second = presentations.Begin();
            Ensure(first.Token.IsCancellationRequested && !presentations.IsCurrent(first),
                "a replacement presentation cancels and invalidates its predecessor");
            Ensure(presentations.IsCurrent(second),
                "the replacement presentation owns the current generation");
            presentations.Complete(first);
            Ensure(presentations.IsCurrent(second),
                "an obsolete completion cannot finish the current presentation");
            presentations.Complete(second);
            Ensure(!presentations.IsCurrent(second),
                "the current generation is invalid after completion");
        }
    }

    private static void Require(uint foreground, uint background, double minimum, string label)
    {
        double first = Luminance(foreground);
        double second = Luminance(background);
        double ratio = (Math.Max(first, second) + 0.05) / (Math.Min(first, second) + 0.05);
        if (ratio < minimum)
            throw new InvalidOperationException($"{label} contrast was {ratio:F2}:1.");
    }

    private static void Ensure(bool condition, string label)
    {
        if (!condition) throw new InvalidOperationException($"Visual contract failed: {label}.");
    }

    private static double Luminance(uint color)
    {
        static double Channel(uint color, int shift)
        {
            double value = (color >> shift & 0xFF) / 255.0;
            return value <= 0.04045 ? value / 12.92 : Math.Pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * Channel(color, 16)
            + 0.7152 * Channel(color, 8)
            + 0.0722 * Channel(color, 0);
    }
}
