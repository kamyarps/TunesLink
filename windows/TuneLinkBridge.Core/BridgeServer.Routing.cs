using System.Diagnostics;
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
            await WriteErrorAsync(stream,
                new ApiError(403, "invalid_request", "TunesLink is local-network only"), token);
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
            // A closed bridge answers before anything else so a guess costs nothing and
            // reveals nothing.
            if (!security.PairingOpen)
            {
                await WriteErrorAsync(stream, ApiError.PairingClosed, token);
                return;
            }
            string code;
            string clientId;
            string device;
            try
            {
                PairRequest? pairing = JsonSerializer.Deserialize<PairRequest>(request.Body, JsonOptions);
                if (pairing is null || string.IsNullOrWhiteSpace(pairing.Code))
                    throw new JsonException("Pairing code is required");
                code = pairing.Code;
                clientId = pairing.ClientId ?? "";
                if (!BridgeSecurity.IsValidClientId(clientId))
                    throw new JsonException("Client identity is required");
                device = pairing.DeviceName ?? "Android phone";
            }
            catch (JsonException)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid pairing request"), token);
                return;
            }
            BridgeSecurity.PairingResult? outcome = null;
            int retryAfter;
            // Checking the limit and recording the result is one step, so concurrent connections
            // cannot all pass the check before any of their failures are counted.
            lock (pairingGate)
            {
                if (pairingRateLimiter.CanAttempt(remote, out retryAfter))
                {
                    BridgeSecurity.PairingResult attempt = security.Pair(code, clientId, device);
                    if (attempt.Status == BridgeSecurity.PairingStatus.Rejected)
                        pairingRateLimiter.RecordFailure(remote);
                    else if (attempt.Succeeded)
                        pairingRateLimiter.ClearAddress(remote);
                    outcome = attempt;
                }
            }
            if (outcome is not { } pair)
            {
                await WriteJsonAsync(stream, 429,
                    new
                    {
                        error = $"Too many attempts. Try again in {retryAfter} seconds.",
                        code = "pairing_rate_limited",
                        retryAfterSeconds = retryAfter
                    }, token,
                    new Dictionary<string, string> { ["Retry-After"] = retryAfter.ToString(CultureInfo.InvariantCulture) });
                return;
            }
            ApiError? failure = pair.Status switch
            {
                BridgeSecurity.PairingStatus.Succeeded => null,
                BridgeSecurity.PairingStatus.PairingClosed => ApiError.PairingClosed,
                BridgeSecurity.PairingStatus.PersistenceFailed =>
                    new ApiError(500, "pairing_save_failed", "Pairing could not be saved"),
                BridgeSecurity.PairingStatus.DeviceLimitReached =>
                    new ApiError(409, "pairing_device_limit", "This PC already has two paired phones"),
                _ => new ApiError(403, "pairing_code_incorrect", "That pairing code is not correct"),
            };
            if (failure is not null)
            {
                await WriteErrorAsync(stream, failure, token);
                return;
            }
            await WriteJsonAsync(stream, 200, new { token = pair.Token }, token);
            return;
        }

        string? bearer = BearerToken(request.Headers);
        if (!security.ValidateToken(bearer))
        {
            await WriteErrorAsync(stream, ApiError.NotPaired, token);
            return;
        }
        addressSelector?.ObserveAuthenticatedClient(remote);

        if (request.Method == "DELETE" && path == "/api/pairing/self")
        {
            PersistenceResult revocation = security.TryForgetToken(bearer);
            if (!revocation.Succeeded)
            {
                await WriteErrorAsync(stream,
                    ApiError.BridgeError with { Message = "Revocation could not be saved" }, token);
                return;
            }
            if (!revocation.Changed)
            {
                await WriteErrorAsync(stream, ApiError.NotPaired, token);
                return;
            }
            await WriteJsonAsync(stream, 200, new { ok = true }, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/state")
        {
            if (options.Demo) Console.WriteLine("state-poll");
            PlaybackState state;
            try
            {
                state = await stateHub.GetStateAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!token.IsCancellationRequested)
            {
                await WriteErrorAsync(stream, ApiError.Busy, token);
                return;
            }
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
                await WriteErrorAsync(stream,
                    ApiError.InvalidRequest("Artwork size must be between 64 and 1000 pixels"), token);
                return;
            }
            using CancellationTokenSource operation = OperationTimeout(BridgeProtocol.MediaTimeout, token);
            ArtworkData? artwork = await media.GetArtworkAsync(id, size, operation.Token)
                .ConfigureAwait(false);
            if (artwork is null)
            {
                await WriteErrorAsync(stream, ApiError.NotFound with { Message = "Artwork not found" }, token);
                return;
            }
            if (artwork.Bytes.Length is 0 or > MaxArtworkResponseBytes
                || artwork.ContentType is not ("image/jpeg" or "image/png"))
            {
                await WriteErrorAsync(stream, ApiError.BridgeError with { Message = "Artwork is invalid" }, token);
                return;
            }
            await WriteBytesAsync(stream, 200, artwork.Bytes, artwork.ContentType, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/collection-albums")
        {
            string kind = QueryValue(request.Target, "kind").Trim().ToLowerInvariant();
            string id = QueryValue(request.Target, "id").Trim();
            string query = QueryValue(request.Target, "query").Trim();
            if (query.Length > 120 || kind is not ("artists" or "genres")
                || !ItunesCollectionId.IsValidText(id, kind))
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid album collection"), token);
                return;
            }
            int offset = int.TryParse(QueryValue(request.Target, "offset"), out int parsedOffset)
                ? Math.Max(0, parsedOffset) : 0;
            int limit = int.TryParse(QueryValue(request.Target, "limit"), out int parsedLimit)
                ? Math.Clamp(parsedLimit, 1, 60) : 40;
            using CancellationTokenSource operation = OperationTimeout(BridgeProtocol.CollectionTimeout, token);
            LibraryCollectionPage page = await media.GetCollectionAlbumsAsync(kind, id, query,
                offset, limit, operation.Token).ConfigureAwait(false);
            await WriteJsonAsync(stream, 200, page, token);
            return;
        }

        if (request.Method == "GET" && path == "/api/collections")
        {
            string kind = QueryValue(request.Target, "kind").Trim().ToLowerInvariant();
            string query = QueryValue(request.Target, "query").Trim();
            if (kind is not ("artists" or "albums" or "genres" or "playlists"))
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Unknown library collection"), token);
                return;
            }
            if (query.Length > 120)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Search is too long"), token);
                return;
            }
            int offset = int.TryParse(QueryValue(request.Target, "offset"), out int parsedOffset)
                ? Math.Max(parsedOffset, 0) : 0;
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
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Search is too long"), token);
                return;
            }
            int offset = int.TryParse(QueryValue(request.Target, "offset"), out int parsedOffset)
                ? Math.Max(parsedOffset, 0) : 0;
            int limit = int.TryParse(QueryValue(request.Target, "limit"), out int parsedLimit)
                ? Math.Clamp(parsedLimit, 1, 60) : 40;
            if (collectionKind.Length > 0
                && (collectionKind is not ("artists" or "albums" or "genres" or "playlists")
                    || collectionId.Length is < 3 or > 1024))
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid library collection"), token);
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
                await WriteErrorAsync(stream, ApiError.NotFound with { Message = exception.Message }, token);
            }
            return;
        }

        if (request.Method == "POST" && path == "/api/play/cancel")
        {
            try
            {
                CancelPlayRequest? submitted = JsonSerializer.Deserialize<CancelPlayRequest>(request.Body, JsonOptions);
                if (submitted is null || submitted.Sequence <= 0) throw new JsonException();
                playbackRequests.Cancel(bearer!, submitted.Sequence);
                await WriteJsonAsync(stream, 200, new { ok = true }, token);
            }
            catch (JsonException)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid playback cancellation"), token);
            }
            return;
        }

        if (request.Method == "POST" && path == "/api/play")
        {
            try
            {
                PlayRequest? submitted = JsonSerializer.Deserialize<PlayRequest>(request.Body, JsonOptions);
                if (submitted?.Sequence is <= 0) throw new JsonException("Invalid playback sequence");
                string id = submitted?.TrackId?.Trim() ?? "";
                string collectionKind = submitted?.CollectionKind?.Trim().ToLowerInvariant() ?? "";
                string collectionId = submitted?.CollectionId?.Trim() ?? "";
                if (id.Length is < 8 or > 80)
                {
                    await WriteErrorAsync(stream, ApiError.InvalidRequest("A valid song is required"), token);
                    return;
                }
                bool hasCollectionKind = collectionKind.Length > 0;
                bool hasCollectionId = collectionId.Length > 0;
                if (hasCollectionKind != hasCollectionId
                    || (hasCollectionKind
                        && (collectionKind is not ("artists" or "albums" or "genres" or "playlists")
                            || collectionId.Length is < 3 or > 1024)))
                {
                    await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid playback collection"), token);
                    return;
                }
                using CancellationTokenSource operation =
                    OperationTimeout(BridgeProtocol.PlaybackTimeout, token);
                using PlaybackRequests.Lease? selection = submitted?.Sequence is { } sequence
                    ? playbackRequests.Begin(bearer!, sequence, operation.Token) : null;
                if (submitted?.Sequence is not null && selection is null)
                {
                    await WriteErrorAsync(stream, ApiError.Superseded("Playback selection was superseded"), token);
                    return;
                }
                long started = Stopwatch.GetTimestamp();
                try
                {
                    await media.PlayTrackAsync(
                        new PlaybackSelection(id, collectionKind, collectionId),
                        selection?.Token ?? operation.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (selection?.Token.IsCancellationRequested == true
                    && !token.IsCancellationRequested && !operation.IsCancellationRequested)
                {
                    await WriteErrorAsync(stream, ApiError.Superseded("Playback selection was canceled"), token);
                    return;
                }
                finally
                {
                    BridgeDiagnostics.RecordDuration("play.request",
                        (long)Stopwatch.GetElapsedTime(started).TotalMilliseconds,
                        options.ConfigDirectory);
                }
                stateHub.Wake();
                await WriteJsonAsync(stream, 200, new { ok = true }, token);
            }
            catch (JsonException)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid play request"), token);
            }
            catch (ArgumentException exception)
            {
                await WriteErrorAsync(stream, ApiError.NotFound with { Message = exception.Message }, token);
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
                    await WriteErrorAsync(stream, ApiError.InvalidRequest("Unknown command"), token);
                    return;
                }
                if (value is not null && !double.IsFinite(value.Value))
                    throw new ArgumentException("Command value must be finite");
                using CancellationTokenSource operation = OperationTimeout(
                    command is "previous" or "shuffle" or "repeat"
                        ? BridgeProtocol.PlaybackTimeout : BridgeProtocol.StateTimeout, token);
                await media.ExecuteAsync(new PlayerCommand(command, value), operation.Token)
                    .ConfigureAwait(false);
                stateHub.Wake();
                await WriteJsonAsync(stream, 200, new { ok = true }, token);
            }
            catch (JsonException)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest("Invalid command"), token);
            }
            catch (ArgumentException exception)
            {
                await WriteErrorAsync(stream, ApiError.InvalidRequest(exception.Message), token);
            }
            return;
        }

        await WriteErrorAsync(stream, ApiError.NotFound, token);
    }

    private string ComputerName => string.IsNullOrWhiteSpace(options.ComputerName)
        ? Environment.MachineName
        : options.ComputerName;

    private async Task StreamStateAsync(Stream stream, string bearer, CancellationToken token)
    {
        if (options.Demo) Console.WriteLine("state-stream:open");
        // A revocation must end the stream now, not at the next heartbeat.
        TaskCompletionSource securityChanged = new(TaskCreationOptions.RunContinuationsAsynchronously);
        void OnSecurityChanged() => Volatile.Read(ref securityChanged).TrySetResult();
        security.Changed += OnSecurityChanged;
        try
        {
            await using PlaybackStateSubscription subscription = await stateHub.SubscribeAsync(token)
                .ConfigureAwait(false);
            const string responseHeaders =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n";
            await WriteWithTimeoutAsync(stream, Encoding.ASCII.GetBytes(responseHeaders), token)
                .ConfigureAwait(false);

            while (!token.IsCancellationRequested)
            {
                using CancellationTokenSource wait = CancellationTokenSource.CreateLinkedTokenSource(token);
                Task<bool> stateReady = subscription.Reader.WaitToReadAsync(wait.Token).AsTask();
                Task heartbeat = Task.Delay(BridgeProtocol.SseHeartbeatInterval, wait.Token);
                Task completed = await Task.WhenAny(stateReady, heartbeat,
                    Volatile.Read(ref securityChanged).Task).ConfigureAwait(false);
                wait.Cancel();
                // Re-armed before the check below, so a change arriving during it wakes the loop again.
                if (securityChanged.Task.IsCompleted)
                    Volatile.Write(ref securityChanged,
                        new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously));

                if (!security.ValidateToken(bearer))
                {
                    await WriteSseChunkAsync(stream, "event: unauthorized\ndata: {}\n\n", token)
                        .ConfigureAwait(false);
                    break;
                }

                if (completed != stateReady && completed != heartbeat) continue;
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
            security.Changed -= OnSecurityChanged;
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
