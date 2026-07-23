using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Globalization;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class BridgeServer : IDisposable
{
    private const int MaxConnections = 24;
    private const int MaxConnectionsPerAddress = 8;
    private const int MaxHeaderBytes = 16 * 1024;
    private const int MaxHeaderCount = 64;
    private const int MaxHeaderLineBytes = 4 * 1024;
    private const int MaxTargetBytes = 2 * 1024;
    private const int MaxBodyBytes = 32 * 1024;
    private const int MaxArtworkResponseBytes = 2 * 1024 * 1024;
    private const int MaxRequestsPerConnection = 64;

    private sealed record HttpRequest(string Method, string Target, string Version,
        Dictionary<string, string> Headers, byte[] Body);
    private sealed class BadHttpRequestException(string message) : Exception(message);

    private sealed record PairRequest(string? Code, string? ClientId, string? DeviceName);
    private sealed record CommandRequest(string? Command, double? Value);
    private sealed record PlayRequest(string? TrackId);

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };
    private static readonly AsyncLocal<bool> ResponseKeepAlive = new();

    private readonly BridgeSecurity security;
    private readonly BridgeTlsIdentity tlsIdentity;
    private readonly IMediaController media;
    private readonly PlaybackStateHub stateHub;
    private readonly NetworkAddressSelector? addressSelector;
    private readonly BridgeOptions options;
    private readonly CancellationTokenSource cancellation = new();
    private readonly SemaphoreSlim connectionSlots = new(MaxConnections, MaxConnections);
    private readonly HashSet<Task> activeClients = [];
    private readonly object clientsGate = new();
    private readonly Dictionary<string, int> activeConnectionsByAddress = new();
    private readonly object connectionsGate = new();
    private readonly PairingRateLimiter pairingRateLimiter;
    private TcpListener? tcp;
    private UdpClient? udp;
    private Task? tcpLoop;
    private Task? udpLoop;
    private bool disposed;

    internal int ActiveConnectionCountForTest
    {
        get
        {
            lock (connectionsGate) return activeConnectionsByAddress.Values.Sum();
        }
    }

    public BridgeServer(BridgeSecurity security, BridgeTlsIdentity tlsIdentity,
                        IMediaController media, PlaybackStateHub stateHub, BridgeOptions options,
                        NetworkAddressSelector? addressSelector = null,
                        TimeProvider? timeProvider = null)
    {
        this.security = security;
        this.tlsIdentity = tlsIdentity;
        this.media = media;
        this.stateHub = stateHub;
        this.options = options;
        this.addressSelector = addressSelector;
        pairingRateLimiter = new PairingRateLimiter(timeProvider);
    }

    public void Start()
    {
        tcp = new TcpListener(IPAddress.Any, options.Port);
        tcp.Server.ExclusiveAddressUse = true;
        tcp.Start(32);
        tcpLoop = Task.Run(AcceptLoopAsync);

        if (options.DiscoveryPort > 0)
        {
            udp = new UdpClient(AddressFamily.InterNetwork);
            udp.Client.ExclusiveAddressUse = true;
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, options.DiscoveryPort));
            udp.EnableBroadcast = true;
            udpLoop = Task.Run(DiscoveryLoopAsync);
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!cancellation.IsCancellationRequested && tcp is not null)
        {
            try
            {
                TcpClient client = await tcp.AcceptTcpClientAsync(cancellation.Token).ConfigureAwait(false);
                if (!connectionSlots.Wait(0))
                {
                    client.Dispose();
                    continue;
                }
                Task clientTask = HandleClientWithSlotAsync(client);
                TrackClient(clientTask);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (Exception exception) when (!cancellation.IsCancellationRequested)
            {
                BridgeDiagnostics.Record("network.accept", exception, options.ConfigDirectory);
                await Task.Delay(150, cancellation.Token).ConfigureAwait(false);
            }
        }
    }

    private async Task HandleClientWithSlotAsync(TcpClient client)
    {
        try { await HandleClientAsync(client).ConfigureAwait(false); }
        finally { connectionSlots.Release(); }
    }

    private async Task DiscoveryLoopAsync()
    {
        while (!cancellation.IsCancellationRequested && udp is not null)
        {
            try
            {
                UdpReceiveResult packet = await udp.ReceiveAsync(cancellation.Token).ConfigureAwait(false);
                if (!IsLocalAddress(packet.RemoteEndPoint.Address)) continue;
                string message = Encoding.ASCII.GetString(packet.Buffer);
                if (!string.Equals(message, "TunesLink_DISCOVER_V1", StringComparison.Ordinal)) continue;
                byte[] response = JsonSerializer.SerializeToUtf8Bytes(new
                {
                    protocol = BridgeProtocol.Id,
                    id = security.BridgeId,
                    name = ComputerName,
                    port = options.Port,
                    version = BridgeProtocol.ProductVersion,
                    tlsFingerprint = tlsIdentity.Fingerprint
                }, JsonOptions);
                await udp.SendAsync(response, packet.RemoteEndPoint, cancellation.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (Exception exception) when (!cancellation.IsCancellationRequested)
            {
                BridgeDiagnostics.Record("network.discovery", exception, options.ConfigDirectory);
                await Task.Delay(150, cancellation.Token).ConfigureAwait(false);
            }
        }
    }

    private void TrackClient(Task task)
    {
        lock (clientsGate) activeClients.Add(task);
        _ = task.ContinueWith(completed =>
        {
            lock (clientsGate) activeClients.Remove(completed);
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        using (client)
        {
            client.NoDelay = true;
            IPAddress remote = ((IPEndPoint?)client.Client.RemoteEndPoint)?.Address ?? IPAddress.None;
            if (!IsLocalAddress(remote)) return;
            if (!TryEnterAddress(remote)) return;
            NetworkStream stream = client.GetStream();
            SslStream? secureStream = null;
            try
            {
                using CancellationTokenSource handshake = CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
                handshake.CancelAfter(BridgeProtocol.HandshakeTimeout);
                secureStream = new SslStream(stream, leaveInnerStreamOpen: false);
                SslServerAuthenticationOptions authentication = new()
                {
                    ServerCertificate = tlsIdentity.Certificate,
                    ClientCertificateRequired = false,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck
                };
                await secureStream.AuthenticateAsServerAsync(authentication, handshake.Token)
                    .ConfigureAwait(false);
                HttpConnectionReader reader = new();
                DateTimeOffset connectionExpiresAt = DateTimeOffset.UtcNow.Add(BridgeProtocol.ConnectionLifetime);
                for (int requestCount = 0; requestCount < MaxRequestsPerConnection; requestCount++)
                {
                    TimeSpan remaining = connectionExpiresAt - DateTimeOffset.UtcNow;
                    if (remaining <= TimeSpan.Zero) break;
                    using CancellationTokenSource requestTimeout =
                        CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
                    requestTimeout.CancelAfter(remaining < BridgeProtocol.RequestIdleTimeout
                        ? remaining : BridgeProtocol.RequestIdleTimeout);
                    HttpRequest? request = await reader.ReadAsync(secureStream, requestTimeout.Token)
                        .ConfigureAwait(false);
                    if (request is null) break;
                    bool keepAlive = request.Version == "HTTP/1.1"
                        && (!request.Headers.TryGetValue("Connection", out string? connection)
                            || !connection.Equals("close", StringComparison.OrdinalIgnoreCase))
                        && requestCount < MaxRequestsPerConnection - 1;
                    ResponseKeepAlive.Value = keepAlive;

                    string path = request.Target.Split('?', 2)[0];
                    if (!options.LegacyState && request.Method == "GET" && path == "/api/state/stream")
                    {
                        string? bearer = BearerToken(request.Headers);
                        if (!security.ValidateToken(bearer))
                            await WriteJsonAsync(secureStream, 401, new { error = "Not paired" },
                                requestTimeout.Token).ConfigureAwait(false);
                        else
                        {
                            addressSelector?.ObserveAuthenticatedClient(remote);
                            await StreamStateAsync(secureStream, bearer!, cancellation.Token)
                                .ConfigureAwait(false);
                        }
                        break;
                    }

                    await RouteAsync(secureStream, request, remote, requestTimeout.Token)
                        .ConfigureAwait(false);
                    if (!keepAlive) break;
                }
            }
            catch (BadHttpRequestException)
            {
                try
                {
                    if (secureStream?.IsAuthenticated == true)
                    {
                        ResponseKeepAlive.Value = false;
                        await WriteJsonAsync(secureStream, 400,
                            new { error = "Bad request" }, cancellation.Token);
                    }
                }
                catch { }
            }
            catch (OperationCanceledException) { }
            catch (IOException) { }
            catch (Exception exception)
            {
                BridgeDiagnostics.Record("network.request", exception, options.ConfigDirectory);
                try
                {
                    if (secureStream?.IsAuthenticated == true)
                    {
                        ResponseKeepAlive.Value = false;
                        await WriteJsonAsync(secureStream, 500, new { error = "Bridge error" }, cancellation.Token);
                    }
                }
                catch { }
            }
            finally
            {
                secureStream?.Dispose();
                ExitAddress(remote);
            }
        }
    }

    private bool TryEnterAddress(IPAddress address)
    {
        string key = address.ToString();
        lock (connectionsGate)
        {
            activeConnectionsByAddress.TryGetValue(key, out int count);
            if (count >= MaxConnectionsPerAddress) return false;
            activeConnectionsByAddress[key] = count + 1;
            return true;
        }
    }

    private void ExitAddress(IPAddress address)
    {
        string key = address.ToString();
        lock (connectionsGate)
        {
            if (!activeConnectionsByAddress.TryGetValue(key, out int count)) return;
            if (count <= 1) activeConnectionsByAddress.Remove(key);
            else activeConnectionsByAddress[key] = count - 1;
        }
    }

    internal static bool IsLocalAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (IPAddress.IsLoopback(address) || address.IsIPv6LinkLocal) return true;
        byte[] bytes = address.GetAddressBytes();
        if (bytes.Length != 4) return false;
        return bytes[0] == 10
               || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
               || (bytes[0] == 192 && bytes[1] == 168)
               || (bytes[0] == 169 && bytes[1] == 254)
               || (bytes[0] == 100 && bytes[1] is >= 64 and <= 127);
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        cancellation.Cancel();
        try { tcp?.Stop(); } catch { }
        try { udp?.Dispose(); } catch { }
        Task[] loops = [tcpLoop ?? Task.CompletedTask, udpLoop ?? Task.CompletedTask];
        bool loopsStopped = WaitForShutdown(loops, TimeSpan.FromSeconds(2));
        Task[] clients;
        lock (clientsGate) clients = [.. activeClients];
        bool clientsStopped = WaitForShutdown(clients, TimeSpan.FromSeconds(2));
        if (loopsStopped && clientsStopped)
        {
            connectionSlots.Dispose();
            cancellation.Dispose();
        }
    }

    private static bool WaitForShutdown(Task[] tasks, TimeSpan timeout)
    {
        try { return Task.WaitAll(tasks, timeout); }
        catch { return tasks.All(task => task.IsCompleted); }
    }
}
