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
        uint BrandEnd,
        uint OnBrand,
        uint Success,
        uint Danger);

    private static readonly Palette Dark = new(
        0xFF09090B, 0xFF141416, 0xFFF7F7FA, 0xFF98989F, 0xFFFF86A3,
        0xFFFF5B84, 0xFFB69CFF, 0xFF09090B, 0xFF5AD978, 0xFFFF6B65);
    private static readonly Palette Light = new(
        0xFFFAFAFC, 0xFFFFFFFF, 0xFF151518, 0xFF56565E, 0xFFB91C45,
        0xFFFF5B84, 0xFFB69CFF, 0xFF09090B, 0xFF147A34, 0xFFB42318);

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
            Require(palette.OnBrand, palette.BrandEnd, 4.5, "on-brand on gradient end");
            Require(palette.Success, palette.Canvas, 4.5, "success on canvas");
            Require(palette.Danger, palette.Canvas, 4.5, "danger on canvas");
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
        Ensure(ready.Mode == HeroMode.Ready && !ready.PairingExpanded,
            "returning state prioritizes readiness");
        Ensure(expanded.PairingExpanded, "pair another phone expansion survives refresh");
        Ensure(!full.PairingExpanded, "pairing closes when the two-phone limit is reached");

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
        Ensure(ProblemPresentationTarget.FromProblemCount(0)
                == new ProblemPresentationTarget(false, 0)
            && ProblemPresentationTarget.FromProblemCount(2)
                == new ProblemPresentationTarget(true, 1),
            "problem presentation final states are always fully hidden or fully visible");
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
