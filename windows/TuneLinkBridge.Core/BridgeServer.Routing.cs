using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class BridgeServer
{
    private async Task RouteAsync(Stream stream, HttpRequest request, IPAddress remote,
                                  CancellationToken token)
    {
        if (!IsLocalAddress(remote))
        {
            await WriteJsonAsync(stream, 403, new { error = "TunesLink is local-network only" }, token);
            return;
        }
        string path = request.Target.Split('?', 2)[0];
        if (request.Method == "GET" && path == "/api/info")
        {
            await WriteJsonAsync(stream, 200, new
            {
                protocol = BridgeProtocol.Id,
                id = security.BridgeId,
                name = ComputerName,
                port = options.Port,
                version = BridgeProtocol.ProductVersion,
                tlsFingerprint = tlsIdentity.Fingerprint
            }, token);
            return;
        }

        if (request.Method == "POST" && path == "/api/pair")
        {
            if (!pairingRateLimiter.CanAttempt(remote, out int retryAfter))
            {
                await WriteJsonAsync(stream, 429,
                    new { error = $"Too many attempts. Try again in {retryAfter} seconds." }, token,
                    new Dictionary<string, string> { ["Retry-After"] = retryAfter.ToString(CultureInfo.InvariantCulture) });
                return;
            }
            try
            {
                PairRequest? pairing = JsonSerializer.Deserialize<PairRequest>(request.Body, JsonOptions);
                if (pairing is null || string.IsNullOrWhiteSpace(pairing.Code))
                    throw new JsonException("Pairing code is required");
                string code = pairing.Code;
                string clientId = pairing.ClientId ?? "";
                if (!BridgeSecurity.IsValidClientId(clientId))
                    throw new JsonException("Client identity is required");
                string device = pairing.DeviceName ?? "Android phone";
                BridgeSecurity.PairingResult pair = security.Pair(code, clientId, device);
                if (pair.Status == BridgeSecurity.PairingStatus.PersistenceFailed)
                {
                    await WriteJsonAsync(stream, 500,
                        new { error = "Pairing could not be saved" }, token);
                    return;
                }
                if (pair.Status == BridgeSecurity.PairingStatus.DeviceLimitReached)
                {
                    await WriteJsonAsync(stream, 409,
                        new { error = "This PC already has two paired phones" }, token);
                    return;
                }
                if (!pair.Succeeded)
                {
                    pairingRateLimiter.RecordFailure(remote);
                    await WriteJsonAsync(stream, 403, new { error = "That pairing code is not correct" }, token);
                    return;
                }
                pairingRateLimiter.ClearAddress(remote);
                await WriteJsonAsync(stream, 200, new { token = pair.Token }, token);
            }
            catch (JsonException)
            {
                await WriteJsonAsync(stream, 400, new { error = "Invalid pairing request" }, token);
            }
            return;
        }

        string? bearer = BearerToken(request.Headers);
        if (!security.ValidateToken(bearer))
        {
            await WriteJsonAsync(stream, 401, new { error = "Not paired" }, token);
            return;
        }
        addressSelector?.ObserveAuthenticatedClient(remote);

        if (request.Method == "DELETE" && path == "/api/pairing/self")
        {
            PersistenceResult revocation = security.TryForgetToken(bearer);
            if (!revocation.Succeeded)
            {
                await WriteJsonAsync(stream, 500,
                    new { error = "Revocation could not be saved" }, token);
                return;
            }
            if (!revocation.Changed)
            {
                await WriteJsonAsync(stream, 401, new { error = "Not paired" }, token);
                return;
            }
            await WriteJsonAsync(stream, 200, new { ok = true }, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/state")
        {
            if (options.Demo) Console.WriteLine("state-poll");
            PlaybackState state = await stateHub.GetStateAsync(token).ConfigureAwait(false);
            await WriteJsonAsync(stream, 200, state, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/artwork")
        {
            string id = QueryValue(request.Target, "id");
            string sizeValue = QueryValue(request.Target, "size");
            int size = 1000;
            if (sizeValue.Length > 0
                && (!int.TryParse(sizeValue, out size) || size is < 64 or > 1000))
            {
                await WriteJsonAsync(stream, 400,
                    new { error = "Artwork size must be between 64 and 1000 pixels" }, token);
                return;
            }
            using CancellationTokenSource operation = OperationTimeout(BridgeProtocol.MediaTimeout, token);
            ArtworkData? artwork = await media.GetArtworkAsync(id, size, operation.Token)
                .ConfigureAwait(false);
            if (artwork is null)
            {
                await WriteJsonAsync(stream, 404, new { error = "Artwork not found" }, token);
                return;
            }
            if (artwork.Bytes.Length is 0 or > MaxArtworkResponseBytes
                || artwork.ContentType is not ("image/jpeg" or "image/png"))
            {
                await WriteJsonAsync(stream, 500, new { error = "Artwork is invalid" }, token);
                return;
            }
            await WriteBytesAsync(stream, 200, artwork.Bytes, artwork.ContentType, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/collections")
        {
            string kind = QueryValue(request.Target, "kind").Trim().ToLowerInvariant();
            string query = QueryValue(request.Target, "query").Trim();
            if (kind is not ("artists" or "albums" or "genres" or "playlists"))
            {
                await WriteJsonAsync(stream, 400, new { error = "Unknown library collection" }, token);
                return;
            }
            if (query.Length > 120)
            {
                await WriteJsonAsync(stream, 400, new { error = "Search is too long" }, token);
                return;
            }
            int offset = int.TryParse(QueryValue(request.Target, "offset"), out int parsedOffset)
                ? Math.Clamp(parsedOffset, 0, 100_000) : 0;
            int limit = int.TryParse(QueryValue(request.Target, "limit"), out int parsedLimit)
                ? Math.Clamp(parsedLimit, 1, 60) : 40;
            using CancellationTokenSource operation = OperationTimeout(BridgeProtocol.CollectionTimeout, token);
            LibraryCollectionPage page = await media.GetCollectionsAsync(kind, query, offset,
                limit, operation.Token).ConfigureAwait(false);
            await WriteJsonAsync(stream, 200, page, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/library")
        {
            string query = QueryValue(request.Target, "query").Trim();
            string collectionKind = QueryValue(request.Target, "collectionKind").Trim()
                .ToLowerInvariant();
            string collectionId = QueryValue(request.Target, "collectionId").Trim();
            if (query.Length > 120)
            {
                await WriteJsonAsync(stream, 400, new { error = "Search is too long" }, token);
                return;
            }
            int offset = int.TryParse(QueryValue(request.Target, "offset"), out int parsedOffset)
                ? Math.Clamp(parsedOffset, 0, 100_000) : 0;
            int limit = int.TryParse(QueryValue(request.Target, "limit"), out int parsedLimit)
                ? Math.Clamp(parsedLimit, 1, 60) : 40;
            if (collectionKind.Length > 0
                && (collectionKind is not ("artists" or "albums" or "genres" or "playlists")
                    || collectionId.Length is < 3 or > 1024))
            {
                await WriteJsonAsync(stream, 400, new { error = "Invalid library collection" }, token);
                return;
            }
            TimeSpan timeout = collectionKind.Length == 0
                ? BridgeProtocol.LibraryTimeout : BridgeProtocol.CollectionTimeout;
            using CancellationTokenSource operation = OperationTimeout(timeout, token);
            try
            {
                LibraryPage page = collectionKind.Length == 0
                    ? await media.GetLibraryAsync(query, offset, limit, operation.Token)
                        .ConfigureAwait(false)
                    : await media.GetCollectionTracksAsync(collectionKind, collectionId, query,
                        offset, limit, operation.Token).ConfigureAwait(false);
                await WriteJsonAsync(stream, 200, page, token);
            }
            catch (ArgumentException exception)
            {
                await WriteJsonAsync(stream, 404, new { error = exception.Message }, token);
            }
            return;
        }

        if (request.Method == "POST" && path == "/api/play")
        {
            try
            {
                PlayRequest? submitted = JsonSerializer.Deserialize<PlayRequest>(request.Body, JsonOptions);
                string id = submitted?.TrackId?.Trim() ?? "";
                string collectionKind = submitted?.CollectionKind?.Trim().ToLowerInvariant() ?? "";
                string collectionId = submitted?.CollectionId?.Trim() ?? "";
                if (id.Length is < 8 or > 80)
                {
                    await WriteJsonAsync(stream, 400, new { error = "A valid song is required" }, token);
                    return;
                }
                bool hasCollectionKind = collectionKind.Length > 0;
                bool hasCollectionId = collectionId.Length > 0;
                if (hasCollectionKind != hasCollectionId
                    || (hasCollectionKind
                        && (collectionKind is not ("artists" or "albums" or "genres" or "playlists")
                            || collectionId.Length is < 3 or > 1024)))
                {
                    await WriteJsonAsync(stream, 400,
                        new { error = "Invalid playback collection" }, token);
                    return;
                }
                using CancellationTokenSource operation =
                    OperationTimeout(BridgeProtocol.PlaybackTimeout, token);
                await media.PlayTrackAsync(
                    new PlaybackSelection(id, collectionKind, collectionId),
                    operation.Token).ConfigureAwait(false);
                stateHub.Wake();
                await WriteJsonAsync(stream, 200, new { ok = true }, token);
            }
            catch (JsonException)
            {
                await WriteJsonAsync(stream, 400, new { error = "Invalid play request" }, token);
            }
            catch (ArgumentException exception)
            {
                await WriteJsonAsync(stream, 404, new { error = exception.Message }, token);
            }
            return;
        }

        if (request.Method == "POST" && path == "/api/command")
        {
            try
            {
                CommandRequest? submitted = JsonSerializer.Deserialize<CommandRequest>(request.Body, JsonOptions);
                string command = submitted?.Command ?? "";
                double? value = submitted?.Value;
                if (command is not ("playPause" or "next" or "previous" or "volume" or "position"
                    or "shuffle" or "repeat"))
                {
                    await WriteJsonAsync(stream, 400, new { error = "Unknown command" }, token);
                    return;
                }
                if (value is not null && !double.IsFinite(value.Value))
                    throw new ArgumentException("Command value must be finite");
                using CancellationTokenSource operation = OperationTimeout(BridgeProtocol.StateTimeout, token);
                await media.ExecuteAsync(new PlayerCommand(command, value), operation.Token)
                    .ConfigureAwait(false);
                stateHub.Wake();
                await WriteJsonAsync(stream, 200, new { ok = true }, token);
            }
            catch (JsonException)
            {
                await WriteJsonAsync(stream, 400, new { error = "Invalid command" }, token);
            }
            catch (ArgumentException exception)
            {
                await WriteJsonAsync(stream, 400, new { error = exception.Message }, token);
            }
            return;
        }

        await WriteJsonAsync(stream, 404, new { error = "Not found" }, token);
    }

    private string ComputerName => string.IsNullOrWhiteSpace(options.ComputerName)
        ? Environment.MachineName
        : options.ComputerName;

    private async Task StreamStateAsync(Stream stream, string bearer, CancellationToken token)
    {
        if (options.Demo) Console.WriteLine("state-stream:open");
        try
        {
            const string responseHeaders =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: keep-alive\r\n\r\n";
            await WriteWithTimeoutAsync(stream, Encoding.ASCII.GetBytes(responseHeaders), token)
                .ConfigureAwait(false);
            await using PlaybackStateSubscription subscription = await stateHub.SubscribeAsync(token)
                .ConfigureAwait(false);

            while (!token.IsCancellationRequested)
            {
                using CancellationTokenSource wait = CancellationTokenSource.CreateLinkedTokenSource(token);
                Task<bool> stateReady = subscription.Reader.WaitToReadAsync(wait.Token).AsTask();
                Task heartbeat = Task.Delay(BridgeProtocol.SseHeartbeatInterval, wait.Token);
                Task completed = await Task.WhenAny(stateReady, heartbeat).ConfigureAwait(false);
                wait.Cancel();

                if (!security.ValidateToken(bearer))
                {
                    await WriteSseChunkAsync(stream, "event: unauthorized\ndata: {}\n\n", token)
                        .ConfigureAwait(false);
                    break;
                }

                if (completed == stateReady && await stateReady.ConfigureAwait(false))
                {
                    while (subscription.Reader.TryRead(out PlaybackStateUpdate? update))
                    {
                        string json = JsonSerializer.Serialize(update.State, JsonOptions);
                        await WriteSseChunkAsync(stream,
                            $"id: {update.Sequence}\nevent: state\ndata: {json}\n\n", token)
                            .ConfigureAwait(false);
                    }
                }
                else
                {
                    await WriteSseChunkAsync(stream, ": keepalive\n\n", token).ConfigureAwait(false);
                }
            }
            try { await WriteWithTimeoutAsync(stream, "0\r\n\r\n"u8.ToArray(), token).ConfigureAwait(false); }
            catch { }
        }
        finally
        {
            if (options.Demo) Console.WriteLine("state-stream:close");
        }
    }

    private static async Task WriteSseChunkAsync(Stream stream, string payload,
        CancellationToken token)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(payload);
        byte[] prefix = Encoding.ASCII.GetBytes(bytes.Length.ToString("X", CultureInfo.InvariantCulture) + "\r\n");
        byte[] suffix = "\r\n"u8.ToArray();
        using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(BridgeProtocol.SseWriteTimeout);
        await stream.WriteAsync(prefix, timeout.Token).ConfigureAwait(false);
        await stream.WriteAsync(bytes, timeout.Token).ConfigureAwait(false);
        await stream.WriteAsync(suffix, timeout.Token).ConfigureAwait(false);
        await stream.FlushAsync(timeout.Token).ConfigureAwait(false);
    }

    private static async Task WriteWithTimeoutAsync(Stream stream, byte[] bytes,
        CancellationToken token)
    {
        using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(BridgeProtocol.SseWriteTimeout);
        await stream.WriteAsync(bytes, timeout.Token).ConfigureAwait(false);
        await stream.FlushAsync(timeout.Token).ConfigureAwait(false);
    }

}
