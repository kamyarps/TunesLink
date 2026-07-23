using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class BridgeServer
{
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
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            503 => "Service Unavailable",
            _ => "Response"
        };
        StringBuilder headers = new($"HTTP/1.1 {status} {reason}\r\n" +
                         $"Content-Type: {contentType}\r\n" +
                         $"Content-Length: {bytes.Length}\r\n" +
                         "Cache-Control: no-store\r\n" +
                         "X-Content-Type-Options: nosniff\r\n");
        if (additionalHeaders is not null)
            foreach ((string name, string value) in additionalHeaders)
                headers.Append(name).Append(": ").Append(value).Append("\r\n");
        if (ResponseKeepAlive.Value)
            headers.Append("Connection: keep-alive\r\nKeep-Alive: timeout=30, max=64\r\n");
        else
            headers.Append("Connection: close\r\n");
        headers.Append("\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(headers.ToString()), token).ConfigureAwait(false);
        if (bytes.Length > 0) await stream.WriteAsync(bytes, token).ConfigureAwait(false);
        await stream.FlushAsync(token).ConfigureAwait(false);
    }

}
