using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace TunesLinkBridge;

internal sealed record NetworkAddressCandidate(
    string InterfaceId,
    string InterfaceName,
    string InterfaceDescription,
    NetworkInterfaceType InterfaceType,
    IPAddress Address,
    bool HasGateway,
    bool DnsEligible);

internal sealed record NetworkAddressSelection(
    string? Address,
    string? Adapter,
    string Diagnostic);

internal sealed class NetworkAddressSelector : IDisposable
{
    private static readonly string[] VirtualMarkers =
    [
        "vpn", "tunnel", "hyper-v", "docker", "wsl", "vmware", "virtualbox",
        "tap", "tailscale", "zerotier", "loopback"
    ];

    private readonly TimeProvider timeProvider;
    private readonly object gate = new();
    private readonly System.Threading.Timer debounce;
    private IPAddress? recentRouteAddress;
    private DateTimeOffset recentRouteExpiresAt;
    private NetworkAddressSelection current = new(null, null,
        "No private IPv4 network is available. Check VPN and firewall settings.");
    private bool disposed;

    public NetworkAddressSelector(TimeProvider? timeProvider = null)
    {
        this.timeProvider = timeProvider ?? TimeProvider.System;
        debounce = new(_ => Refresh(), null, Timeout.Infinite, Timeout.Infinite);
        NetworkChange.NetworkAddressChanged += NetworkAddressChanged;
        Refresh();
    }

    public event Action? Changed;

    public NetworkAddressSelection Current
    {
        get { lock (gate) return current; }
    }

    public void ObserveAuthenticatedClient(IPAddress remote)
    {
        try
        {
            using Socket route = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            route.Connect(new IPEndPoint(remote, 9));
            if (route.LocalEndPoint is IPEndPoint local)
            {
                lock (gate)
                {
                    recentRouteAddress = local.Address;
                    recentRouteExpiresAt = timeProvider.GetUtcNow().AddMinutes(10);
                }
            }
        }
        catch { }
        ScheduleRefresh();
    }

    public void Refresh()
    {
        if (disposed) return;
        IPAddress? routeHint;
        lock (gate)
        {
            if (recentRouteExpiresAt <= timeProvider.GetUtcNow()) recentRouteAddress = null;
            routeHint = recentRouteAddress;
        }
        NetworkAddressSelection selected = Select(EnumerateCandidates(), routeHint);
        bool changed;
        lock (gate)
        {
            changed = current != selected;
            current = selected;
        }
        if (changed) Changed?.Invoke();
    }

    internal static NetworkAddressSelection Select(
        IEnumerable<NetworkAddressCandidate> candidates, IPAddress? routeHint = null)
    {
        NetworkAddressCandidate? selected = candidates
            .Where(candidate => candidate.Address.AddressFamily == AddressFamily.InterNetwork
                && BridgeServer.IsLocalAddress(candidate.Address)
                && !IPAddress.IsLoopback(candidate.Address))
            .OrderByDescending(candidate => Score(candidate, routeHint))
            .ThenBy(candidate => candidate.InterfaceId, StringComparer.Ordinal)
            .ThenBy(candidate => candidate.Address.ToString(), StringComparer.Ordinal)
            .FirstOrDefault();
        return selected is null
            ? new NetworkAddressSelection(null, null,
                "No private IPv4 network is available. Check VPN, virtual adapters, and Windows Firewall private-network access.")
            : new NetworkAddressSelection(selected.Address.ToString(), selected.InterfaceName,
                $"Using {selected.InterfaceName} ({selected.Address})");
    }

    private static int Score(NetworkAddressCandidate candidate, IPAddress? routeHint)
    {
        int score = routeHint is not null && candidate.Address.Equals(routeHint) ? 10_000 : 0;
        if (candidate.HasGateway) score += 400;
        if (candidate.InterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet
            or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetFx
            or NetworkInterfaceType.FastEthernetT) score += 200;
        byte[] bytes = candidate.Address.GetAddressBytes();
        if (bytes[0] == 10 || bytes[0] == 192 && bytes[1] == 168
            || bytes[0] == 172 && bytes[1] is >= 16 and <= 31) score += 100;
        else if (bytes[0] == 100 && bytes[1] is >= 64 and <= 127) score += 20;
        else if (bytes[0] == 169 && bytes[1] == 254) score -= 100;
        if (candidate.DnsEligible) score += 10;
        string identity = (candidate.InterfaceName + " " + candidate.InterfaceDescription).ToLowerInvariant();
        if (candidate.InterfaceType is NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp
            || VirtualMarkers.Any(identity.Contains)) score -= 500;
        return score;
    }

    private static IEnumerable<NetworkAddressCandidate> EnumerateCandidates()
    {
        foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces()
                     .Where(item => item.OperationalStatus == OperationalStatus.Up
                         && item.NetworkInterfaceType != NetworkInterfaceType.Loopback))
        {
            IPInterfaceProperties properties;
            try { properties = adapter.GetIPProperties(); }
            catch { continue; }
            bool gateway = properties.GatewayAddresses.Any(item =>
                item.Address.AddressFamily == AddressFamily.InterNetwork
                && !item.Address.Equals(IPAddress.Any));
            foreach (UnicastIPAddressInformation unicast in properties.UnicastAddresses)
            {
                bool dnsEligible = OperatingSystem.IsWindows() && unicast.IsDnsEligible;
                yield return new NetworkAddressCandidate(adapter.Id, adapter.Name, adapter.Description,
                    adapter.NetworkInterfaceType, unicast.Address, gateway, dnsEligible);
            }
        }
    }

    private void NetworkAddressChanged(object? sender, EventArgs eventArgs) => ScheduleRefresh();

    private void ScheduleRefresh()
    {
        if (!disposed) debounce.Change(500, Timeout.Infinite);
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        NetworkChange.NetworkAddressChanged -= NetworkAddressChanged;
        debounce.Dispose();
    }
}
