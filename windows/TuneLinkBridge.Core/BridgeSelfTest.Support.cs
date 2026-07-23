using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text.Json;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private sealed class FailingPersistence : IAtomicFilePersistence
    {
        public void WriteText(string path, string contents) =>
            throw new IOException("Injected persistence failure");
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan duration) => current += duration;
    }

    private sealed class CountingMediaController : IMediaController
    {
        private int stateRequests;
        public int StateRequests => Volatile.Read(ref stateRequests);

        public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default)
        {
            int request = Interlocked.Increment(ref stateRequests);
            return Task.FromResult(new PlaybackState(true, true, "State " + request,
                "Test", "Test", 100, request, 50, "", "", false, "off"));
        }

        public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
            CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query,
            int offset, int limit, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
            int offset, int limit, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task PlayTrackAsync(PlaybackSelection selection,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
        public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
            CancellationToken cancellationToken = default) => throw new NotSupportedException();
        public void Dispose() { }
    }

    private static int FreePort()
    {
        TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static int FreeUdpPort()
    {
        using UdpClient client = new(new IPEndPoint(IPAddress.Loopback, 0));
        return ((IPEndPoint)client.Client.LocalEndPoint!).Port;
    }

    private static Task<HttpResponseMessage> PostJsonAsync(HttpClient client, string path, object body)
    {
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(body);
        ByteArrayContent content = new(bytes);
        content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        return client.PostAsync(path, content);
    }

    private static void Ensure(bool condition, string step)
    {
        if (!condition) throw new InvalidOperationException("Self-test failed: " + step);
    }
}
