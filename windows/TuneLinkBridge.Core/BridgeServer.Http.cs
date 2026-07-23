using System.Globalization;
using System.Text;

namespace TunesLinkBridge;

internal sealed partial class BridgeServer
{
    private sealed class HttpConnectionReader
    {
        private byte[] pending = [];

        public async Task<HttpRequest?> ReadAsync(Stream stream, CancellationToken token)
        {
            using MemoryStream received = new();
            if (pending.Length > 0) received.Write(pending);
            pending = [];
            byte[] buffer = new byte[4096];
            int headerEnd = FindHeaderEnd(received.GetBuffer(), (int)received.Length);
            while (headerEnd < 0)
            {
                int read = await stream.ReadAsync(buffer, token).ConfigureAwait(false);
                if (read == 0) return received.Length == 0
                    ? null : throw new BadHttpRequestException("Incomplete headers");
                received.Write(buffer, 0, read);
                headerEnd = FindHeaderEnd(received.GetBuffer(), (int)received.Length);
                if (headerEnd < 0 && received.Length > MaxHeaderBytes)
                    throw new BadHttpRequestException("Headers too large");
            }
            if (headerEnd > MaxHeaderBytes) throw new BadHttpRequestException("Headers too large");

            byte[] all = received.ToArray();
            string headerText = Encoding.ASCII.GetString(all, 0, headerEnd);
            string[] lines = headerText.Split("\r\n", StringSplitOptions.None);
            string[] first = lines[0].Split(' ', 3);
            if (first.Length != 3
            || first[0].Length is < 1 or > 8
            || !first[0].All(character => character is >= 'A' and <= 'Z')
            || first[2] is not ("HTTP/1.1" or "HTTP/1.0"))
                throw new BadHttpRequestException("Bad request line");
            string target = first[1];
            if (target.Length is < 1 or > MaxTargetBytes
            || target[0] != '/'
            || target.Contains('#', StringComparison.Ordinal)
            || target.Any(character => character <= ' ' || character >= 127))
                throw new BadHttpRequestException("Bad request target");
            Dictionary<string, string> headers = new(StringComparer.OrdinalIgnoreCase);
            foreach (string line in lines.Skip(1))
            {
                if (line.Length is 0 or > MaxHeaderLineBytes)
                    throw new BadHttpRequestException("Bad header");
                int colon = line.IndexOf(':');
                if (colon <= 0) throw new BadHttpRequestException("Bad header");
                string name = line[..colon];
                string value = line[(colon + 1)..].Trim();
                if (!name.All(IsHeaderNameCharacter)
                || value.Any(character => character < 32 && character != '\t' || character == 127)
                || !headers.TryAdd(name, value))
                    throw new BadHttpRequestException("Bad header");
                if (headers.Count > MaxHeaderCount)
                    throw new BadHttpRequestException("Too many headers");
            }
            if (first[2] == "HTTP/1.1" && !headers.ContainsKey("Host"))
                throw new BadHttpRequestException("Host is required");
            if (headers.ContainsKey("Transfer-Encoding"))
                throw new BadHttpRequestException("Transfer encoding is unsupported");
            int contentLength = 0;
            if (headers.TryGetValue("Content-Length", out string? contentText)
            && (contentText.Length == 0
                || !contentText.All(char.IsAsciiDigit)
                || !int.TryParse(contentText, NumberStyles.None, CultureInfo.InvariantCulture,
                    out contentLength)))
                throw new BadHttpRequestException("Bad length");
            if (contentLength > MaxBodyBytes) throw new BadHttpRequestException("Body too large");
            int bodyStart = headerEnd + 4;
            int requiredLength = bodyStart + contentLength;
            while (received.Length < requiredLength)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(0,
                    Math.Min(buffer.Length, requiredLength - (int)received.Length)), token)
                    .ConfigureAwait(false);
                if (read == 0) throw new BadHttpRequestException("Incomplete body");
                received.Write(buffer, 0, read);
            }
            all = received.ToArray();
            byte[] body = new byte[contentLength];
            if (contentLength > 0) Buffer.BlockCopy(all, bodyStart, body, 0, contentLength);
            if (all.Length > requiredLength) pending = all[requiredLength..];
            return new HttpRequest(first[0], target, first[2], headers, body);
        }
    }

    private static bool IsHeaderNameCharacter(char character) =>
        char.IsAsciiLetterOrDigit(character)
        || character is '!' or '#' or '$' or '%' or '&' or '\'' or '*' or '+'
            or '-' or '.' or '^' or '_' or '`' or '|' or '~';

    private static int FindHeaderEnd(byte[] data, int length)
    {
        for (int i = 0; i <= length - 4; i++)
            if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10)
                return i;
        return -1;
    }

    private static string? BearerToken(Dictionary<string, string> headers)
    {
        if (!headers.TryGetValue("Authorization", out string? authorization)) return null;
        const string prefix = "Bearer ";
        return authorization.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            ? authorization[prefix.Length..].Trim() : null;
    }

    private static CancellationTokenSource OperationTimeout(TimeSpan timeout, CancellationToken parent)
    {
        CancellationTokenSource source = CancellationTokenSource.CreateLinkedTokenSource(parent);
        source.CancelAfter(timeout);
        return source;
    }

    private static string QueryValue(string target, string key)
    {
        try
        {
            int queryIndex = target.IndexOf('?');
            if (queryIndex < 0) return "";
            foreach (string part in target[(queryIndex + 1)..].Split('&'))
            {
                string[] pair = part.Split('=', 2);
                if (pair.Length == 2 && string.Equals(Uri.UnescapeDataString(pair[0]), key,
                        StringComparison.OrdinalIgnoreCase))
                    return Uri.UnescapeDataString(pair[1].Replace("+", " ", StringComparison.Ordinal));
            }
            return "";
        }
        catch (UriFormatException exception)
        {
            throw new BadHttpRequestException("Malformed query encoding: " + exception.GetType().Name);
        }
    }

}
