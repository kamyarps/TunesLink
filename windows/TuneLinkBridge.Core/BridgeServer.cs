using System.Diagnostics;
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
    // A phone runs up to eight requests at once plus its state stream, and a request it abandons
    // keeps its slot until the bridge notices. The per-address limit leaves room for both.
    private const int MaxConnections = 32;
    private const int MaxConnectionsPerAddress = 16;
    private const int MaxHeaderBytes = 16 * 1024;
    private const int MaxHeaderCount = 64;
    private const int MaxHeaderLineBytes = 4 * 1024;
    private const int MaxTargetBytes = 2 * 1024;
    private const int MaxBodyBytes = 32 * 1024;
    private const int MaxArtworkResponseBytes = 2 * 1024 * 1024;
    private const int MaxRequestsPerConnection = 64;
    private static readonly TimeSpan RejectionTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan ErrorWriteTimeout = TimeSpan.FromSeconds(5);

    private sealed record HttpRequest(string Method, string Target, string Version,
        Dictionary<string, string> Headers, byte[] Body);
    private sealed class BadHttpRequestException(string message) : Exception(message);

    private sealed record PairRequest(string? Code, string? ClientId, string? DeviceName);
    private sealed record CommandRequest(string? Command, double? Value);
    private sealed record PlayRequest(
        string? TrackId,
        string? CollectionKind = null,
        string? CollectionId = null,
        long? Sequence = null);
    private sealed record CancelPlayRequest(long Sequence);

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };
    private static readonly AsyncLocal<ResponseConnection?> ResponseKeepAlive = new();

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
    private readonly object pairingGate = new();
    private readonly PlaybackRequests playbackRequests;
    private TcpListener? tcp;
    private UdpClient? udp;
    private Task? tcpLoop;
    private Task? udpLoop;
    private bool disposed;

    /// <summary>False when the discovery port could not be opened; HTTPS still serves phones.</summary>
    public bool DiscoveryAvailable { get; private set; }

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
        playbackRequests = new PlaybackRequests(security.IsAuthorized);
    }

    public void Start()
    {
        BridgeDiagnostics.RecordEvent("bridge.start", BridgeProtocol.ProductVersion,
            options.ConfigDirectory);
        tcp = new TcpListener(IPAddress.Any, options.Port);
        tcp.Server.ExclusiveAddressUse = true;
        tcp.Start(32);
        tcpLoop = Task.Run(AcceptLoopAsync);

        if (options.DiscoveryPort > 0)
        {
            // Discovery is a convenience; a phone that knows the address still connects. Another
            // program holding the port must not take the whole bridge down.
            try
            {
                udp = new UdpClient(AddressFamily.InterNetwork);
                udp.Client.ExclusiveAddressUse = true;
                if (OperatingSystem.IsWindows())
                {
                    // Without this, an ICMP port-unreachable from a departed phone surfaces as a
                    // connection reset on the next receive.
                    const int SioUdpConnectionReset = -1744830452;
                    udp.Client.IOControl(SioUdpConnectionReset, [0, 0, 0, 0], null);
                }
                udp.Client.Bind(new IPEndPoint(IPAddress.Any, options.DiscoveryPort));
                udpLoop = Task.Run(DiscoveryLoopAsync);
                DiscoveryAvailable = true;
            }
            catch (SocketException exception)
            {
                BridgeDiagnostics.Record("network.discovery.bind", exception, options.ConfigDirectory);
                udp?.Dispose();
                udp = null;
            }
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
            bool admitted = TryEnterAddress(remote);
            NetworkStream stream = client.GetStream();
            SslStream? secureStream = null;
            // Canceled when the phone closes the connection while a long request is running.
            using CancellationTokenSource disconnected = new();
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
                if (!admitted)
                {
                    await RejectBusyAsync(secureStream).ConfigureAwait(false);
                    return;
                }
                HttpConnectionReader reader = new();
                long connectionStarted = Stopwatch.GetTimestamp();
                for (int requestCount = 0; requestCount < MaxRequestsPerConnection; requestCount++)
                {
                    TimeSpan idleRemaining = BridgeProtocol.ConnectionLifetime
                        - Stopwatch.GetElapsedTime(connectionStarted);
                    if (idleRemaining <= TimeSpan.Zero) break;
                    using CancellationTokenSource idleTimeout =
                        CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
                    idleTimeout.CancelAfter(idleRemaining < BridgeProtocol.RequestIdleTimeout
                        ? idleRemaining : BridgeProtocol.RequestIdleTimeout);
                    HttpRequest? request = await reader.ReadAsync(secureStream, idleTimeout.Token)
                        .ConfigureAwait(false);
                    if (request is null) break;
                    using CancellationTokenSource requestTimeout =
                        CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token,
                            disconnected.Token);
                    requestTimeout.CancelAfter(BridgeProtocol.RequestTimeout);
                    bool keepAliveRequested = request.Version == "HTTP/1.1"
                        && (!request.Headers.TryGetValue("Connection", out string? connection)
                            || !connection.Equals("close", StringComparison.OrdinalIgnoreCase));
                    ResponseConnection response = new(connectionStarted,
                        MaxRequestsPerConnection - requestCount - 1, keepAliveRequested);
                    ResponseKeepAlive.Value = response;

                    string path = request.Target.Split('?', 2)[0];
                    if (IsLongRequest(request.Method, path))
                        reader.WatchForDisconnect(secureStream, disconnected);
                    if (!options.LegacyState && request.Method == "GET" && path == "/api/state/stream")
                    {
                        response.Closing = true;
                        string? bearer = BearerToken(request.Headers);
                        if (!security.ValidateToken(bearer))
                            await WriteErrorAsync(secureStream, ApiError.NotPaired,
                                requestTimeout.Token).ConfigureAwait(false);
                        else
                        {
                            addressSelector?.ObserveAuthenticatedClient(remote);
                            using CancellationTokenSource streaming =
                                CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token,
                                    disconnected.Token);
                            await StreamStateAsync(secureStream, bearer!, streaming.Token)
                                .ConfigureAwait(false);
                        }
                        break;
                    }

                    try
                    {
                        await RouteAsync(secureStream, request, remote, requestTimeout.Token)
                            .ConfigureAwait(false);
                    }
                    catch (Exception exception) when (!cancellation.IsCancellationRequested
                        && !disconnected.IsCancellationRequested
                        && ApiErrorFor(exception) is { } failure)
                    {
                        // Timeouts and iTunes failures get an answer the phone can act on. The
                        // connection stays usable because the request was read completely.
                        if (failure == ApiError.BridgeError)
                        {
                            BridgeDiagnostics.Record("network.request", exception, options.ConfigDirectory);
                            response.Closing = true;
                        }
                        using CancellationTokenSource write =
                            CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
                        write.CancelAfter(ErrorWriteTimeout);
                        await WriteErrorAsync(secureStream, failure, write.Token).ConfigureAwait(false);
                    }
                    if (response.Closing) break;
                }
            }
            catch (BadHttpRequestException)
            {
                try
                {
                    if (secureStream?.IsAuthenticated == true)
                    {
                        ResponseKeepAlive.Value = null;
                        await WriteErrorAsync(secureStream,
                            new ApiError(400, "invalid_request", "Bad request"), cancellation.Token);
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
                        ResponseKeepAlive.Value = null;
                        await WriteErrorAsync(secureStream, ApiError.BridgeError, cancellation.Token);
                    }
                }
                catch { }
            }
            finally
            {
                secureStream?.Dispose();
                if (admitted) ExitAddress(remote);
            }
        }
    }

    // A phone over its connection allowance is told to retry rather than left with a reset.
    // The request is read first so closing the socket does not discard the response.
    private async Task RejectBusyAsync(Stream stream)
    {
        ResponseKeepAlive.Value = null;
        using CancellationTokenSource timeout =
            CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
        timeout.CancelAfter(RejectionTimeout);
        HttpRequest? request = await new HttpConnectionReader().ReadAsync(stream, timeout.Token)
            .ConfigureAwait(false);
        if (request is not null)
            await WriteErrorAsync(stream, ApiError.Busy, timeout.Token).ConfigureAwait(false);
    }

    // Requests that can wait on iTunes for a long time, and the state stream. For these the
    // bridge watches for the phone hanging up so abandoned work stops and frees its slot.
    private static bool IsLongRequest(string method, string path) =>
        (method == "GET" && path is "/api/library" or "/api/collections"
            or "/api/collection-albums" or "/api/artwork" or "/api/state/stream")
        || (method == "POST" && path == "/api/play");

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
