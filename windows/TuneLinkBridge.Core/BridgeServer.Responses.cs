using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class BridgeServer
{
    /// <summary>
    /// Every failure a phone receives: an English sentence it can show and a stable code it can
    /// act on, plus a retry delay for the failures that clear on their own.
    /// </summary>
    private sealed record ApiError(int Status, string Code, string Message, int RetryAfterSeconds = 0)
    {
        public static readonly ApiError Busy =
            new(503, "busy", "The computer is busy. Try again.", 2);
        public static readonly ApiError ItunesBusy = new(503, "itunes_busy",
            "iTunes is busy. Close any open iTunes windows or dialogs on the computer, then try again.", 3);
        public static readonly ApiError ItunesUnavailable =
            new(503, "itunes_unavailable", "Open iTunes on this computer to continue");
        public static readonly ApiError NotPaired = new(401, "not_paired", "Not paired");
        public static readonly ApiError NotFound = new(404, "not_found", "Not found");
        public static readonly ApiError PairingClosed = new(403, "pairing_closed",
            "Pairing is not open on this computer. Show the pairing code in TunesLink, then try again.");
        public static readonly ApiError BridgeError = new(500, "bridge_error", "Bridge error");

        public static ApiError InvalidRequest(string message) => new(400, "invalid_request", message);

        public static ApiError Superseded(string message) => new(409, "superseded", message);
    }

    /// <summary>
    /// The connection a response is written on. Keep-alive is decided when the headers are
    /// written, so the advertised idle timeout is the one the bridge will actually apply.
    /// </summary>
    private sealed class ResponseConnection(long startedAt, int remainingRequests, bool requested)
    {
        // Advertise slightly less than the real idle timeout so a phone never reuses a connection
        // at the moment the bridge closes it.
        private static readonly TimeSpan ReuseMargin = TimeSpan.FromSeconds(5);

        public bool Closing { get; set; } = !requested || remainingRequests <= 0;

        public string? KeepAliveHeader()
        {
            if (Closing) return null;
            TimeSpan lifetime = BridgeProtocol.ConnectionLifetime - Stopwatch.GetElapsedTime(startedAt);
            TimeSpan idle = (lifetime < BridgeProtocol.RequestIdleTimeout
                ? lifetime : BridgeProtocol.RequestIdleTimeout) - ReuseMargin;
            if (idle < TimeSpan.FromSeconds(1))
            {
                Closing = true;
                return null;
            }
            return string.Create(CultureInfo.InvariantCulture,
                $"timeout={(int)idle.TotalSeconds}, max={remainingRequests}");
        }
    }

    /// <summary>
    /// Maps a failure escaping a route to its response, or null when the connection itself failed
    /// and nothing can be written.
    /// </summary>
    private static ApiError? ApiErrorFor(Exception exception) => exception switch
    {
        IOException or ObjectDisposedException or BadHttpRequestException => null,
        // The route's own time limit expired: the computer is still finishing earlier work.
        OperationCanceledException => ApiError.Busy,
        MediaUnavailableException => ApiError.ItunesUnavailable with { Message = exception.Message },
        ItunesWorkerException worker => worker.Category switch
        {
            ItunesWorkerFailureCategory.Timeout => ApiError.Busy,
            ItunesWorkerFailureCategory.ItunesBusy => ApiError.ItunesBusy,
            ItunesWorkerFailureCategory.Unavailable =>
                ApiError.ItunesUnavailable with { Message = worker.Message },
            ItunesWorkerFailureCategory.ItunesTerminated
                or ItunesWorkerFailureCategory.ComDisconnected => ApiError.ItunesUnavailable,
            _ => ApiError.BridgeError,
        },
        COMException com when ItunesWorkerProtocol.ClassifyComFailure(com.HResult)
            == ItunesWorkerFailureCategory.ItunesBusy => ApiError.ItunesBusy,
        MediaNotFoundException => ApiError.NotFound with { Message = exception.Message },
        ArgumentException => ApiError.InvalidRequest(exception.Message),
        _ => ApiError.BridgeError,
    };

    private static Task WriteErrorAsync(Stream stream, ApiError error, CancellationToken token) =>
        WriteJsonAsync(stream, error.Status, new { error = error.Message, code = error.Code }, token,
            error.RetryAfterSeconds > 0
                ? new Dictionary<string, string>
                {
                    ["Retry-After"] = error.RetryAfterSeconds.ToString(CultureInfo.InvariantCulture)
                }
                : null);

    private static async Task WriteJsonAsync(Stream stream, int status, object body,
                                             CancellationToken token,
                                             IReadOnlyDictionary<string, string>? additionalHeaders = null)
    {
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(body, JsonOptions);
        await WriteBytesAsync(stream, status, bytes, "application/json; charset=utf-8", token,
            additionalHeaders);
    }

    private static async Task WriteBytesAsync(Stream stream, int status, byte[] bytes,
                                              string contentType, CancellationToken token,
                                              IReadOnlyDictionary<string, string>? additionalHeaders = null)
    {
        string reason = status switch
        {
            200 => "OK",
            204 => "No Content",
            400 => "Bad Request",
            401 => "Unauthorized",
            403 => "Forbidden",
            404 => "Not Found",
            409 => "Conflict",
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            503 => "Service Unavailable",
            >= 500 => "Server Error",
            >= 400 => "Client Error",
            _ => "OK"
        };
        StringBuilder headers = new($"HTTP/1.1 {status} {reason}\r\n" +
                         $"Content-Type: {contentType}\r\n" +
                         $"Content-Length: {bytes.Length}\r\n" +
                         "Cache-Control: no-store\r\n" +
                         "X-Content-Type-Options: nosniff\r\n");
        if (additionalHeaders is not null)
            foreach ((string name, string value) in additionalHeaders)
                headers.Append(name).Append(": ").Append(value).Append("\r\n");
        if (ResponseKeepAlive.Value?.KeepAliveHeader() is { } keepAlive)
            headers.Append("Connection: keep-alive\r\nKeep-Alive: ").Append(keepAlive).Append("\r\n");
        else
            headers.Append("Connection: close\r\n");
        headers.Append("\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(headers.ToString()), token).ConfigureAwait(false);
        if (bytes.Length > 0) await stream.WriteAsync(bytes, token).ConfigureAwait(false);
        await stream.FlushAsync(token).ConfigureAwait(false);
    }

}
