using System.Net;
using System.Globalization;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    public static async Task<int> RunAsync()
    {
        string directory = Path.Combine(Path.GetTempPath(), "TunesLink-selftest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            Console.WriteLine("self-test:safety");
            await TestSafetyRegressionsAsync(directory);
            Console.WriteLine("self-test:worker-isolation");
            await TestWorkerIsolationAsync();
            await TestWorkerFailureCategoriesAsync();
            Console.WriteLine("self-test:state-hub");
            await TestSharedStateHubAsync();
            TestPairCodeExpiry();
            TestPairingRateLimits();
            TestAddressSelection();
            int port = FreePort();
            int discoveryPort = FreeUdpPort();
            BridgeOptions options = new(port, discoveryPort, true, false, false, "123456", directory);
            BridgeSecurity security = new(directory, "123456");
            using BridgeTlsIdentity tlsIdentity = new(directory);
            using DemoController media = new();
            using PlaybackStateHub stateHub = new(media);
            using BridgeServer server = new(security, tlsIdentity, media, stateHub, options);
            server.Start();
            Console.WriteLine("self-test:protocol");
            TestExclusivePortOwnership(port, discoveryPort);
            if (OperatingSystem.IsWindows())
            {
                byte[] storedIdentity = File.ReadAllBytes(Path.Combine(directory,
                    "bridge-identity.pfx"));
                Ensure(storedIdentity.AsSpan().StartsWith("TunesLink-DPAPI-V1\n"u8),
                    "TLS private key protected with current-user DPAPI");
            }
            await TestDiscoveryAsync(discoveryPort);
            using HttpClientHandler handler = new();
            handler.ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null && tlsIdentity.Matches(certificate);
            using HttpClient client = new(handler) { BaseAddress = new Uri($"https://127.0.0.1:{port}") };

            HttpResponseMessage info = await client.GetAsync("/api/info");
            Ensure(info.StatusCode == HttpStatusCode.OK, "info endpoint");
            using JsonDocument infoJson = JsonDocument.Parse(await info.Content.ReadAsByteArrayAsync());
            Ensure(infoJson.RootElement.GetProperty("protocol").GetString() == BridgeProtocol.Id, "protocol");
            Ensure(infoJson.RootElement.GetProperty("tlsFingerprint").GetString() == tlsIdentity.Fingerprint,
                "TLS fingerprint");

            Ensure(await RawStatusAsync(port,
                    "GET /api/info HTTP/1.1\r\n\r\n") == HttpStatusCode.BadRequest,
                "HTTP/1.1 host header required");
            Ensure(await RawStatusAsync(port,
                    "POST /api/pair HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Length: 0\r\nContent-Length: 0\r\n\r\n")
                    == HttpStatusCode.BadRequest,
                "duplicate content length rejected");
            Ensure(await RawStatusAsync(port,
                    "POST /api/pair HTTP/1.1\r\nHost: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
                    == HttpStatusCode.BadRequest,
                "transfer encoding rejected");
            Ensure(await RawStatusAsync(port,
                    "POST /api/pair HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Length: 32769\r\n\r\n") == HttpStatusCode.BadRequest,
                "oversized body rejected before reading");
            Ensure(await RawStatusAsync(port,
                    "GET /" + new string('a', 2_048) +
                    " HTTP/1.1\r\nHost: localhost\r\n\r\n") == HttpStatusCode.BadRequest,
                "oversized request target rejected");
            Ensure(await RawStatusAsync(port,
                    "GET /api/info HTTP/1.1\r\nHost localhost\r\n\r\n")
                    == HttpStatusCode.BadRequest,
                "malformed header rejected");

            IReadOnlyList<HttpStatusCode> pipelined = await RawStatusesAsync(port, 2);
            Ensure(pipelined.Count == 2 && pipelined.All(status => status == HttpStatusCode.OK),
                "two pipelined requests on one TLS connection");
            IReadOnlyList<HttpStatusCode> requestLimit = await RawStatusesAsync(port, 64);
            Ensure(requestLimit.Count == 64 && requestLimit.All(status => status == HttpStatusCode.OK),
                "64 requests on one TLS connection");

            HttpResponseMessage denied = await client.GetAsync("/api/state");
            Ensure(denied.StatusCode == HttpStatusCode.Unauthorized, "unauthorized request");

            HttpResponseMessage invalidPair = await PostJsonAsync(client, "/api/pair",
                new { deviceName = "Missing code" });
            Ensure(invalidPair.StatusCode == HttpStatusCode.BadRequest, "malformed pairing request");

            HttpResponseMessage invalidCode = await PostJsonAsync(client, "/api/pair",
                new
                {
                    code = "12 3456",
                    clientId = "6a772ae7-5550-465d-a6a6-a48f8ccfef18",
                    deviceName = "Malformed code"
                });
            Ensure(invalidCode.StatusCode == HttpStatusCode.Forbidden, "strict pairing code format");

            HttpResponseMessage paired = await PostJsonAsync(client, "/api/pair",
                new
                {
                    code = "123456",
                    clientId = "11111111-1111-4111-8111-111111111111",
                    deviceName = "Self-test phone"
                });
            byte[] pairBody = await paired.Content.ReadAsByteArrayAsync();
            Ensure(paired.StatusCode == HttpStatusCode.OK,
                "pairing (" + (int)paired.StatusCode + ": " + System.Text.Encoding.UTF8.GetString(pairBody) + ")");
            using JsonDocument pairJson = JsonDocument.Parse(pairBody);
            string token = pairJson.RootElement.GetProperty("token").GetString()!;
            Ensure(token.Length >= 32, "issued token");
            Ensure(!File.ReadAllText(Path.Combine(directory, "config.json")).Contains(token,
                StringComparison.Ordinal), "token stored as hash");

            HttpResponseMessage sameModelPair = await PostJsonAsync(client, "/api/pair",
                new
                {
                    code = security.PairCode,
                    clientId = "22222222-2222-4222-8222-222222222222",
                    deviceName = "Self-test phone"
                });
            Ensure(sameModelPair.StatusCode == HttpStatusCode.OK,
                "same phone model can pair independently");
            Ensure(security.Devices.Count == 2, "paired device identity is not based on model name");
            HttpResponseMessage thirdDevicePair = await PostJsonAsync(client, "/api/pair",
                new
                {
                    code = security.PairCode,
                    clientId = "33333333-3333-4333-8333-333333333333",
                    deviceName = "Third self-test phone"
                });
            Ensure(thirdDevicePair.StatusCode == HttpStatusCode.Conflict,
                "third paired device is rejected");
            Ensure(security.Devices.Count == 2,
                "device limit does not evict an existing paired phone");
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

            using HttpResponseMessage initialStreamResponse = await client.SendAsync(
                new HttpRequestMessage(HttpMethod.Get, "/api/state/stream"),
                HttpCompletionOption.ResponseHeadersRead);
            Ensure(initialStreamResponse.StatusCode == HttpStatusCode.OK
                && initialStreamResponse.Content.Headers.ContentType?.MediaType == "text/event-stream",
                "authenticated SSE response");
            using Stream initialStream = await initialStreamResponse.Content.ReadAsStreamAsync();
            using StreamReader initialStreamReader = new(initialStream, Encoding.UTF8);
            (string InitialEvent, string InitialData) = await ReadSseEventAsync(initialStreamReader,
                TimeSpan.FromSeconds(5));
            Ensure(InitialEvent == "state" && InitialData.Contains("\"title\"", StringComparison.Ordinal),
                "initial SSE state");

            JsonDocument state = JsonDocument.Parse(await client.GetByteArrayAsync("/api/state"));
            Ensure(state.RootElement.GetProperty("title").GetString() == "Midnight Drive", "state title");
            Ensure(state.RootElement.GetProperty("trackId").GetString()?.Length >= 8,
                "state track identity");
            Ensure(!state.RootElement.GetProperty("shuffleEnabled").GetBoolean(),
                "initial shuffle state");
            Ensure(state.RootElement.GetProperty("repeatMode").GetString() == "off",
                "initial repeat state");
            string artworkId = state.RootElement.GetProperty("artworkId").GetString()!;
            state.Dispose();

            JsonDocument library = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/library?offset=0&limit=1"));
            Ensure(library.RootElement.GetProperty("total").GetInt32() == 2, "library total");
            string libraryRevision = library.RootElement.GetProperty("revision").GetString()!;
            Ensure(libraryRevision.Length == 64, "library revision");
            JsonElement firstSong = library.RootElement.GetProperty("items")[0];
            string firstTrackId = firstSong.GetProperty("id").GetString()!;
            Ensure(firstSong.GetProperty("title").GetString() == "Midnight Drive", "library title");
            library.Dispose();

            JsonDocument search = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/library?query=golden&offset=0&limit=40"));
            Ensure(search.RootElement.GetProperty("total").GetInt32() == 1, "library search");
            string secondTrackId = search.RootElement.GetProperty("items")[0]
                .GetProperty("id").GetString()!;
            search.Dispose();

            JsonDocument artists = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/collections?kind=artists&offset=0&limit=40"));
            Ensure(artists.RootElement.GetProperty("total").GetInt32() == 2,
                "artist collection browsing");
            Ensure(artists.RootElement.GetProperty("revision").GetString() == libraryRevision,
                "collection revision matches library");
            string artistId = artists.RootElement.GetProperty("items")[0]
                .GetProperty("id").GetString()!;
            artists.Dispose();
            JsonDocument artistTracks = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/library?collectionKind=artists&collectionId="
                + Uri.EscapeDataString(artistId) + "&offset=0&limit=40"));
            Ensure(artistTracks.RootElement.GetProperty("total").GetInt32() == 1,
                "artist-scoped songs");
            artistTracks.Dispose();

            JsonDocument albums = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/collections?kind=albums&offset=0&limit=40"));
            Ensure(albums.RootElement.GetProperty("total").GetInt32() == 2,
                "album collection browsing");
            albums.Dispose();
            JsonDocument genres = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/collections?kind=genres&offset=0&limit=40"));
            Ensure(genres.RootElement.GetProperty("total").GetInt32() == 2,
                "genre collection browsing");
            string genreId = genres.RootElement.GetProperty("items")[0]
                .GetProperty("id").GetString()!;
            genres.Dispose();
            JsonDocument genreTracks = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/library?collectionKind=genres&collectionId="
                + Uri.EscapeDataString(genreId) + "&offset=0&limit=40"));
            Ensure(genreTracks.RootElement.GetProperty("total").GetInt32() == 1,
                "genre-scoped songs");
            genreTracks.Dispose();
            JsonDocument playlists = JsonDocument.Parse(await client.GetByteArrayAsync(
                "/api/collections?kind=playlists&offset=0&limit=40"));
            Ensure(playlists.RootElement.GetProperty("total").GetInt32() == 2,
                "playlist collection browsing");
            playlists.Dispose();

            HttpResponseMessage play = await PostJsonAsync(client, "/api/play",
                new { trackId = secondTrackId });
            Ensure(play.StatusCode == HttpStatusCode.OK, "play library track");
            Ensure(media.LastSelection == new PlaybackSelection(secondTrackId),
                "legacy play request remains context-free");
            JsonDocument playing = JsonDocument.Parse(await client.GetByteArrayAsync("/api/state"));
            Ensure(playing.RootElement.GetProperty("title").GetString() == "Golden Static",
                "selected track playing");
            playing.Dispose();

            using JsonDocument albumCollections = JsonDocument.Parse(
                await client.GetByteArrayAsync(
                    "/api/collections?kind=albums&offset=0&limit=40"));
            string playbackAlbumId = albumCollections.RootElement.GetProperty("items")[0]
                .GetProperty("id").GetString()!;
            using JsonDocument playbackAlbum = JsonDocument.Parse(
                await client.GetByteArrayAsync(
                    "/api/library?collectionKind=albums&collectionId="
                    + Uri.EscapeDataString(playbackAlbumId) + "&offset=0&limit=40"));
            string playbackAlbumTrackId = playbackAlbum.RootElement.GetProperty("items")[0]
                .GetProperty("id").GetString()!;
            HttpResponseMessage contextualPlay = await PostJsonAsync(client, "/api/play",
                new
                {
                    trackId = playbackAlbumTrackId,
                    collectionKind = "albums",
                    collectionId = playbackAlbumId
                });
            Ensure(contextualPlay.StatusCode == HttpStatusCode.OK,
                "play track with album context");
            Ensure(media.LastSelection == new PlaybackSelection(
                    playbackAlbumTrackId, "albums", playbackAlbumId),
                "album playback context reaches media controller");

            Ensure((await PostJsonAsync(client, "/api/command",
                new { command = "shuffle", value = 1 })).StatusCode == HttpStatusCode.OK,
                "enable shuffle");
            Ensure((await PostJsonAsync(client, "/api/command",
                new { command = "repeat", value = 2 })).StatusCode == HttpStatusCode.OK,
                "enable repeat all");
            JsonDocument playbackModes = JsonDocument.Parse(
                await client.GetByteArrayAsync("/api/state"));
            Ensure(playbackModes.RootElement.GetProperty("shuffleEnabled").GetBoolean(),
                "updated shuffle state");
            Ensure(playbackModes.RootElement.GetProperty("repeatMode").GetString() == "all",
                "updated repeat state");
            playbackModes.Dispose();

            HttpResponseMessage command = await PostJsonAsync(client, "/api/command",
                new { command = "volume", value = 31 });
            Ensure(command.StatusCode == HttpStatusCode.OK, "volume command");
            JsonDocument updated = JsonDocument.Parse(await client.GetByteArrayAsync("/api/state"));
            Ensure(updated.RootElement.GetProperty("volume").GetInt32() == 31, "updated volume");
            updated.Dispose();

            HttpResponseMessage missingValue = await PostJsonAsync(client, "/api/command",
                new { command = "volume" });
            Ensure(missingValue.StatusCode == HttpStatusCode.BadRequest, "missing command value");

            HttpResponseMessage invalidCommand = await PostJsonAsync(client, "/api/command", new { });
            Ensure(invalidCommand.StatusCode == HttpStatusCode.BadRequest, "malformed command");

            HttpResponseMessage artwork = await client.GetAsync(
                "/api/artwork?id=" + artworkId + "&size=180");
            Ensure(artwork.StatusCode == HttpStatusCode.OK, "artwork endpoint");
            Ensure((await artwork.Content.ReadAsByteArrayAsync()).Length > 1_000, "artwork bytes");
            Ensure((await client.GetAsync(
                    "/api/artwork?id=" + artworkId + "&size=-1")).StatusCode
                    == HttpStatusCode.BadRequest,
                "negative artwork size rejected");
            Ensure((await client.GetAsync(
                    "/api/artwork?id=" + artworkId + "&size=1001")).StatusCode
                    == HttpStatusCode.BadRequest,
                "oversized artwork size rejected");
            Ensure((await client.GetAsync(
                    "/api/artwork?id=" + artworkId + "&size=999999999999999999999")).StatusCode
                    == HttpStatusCode.BadRequest,
                "overflowing artwork size rejected");
            Ensure((await client.GetAsync(
                    "/api/artwork?id=" + artworkId + "&size=wide")).StatusCode
                    == HttpStatusCode.BadRequest,
                "malformed artwork size rejected");

            HttpResponseMessage malformedPlay = await PostJsonAsync(client, "/api/play",
                new { trackId = "bad" });
            Ensure(malformedPlay.StatusCode == HttpStatusCode.BadRequest, "invalid play request");
            HttpResponseMessage incompletePlaybackContext = await PostJsonAsync(
                client, "/api/play",
                new { trackId = secondTrackId, collectionKind = "albums" });
            Ensure(incompletePlaybackContext.StatusCode == HttpStatusCode.BadRequest,
                "incomplete playback context rejected");

            HttpResponseMessage replacementPair = await PostJsonAsync(client, "/api/pair",
                new
                {
                    code = security.PairCode,
                    clientId = "11111111-1111-4111-8111-111111111111",
                    deviceName = "Renamed self-test phone"
                });
            Ensure(replacementPair.StatusCode == HttpStatusCode.OK, "same client identity replaces its token");
            using JsonDocument replacementJson = JsonDocument.Parse(
                await replacementPair.Content.ReadAsByteArrayAsync());
            string replacementToken = replacementJson.RootElement.GetProperty("token").GetString()!;
            Ensure(security.Devices.Count == 2, "re-pairing does not create a duplicate device");
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            Ensure((await client.GetAsync("/api/state")).StatusCode == HttpStatusCode.Unauthorized,
                "old token revoked after re-pairing");

            client.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Bearer", replacementToken);
            Ensure((await client.GetAsync("/api/state")).StatusCode == HttpStatusCode.OK,
                "replacement token accepted");
            Ensure((await PostJsonAsync(client, "/api/command",
                new { command = "playPause" })).StatusCode == HttpStatusCode.OK,
                "command wakes state stream");
            string revokedEvent = "";
            for (int index = 0; index < 10 && revokedEvent != "unauthorized"; index++)
                (revokedEvent, _) = await ReadSseEventAsync(initialStreamReader, TimeSpan.FromSeconds(5));
            Ensure(revokedEvent == "unauthorized", "live stream token revocation");
            initialStreamReader.Dispose();
            initialStream.Dispose();
            initialStreamResponse.Dispose();
            await WaitForActiveConnectionsAsync(server, maximum: 1);

            for (int attempt = 0; attempt < 5; attempt++)
            {
                HttpResponseMessage rejected = await PostJsonAsync(client, "/api/pair", new
                {
                    code = "bad",
                    clientId = $"44444444-4444-4444-8444-4444444444{attempt:D2}",
                    deviceName = "Rate-limit test"
                });
                Ensure(rejected.StatusCode == HttpStatusCode.Forbidden,
                    "failed pairing attempt recorded");
            }
            HttpResponseMessage throttled = await PostJsonAsync(client, "/api/pair", new
            {
                code = "bad",
                clientId = "55555555-5555-4555-8555-555555555555",
                deviceName = "Rate-limit test"
            });
            Ensure(throttled.StatusCode == HttpStatusCode.TooManyRequests
                && throttled.Headers.RetryAfter?.Delta > TimeSpan.Zero,
                "pairing throttle returns Retry-After");

            HttpResponseMessage forgotSelf = await client.SendAsync(
                new HttpRequestMessage(HttpMethod.Delete, "/api/pairing/self"));
            Ensure(forgotSelf.StatusCode == HttpStatusCode.OK,
                "paired device can revoke its own token");
            Ensure((await client.GetAsync("/api/state")).StatusCode == HttpStatusCode.Unauthorized,
                "self-revoked token is rejected");
            Ensure(security.Devices.Count == 1,
                "self-revocation removes only the calling device");

            Ensure(BridgeServer.IsLocalAddress(IPAddress.Parse("192.168.1.12")), "private address");
            Ensure(!BridgeServer.IsLocalAddress(IPAddress.Parse("8.8.8.8")), "public address rejected");
            Console.WriteLine("TunesLink Bridge self-test passed.");
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(exception);
            return 1;
        }
        finally
        {
            try { Directory.Delete(directory, true); } catch { }
        }
    }

}
