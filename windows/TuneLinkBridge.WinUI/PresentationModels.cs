using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace TunesLinkBridge;

internal enum BridgeProblemKind
{
    NetworkUnavailable,
    ITunesUnavailable
}

internal sealed record BridgeProblem(BridgeProblemKind Kind, string Title, string Detail);

internal sealed class PairedPhonePresentation : INotifyPropertyChanged
{
    private string name;
    private string detail;

    public PairedPhonePresentation(string tokenHash, string name, string detail)
    {
        TokenHash = tokenHash;
        this.name = name;
        this.detail = detail;
    }

    public string TokenHash { get; }
    public string Name
    {
        get => name;
        private set => Set(ref name, value);
    }
    public string Detail
    {
        get => detail;
        private set => Set(ref detail, value);
    }
    public string AccessibleName => UiStrings.Format("DeviceAccessibleName", "{0}, {1}", Name, Detail);
    public string ForgetAccessibleName => UiStrings.Format("ForgetDeviceAccessibleName", "Forget {0}", Name);

    public void Update(string updatedName, string updatedDetail)
    {
        Name = updatedName;
        Detail = updatedDetail;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(AccessibleName)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ForgetAccessibleName)));
    }

    private void Set(ref string field, string value, [CallerMemberName] string? property = null)
    {
        if (field == value) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(property));
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}

internal sealed class BridgeHealthState
{
    private readonly Dictionary<BridgeProblemKind, BridgeProblem> active = new();

    public IReadOnlyList<BridgeProblem> Active => active.Values
        .OrderBy(problem => problem.Kind)
        .ToArray();

    public bool Set(BridgeProblem? problem, BridgeProblemKind kind)
    {
        if (problem is null) return active.Remove(kind);
        if (active.TryGetValue(kind, out BridgeProblem? current) && current == problem) return false;
        active[kind] = problem;
        return true;
    }
}

internal enum HeroMode
{
    PairFirstPhone,
    Ready
}

internal sealed record HeroPresentation(
    HeroMode Mode,
    string Title,
    string Detail,
    bool PairingExpanded)
{
    public static HeroPresentation Create(int pairedPhoneCount, bool userExpandedPairing)
    {
        if (pairedPhoneCount == 0)
            return new(HeroMode.PairFirstPhone,
                UiStrings.Get("HeroPairTitle", "Pair your phone"),
                UiStrings.Get("HeroPairDetail", "Open TunesLink on your phone and enter the pairing code."),
                true);
        return new(HeroMode.Ready,
            UiStrings.Get("HeroReadyTitle", "TunesLink is ready"),
            pairedPhoneCount == 1
                ? UiStrings.Get("HeroReadyDetail", "Your phone can control iTunes on this PC.")
                : UiStrings.Get("HeroReadyMultipleDetail", "Your phones can control iTunes on this PC."),
            userExpandedPairing && pairedPhoneCount < BridgeSecurity.MaxPairedDevices);
    }
}

internal sealed record RuntimeAvailabilityPresentation(
    bool RuntimeAvailable,
    bool CanRequestNewCode,
    bool CanPairAnotherPhone,
    bool CanManageDevices,
    bool CanChangeRuntimeSettings,
    bool CanCopyPairingCode,
    bool CanCopyAddress)
{
    public static RuntimeAvailabilityPresentation Create(bool runtimeAvailable,
                                                         bool hasValidatedLanAddress,
                                                         int pairedPhoneCount = 0) => new(
        RuntimeAvailable: runtimeAvailable,
        CanRequestNewCode: runtimeAvailable,
        CanPairAnotherPhone: runtimeAvailable
            && pairedPhoneCount < BridgeSecurity.MaxPairedDevices,
        CanManageDevices: runtimeAvailable,
        CanChangeRuntimeSettings: runtimeAvailable,
        CanCopyPairingCode: runtimeAvailable,
        CanCopyAddress: runtimeAvailable && hasValidatedLanAddress);
}

internal enum SecurityChangeCause
{
    InitialRefresh,
    AutomaticPairCodeRotation,
    UserRequestedNewCode,
    DevicePaired,
    DeviceForgotten,
    AllDevicesForgotten
}

internal enum SecurityAnnouncementKind
{
    None,
    PairingCodeRotated,
    NewPairingCodeGenerated,
    DevicePaired,
    DeviceForgotten,
    AllDevicesForgotten
}

internal sealed record SecurityAnnouncementDecision(
    SecurityAnnouncementKind Kind,
    string? DeviceName = null)
{
    public bool ShouldAnnounce => Kind != SecurityAnnouncementKind.None;

    public static SecurityAnnouncementDecision Create(SecurityChangeCause cause,
                                                      bool pairingPanelVisible,
                                                      string? deviceName = null) => cause switch
                                                      {
                                                          SecurityChangeCause.AutomaticPairCodeRotation when pairingPanelVisible =>
                                                              new(SecurityAnnouncementKind.PairingCodeRotated),
                                                          SecurityChangeCause.UserRequestedNewCode =>
                                                              new(SecurityAnnouncementKind.NewPairingCodeGenerated),
                                                          SecurityChangeCause.DevicePaired =>
                                                              new(SecurityAnnouncementKind.DevicePaired, deviceName),
                                                          SecurityChangeCause.DeviceForgotten =>
                                                              new(SecurityAnnouncementKind.DeviceForgotten, deviceName),
                                                          SecurityChangeCause.AllDevicesForgotten =>
                                                              new(SecurityAnnouncementKind.AllDevicesForgotten),
                                                          _ => new(SecurityAnnouncementKind.None)
                                                      };
}

internal enum DeviceFocusTargetKind
{
    RemainingDevice,
    PairAnother,
    PairingCode
}

internal readonly record struct DeviceFocusTarget(DeviceFocusTargetKind Kind, int DeviceIndex = -1)
{
    public static DeviceFocusTarget AfterRemoval(int removedIndex, int remainingDeviceCount,
                                                 bool pairingPanelExpanded)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(removedIndex);
        ArgumentOutOfRangeException.ThrowIfNegative(remainingDeviceCount);
        if (remainingDeviceCount > 0)
            return new(DeviceFocusTargetKind.RemainingDevice,
                Math.Min(removedIndex, remainingDeviceCount - 1));
        return new(pairingPanelExpanded
            ? DeviceFocusTargetKind.PairingCode
            : DeviceFocusTargetKind.PairAnother);
    }

    public static DeviceFocusTarget AfterForgetAll() =>
        new(DeviceFocusTargetKind.PairingCode);
}

internal sealed record MotionPolicy(bool AnimationsEnabled, bool TransparencyEnabled, bool HighContrast)
{
    public bool MaterialsEnabled => TransparencyEnabled && !HighContrast;
    public bool SpatialMotionEnabled => AnimationsEnabled;
    public bool ReflowMotionEnabled => AnimationsEnabled;
    public bool OpacityFeedbackEnabled => FeedbackDurationMs > 0;
    public int FeedbackDurationMs => AnimationsEnabled
        ? MotionTokens.StatusCrossfadeMs
        : MotionTokens.ReducedFeedbackMs;
}

internal readonly record struct CubicBezierToken(double X1, double Y1, double X2, double Y2);

internal static class MotionTokens
{
    public const int SmallFeedbackMs = 120;
    public const int FeedbackHoldMs = 1200;
    public const int StatusCrossfadeMs = 180;
    public const int ReducedFeedbackMs = 160;
    public const int ProblemReflowMs = 180;
    public const double EaseOutX1 = 0.23;
    public const double EaseOutY1 = 1.0;
    public const double EaseOutX2 = 0.32;
    public const double EaseOutY2 = 1.0;
    public const double EaseInOutX1 = 0.77;
    public const double EaseInOutY1 = 0.0;
    public const double EaseInOutX2 = 0.175;
    public const double EaseInOutY2 = 1.0;
    public static readonly CubicBezierToken EaseOut = new(
        EaseOutX1, EaseOutY1, EaseOutX2, EaseOutY2);
    public static readonly CubicBezierToken EaseInOut = new(
        EaseInOutX1, EaseInOutY1, EaseInOutX2, EaseInOutY2);
}

internal readonly record struct PresentationGeneration(long Value, CancellationToken Token);

internal sealed class PresentationGenerationCoordinator : IDisposable
{
    private long generation;
    private CancellationTokenSource? current;
    private bool disposed;

    public PresentationGeneration Begin()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        CancelCurrent();
        current = new CancellationTokenSource();
        return new(++generation, current.Token);
    }

    public bool IsCurrent(PresentationGeneration candidate) =>
        !disposed
        && current is not null
        && candidate.Value == generation
        && candidate.Token == current.Token
        && !candidate.Token.IsCancellationRequested;

    public void Complete(PresentationGeneration candidate)
    {
        if (!IsCurrent(candidate)) return;
        current!.Dispose();
        current = null;
    }

    public void CancelCurrent()
    {
        if (current is null) return;
        current.Cancel();
        current.Dispose();
        current = null;
    }

    public void Dispose()
    {
        if (disposed) return;
        CancelCurrent();
        disposed = true;
    }
}

internal readonly record struct ProblemPresentationTarget(bool Visible, double Opacity)
{
    public static ProblemPresentationTarget FromProblemCount(int problemCount)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(problemCount);
        return problemCount == 0 ? new(false, 0) : new(true, 1);
    }
}

internal static class NetworkAddressPresentation
{
    public static string Format(string address, int port) =>
        port > 0 ? $"{address}:{port}" : address;
}

internal sealed class CopyFeedbackCoordinator : IDisposable
{
    private readonly Dictionary<object, CancellationTokenSource> active = new();

    public CancellationToken Begin(object key)
    {
        if (active.Remove(key, out CancellationTokenSource? previous))
        {
            previous.Cancel();
            previous.Dispose();
        }
        CancellationTokenSource current = new();
        active[key] = current;
        return current.Token;
    }

    public bool IsCurrent(object key, CancellationToken token) =>
        active.TryGetValue(key, out CancellationTokenSource? current) && current.Token == token;

    public void Complete(object key, CancellationToken token)
    {
        if (!IsCurrent(key, token) || !active.Remove(key, out CancellationTokenSource? current)) return;
        current.Dispose();
    }

    public void Dispose()
    {
        foreach (CancellationTokenSource source in active.Values)
        {
            source.Cancel();
            source.Dispose();
        }
        active.Clear();
    }
}
