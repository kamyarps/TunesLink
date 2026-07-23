namespace TunesLinkBridge;

internal sealed record BridgeLaunchOptions(
    int Port,
    int DiscoveryPort,
    bool Headless,
    bool Demo,
    bool Background,
    string? ForcedPairCode,
    string? ConfigDirectory,
    bool LegacyState,
    bool PairedPreview,
    bool VerifyLayout,
    string? UiState,
    string? Viewport,
    double TextScale,
    string? SnapshotPath)
{
    public static BridgeLaunchOptions Parse(string[] args)
    {
        string? snapshot = ValueAfter(args, "--snapshot");
        string? uiState = ValueAfter(args, "--ui-state")?.ToLowerInvariant();
        if (uiState is not null && uiState is not ("unpaired" or "paired" or "network-error"
                or "itunes-error" or "both-errors" or "runtime-unavailable" or "long-name"
                or "two-phones"))
            throw new ArgumentException("UI state must be unpaired, paired, two-phones, network-error, iTunes-error, both-errors, runtime-unavailable, or long-name.");
        double textScale = DoubleAfter(args, "--text-scale", 1.0);
        if (textScale is not (1.0 or 1.5 or 2.0))
            throw new ArgumentException("Text scale must be 1.0, 1.5, or 2.0.");
        bool preview = args.Contains("--paired-preview", StringComparer.OrdinalIgnoreCase)
            || args.Contains("--verify-layout", StringComparer.OrdinalIgnoreCase)
            || snapshot is not null || uiState is not null;
        bool demo = args.Contains("--demo", StringComparer.OrdinalIgnoreCase) || preview;
        string? directory = demo
            ? Path.Combine(Path.GetTempPath(), "TunesLink-winui-demo-" + Environment.ProcessId)
            : ValueAfter(args, "--config-directory");
        return new(
            IntAfter(args, "--port", preview ? 0 : BridgeProtocol.DefaultPort),
            IntAfter(args, "--discovery-port", preview ? 0 : BridgeProtocol.DefaultDiscoveryPort),
            args.Contains("--headless", StringComparer.OrdinalIgnoreCase),
            demo,
            args.Contains("--background", StringComparer.OrdinalIgnoreCase),
            ValueAfter(args, "--pair-code") ?? (demo ? "483921" : null),
            directory,
            args.Contains("--legacy-state", StringComparer.OrdinalIgnoreCase),
            args.Contains("--paired-preview", StringComparer.OrdinalIgnoreCase),
            args.Contains("--verify-layout", StringComparer.OrdinalIgnoreCase),
            uiState,
            ValueAfter(args, "--viewport"),
            textScale,
            snapshot);
    }

    private static int IntAfter(string[] args, string name, int fallback) =>
        int.TryParse(ValueAfter(args, name), out int parsed) ? parsed : fallback;

    private static double DoubleAfter(string[] args, string name, double fallback) =>
        double.TryParse(ValueAfter(args, name), System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out double parsed) ? parsed : fallback;

    private static string? ValueAfter(string[] args, string name)
    {
        for (int index = 0; index < args.Length - 1; index++)
            if (string.Equals(args[index], name, StringComparison.OrdinalIgnoreCase))
                return args[index + 1];
        return null;
    }
}

internal sealed class BridgeRuntime : IDisposable
{
    private readonly IMediaController media;
    private bool disposed;

    public BridgeRuntime(BridgeLaunchOptions launch)
    {
        Options = new BridgeOptions(
            launch.Port,
            launch.DiscoveryPort,
            launch.Headless,
            launch.Demo,
            launch.Background,
            launch.ForcedPairCode,
            launch.ConfigDirectory,
            launch.LegacyState);
        Security = new BridgeSecurity(launch.ConfigDirectory, launch.ForcedPairCode);
        if (launch.PairedPreview || launch.UiState is "paired" or "two-phones" or "network-error"
                or "itunes-error" or "both-errors" or "long-name")
        {
            _ = Security.TryPair("483921", "d64b17b7-2dc7-49fc-8141-7f94d41661cf",
                launch.UiState == "long-name"
                    ? "A very long Android phone name used to verify responsive device actions"
                    : "Pixel 8", out _);
            if (launch.UiState == "two-phones")
                _ = Security.TryPair(Security.PairCode,
                    "f75cc107-ce73-457c-9fa2-bdc89134552e", "Galaxy S26", out _);
        }
        BridgeTlsIdentity? identity = null;
        IMediaController? controller = null;
        PlaybackStateHub? stateHub = null;
        NetworkAddressSelector? addressSelector = null;
        BridgeServer? server = null;
        try
        {
            Identity = identity = new BridgeTlsIdentity(launch.ConfigDirectory);
            media = controller = launch.Demo ? new DemoController() : new IsolatedItunesController();
            StateHub = stateHub = new PlaybackStateHub(media);
            AddressSelector = addressSelector = new NetworkAddressSelector();
            Server = server = new BridgeServer(
                Security, Identity, media, StateHub, Options, AddressSelector);
            Preferences = new BridgePreferences(launch.ConfigDirectory);
        }
        catch
        {
            server?.Dispose();
            addressSelector?.Dispose();
            stateHub?.Dispose();
            controller?.Dispose();
            identity?.Dispose();
            throw;
        }
    }

    public BridgeOptions Options { get; }
    public BridgeSecurity Security { get; }
    public BridgeTlsIdentity Identity { get; }
    public PlaybackStateHub StateHub { get; }
    public NetworkAddressSelector AddressSelector { get; }
    public BridgeServer Server { get; }
    public BridgePreferences Preferences { get; }

    public void Start() => Server.Start();

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        Server.Dispose();
        AddressSelector.Dispose();
        StateHub.Dispose();
        media.Dispose();
        Identity.Dispose();
    }
}
