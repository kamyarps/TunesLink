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
    Paired
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
                UiStrings.Get("HeroPairTitle", "Welcome to TunesLink.\nLet’s pair your phone."),
                UiStrings.Get("HeroPairDetail",
                    "Open TunesLink on your Android phone and enter the pairing code below."),
                true);
        return new(HeroMode.Paired,
            pairedPhoneCount == 1
                ? UiStrings.Get("HeroReadyTitle", "Your phone is paired.")
                : UiStrings.Get("HeroReadyTitleMultiple", "Your phones are paired."),
            pairedPhoneCount == 1
                ? UiStrings.Get("HeroReadyDetail", "Control iTunes on this PC wirelessly from your phone.")
                : UiStrings.Get("HeroReadyMultipleDetail", "Control iTunes on this PC wirelessly from your phones."),
            userExpandedPairing && pairedPhoneCount < BridgeSecurity.MaxPairedDevices);
    }
}

/// <summary>
/// Tracks whether the pairing code is disclosed. The first-run expansion is automatic; only an
/// explicit "Pair another phone" opens it once a phone is paired, and any newly paired phone
/// closes a disclosure the user had opened, so the code never lingers on screen.
/// </summary>
internal sealed class PairingDisclosure
{
    private int? lastDeviceCount;

    public bool UserExpanded { get; private set; }

    public void SetUserExpanded(bool expanded) => UserExpanded = expanded;

    public HeroPresentation Update(int pairedPhoneCount)
    {
        if (lastDeviceCount is int previous && pairedPhoneCount > previous) UserExpanded = false;
        lastDeviceCount = pairedPhoneCount;
        return HeroPresentation.Create(pairedPhoneCount, UserExpanded);
    }
}

internal static class PairingWindowPolicy
{
    /// <summary>
    /// Phones may pair only while the six-digit code is actually in front of the user: the
    /// window is shown and not minimized, and the pairing panel is expanded.
    /// </summary>
    public static bool AcceptsPairing(bool windowShown, bool minimized, bool pairingPanelVisible) =>
        windowShown && !minimized && pairingPanelVisible;
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
    AllDevicesForgotten,
    PairCodeReplacedAfterFailedAttempts
}

internal enum SecurityAnnouncementKind
{
    None,
    PairingCodeRotated,
    NewPairingCodeGenerated,
    DevicePaired,
    DeviceForgotten,
    AllDevicesForgotten,
    PairingCodeReplaced
}

/// <summary>What the window last showed of the pairing state.</summary>
internal readonly record struct SecuritySnapshot(
    int DeviceCount,
    DateTimeOffset? NewestPairedAt,
    string PairCode,
    DateTimeOffset PairCodeExpiresAt);

internal static class SecurityChangeInference
{
    /// <summary>
    /// Explains a change the server raised on its own thread by comparing what the window last
    /// showed with the current state: a new or re-paired phone, the ten-minute rotation, or a
    /// replacement after too many wrong codes (the only other way the code changes early).
    /// </summary>
    public static SecurityChangeCause Infer(SecuritySnapshot previous, SecuritySnapshot current,
                                           DateTimeOffset now)
    {
        if (current.DeviceCount > previous.DeviceCount
            || current.NewestPairedAt > previous.NewestPairedAt)
            return SecurityChangeCause.DevicePaired;
        if (!string.Equals(current.PairCode, previous.PairCode, StringComparison.Ordinal))
            return now >= previous.PairCodeExpiresAt
                ? SecurityChangeCause.AutomaticPairCodeRotation
                : SecurityChangeCause.PairCodeReplacedAfterFailedAttempts;
        return SecurityChangeCause.InitialRefresh;
    }
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
                                                          SecurityChangeCause.PairCodeReplacedAfterFailedAttempts when pairingPanelVisible =>
                                                              new(SecurityAnnouncementKind.PairingCodeReplaced),
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

internal static class NetworkAddressPresentation
{
    public static string Format(string address, int port) =>
        port > 0 ? $"{address}:{port}" : address;

    /// <summary>Localized help for the address card; Core's diagnostic text stays in the log.</summary>
    public static string Help(NetworkAddressSelection selection) => selection.Address is null
        ? UiStrings.Get("PrivateAddressUnavailableHelp",
            "No private IPv4 network is available. Check VPN, virtual adapters, and Windows Firewall private-network access.")
        : UiStrings.Format("AddressAdapterHelp", "Using {0} ({1})",
            selection.Adapter ?? "", selection.Address);
}

internal static class TimePhrases
{
    public static string RelativeLastUsed(TimeSpan elapsed)
    {
        if (elapsed < TimeSpan.FromMinutes(2)) return UiStrings.Get("UsedJustNow", "Used just now");
        if (elapsed < TimeSpan.FromHours(1))
            return UiStrings.Format("UsedMinutesAgo", "Used {0} minutes ago", (int)elapsed.TotalMinutes);
        if (elapsed < TimeSpan.FromDays(1))
        {
            int hours = Math.Max(1, (int)elapsed.TotalHours);
            return hours == 1
                ? UiStrings.Get("UsedOneHourAgo", "Used 1 hour ago")
                : UiStrings.Format("UsedHoursAgo", "Used {0} hours ago", hours);
        }
        int days = Math.Max(1, (int)elapsed.TotalDays);
        return days == 1
            ? UiStrings.Get("UsedOneDayAgo", "Used 1 day ago")
            : UiStrings.Format("UsedDaysAgo", "Used {0} days ago", days);
    }

    public static string ExpiryAccessibleName(int totalSeconds)
    {
        int minutes = Math.Max(0, totalSeconds) / 60;
        int seconds = Math.Max(0, totalSeconds) % 60;
        string minutePart = minutes == 1
            ? UiStrings.Get("DurationOneMinute", "1 minute")
            : UiStrings.Format("DurationMinutes", "{0} minutes", minutes);
        string secondPart = seconds == 1
            ? UiStrings.Get("DurationOneSecond", "1 second")
            : UiStrings.Format("DurationSeconds", "{0} seconds", seconds);
        string duration = minutes == 0 ? secondPart
            : seconds == 0 ? minutePart
            : UiStrings.Format("DurationMinutesAndSeconds", "{0} and {1}", minutePart, secondPart);
        return UiStrings.Format("PairingCodeExpiryAccessibleName", "Pairing code expires in {0}", duration);
    }
}

internal enum StartupFailureKind
{
    PortInUse,
    PortBlocked,
    NetworkUnavailable,
    SettingsAccessDenied,
    SettingsUnavailable,
    SecurityIdentity,
    Unknown
}

internal static class StartupFailurePresentation
{
    public static StartupFailureKind Classify(Exception exception) => exception switch
    {
        System.Net.Sockets.SocketException
        {
            SocketErrorCode: System.Net.Sockets.SocketError.AddressAlreadyInUse
        } => StartupFailureKind.PortInUse,
        System.Net.Sockets.SocketException
        {
            SocketErrorCode: System.Net.Sockets.SocketError.AccessDenied
        } => StartupFailureKind.PortBlocked,
        System.Net.Sockets.SocketException => StartupFailureKind.NetworkUnavailable,
        UnauthorizedAccessException => StartupFailureKind.SettingsAccessDenied,
        System.Security.Cryptography.CryptographicException => StartupFailureKind.SecurityIdentity,
        IOException => StartupFailureKind.SettingsUnavailable,
        _ => StartupFailureKind.Unknown
    };

    /// <summary>A plain-language reason; raw exception messages are English and technical.</summary>
    public static string Detail(StartupFailureKind kind, int port)
    {
        int shownPort = port > 0 ? port : BridgeProtocol.DefaultPort;
        return kind switch
        {
            StartupFailureKind.PortInUse => UiStrings.Format("StartupFailurePortInUse",
                "Another app is using TunesLink’s network port {0}. Close it, then try again.", shownPort),
            StartupFailureKind.PortBlocked => UiStrings.Format("StartupFailurePortBlocked",
                "Windows didn’t let TunesLink use network port {0}. Another app or a system port reservation may be holding it. Close other network apps or restart this PC, then try again.",
                shownPort),
            StartupFailureKind.NetworkUnavailable => UiStrings.Get("StartupFailureNetwork",
                "TunesLink couldn’t start its network connection. Check that this PC is connected to a network, then try again."),
            StartupFailureKind.SettingsAccessDenied => UiStrings.Get("StartupFailureAccessDenied",
                "TunesLink doesn’t have permission to open its settings folder. Check that security software isn’t blocking it, then try again."),
            StartupFailureKind.SettingsUnavailable => UiStrings.Get("StartupFailureSettings",
                "TunesLink couldn’t read its settings. Another program may be using them. Try again in a moment."),
            StartupFailureKind.SecurityIdentity => UiStrings.Get("StartupFailureIdentity",
                "TunesLink couldn’t load its security certificate. Try again. If this keeps happening, restart this PC."),
            _ => UiStrings.Get("StartupFailureUnknown",
                "Something unexpected stopped TunesLink from starting. Try again.")
        };
    }
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
