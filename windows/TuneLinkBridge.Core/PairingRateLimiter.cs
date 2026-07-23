using System.Net;

namespace TunesLinkBridge;

internal sealed class PairingRateLimiter
{
    private sealed class Attempts
    {
        public int Count;
        public DateTimeOffset WindowStarted;
        public DateTimeOffset BlockedUntil;
    }

    private readonly object gate = new();
    private readonly Dictionary<string, Attempts> perAddress = [];
    private readonly Attempts global;
    private readonly TimeProvider timeProvider;

    public PairingRateLimiter(TimeProvider? timeProvider = null)
    {
        this.timeProvider = timeProvider ?? TimeProvider.System;
        global = new Attempts { WindowStarted = this.timeProvider.GetUtcNow() };
    }

    public bool CanAttempt(IPAddress address, out int retryAfter)
    {
        lock (gate)
        {
            DateTimeOffset now = timeProvider.GetUtcNow();
            ResetWindowIfExpired(global, now);
            if (global.BlockedUntil > now)
            {
                retryAfter = RemainingSeconds(global.BlockedUntil, now);
                return false;
            }

            foreach (string expired in perAddress
                         .Where(item => item.Value.BlockedUntil <= now
                             && now - item.Value.WindowStarted >= TimeSpan.FromMinutes(1))
                         .Select(item => item.Key).ToList())
                perAddress.Remove(expired);

            if (!perAddress.TryGetValue(address.ToString(), out Attempts? attempts))
            {
                retryAfter = 0;
                return true;
            }
            if (attempts.BlockedUntil > now)
            {
                retryAfter = RemainingSeconds(attempts.BlockedUntil, now);
                return false;
            }
            retryAfter = 0;
            return true;
        }
    }

    public void RecordFailure(IPAddress address)
    {
        lock (gate)
        {
            DateTimeOffset now = timeProvider.GetUtcNow();
            string key = address.ToString();
            if (!perAddress.TryGetValue(key, out Attempts? attempts))
            {
                attempts = new Attempts { WindowStarted = now };
                perAddress[key] = attempts;
            }
            ResetWindowIfExpired(attempts, now);
            attempts.Count++;
            if (attempts.Count >= 5) attempts.BlockedUntil = now.AddMinutes(1);

            ResetWindowIfExpired(global, now);
            global.Count++;
            if (global.Count >= 20) global.BlockedUntil = now.AddMinutes(5);
        }
    }

    public void ClearAddress(IPAddress address)
    {
        lock (gate) perAddress.Remove(address.ToString());
    }

    private static void ResetWindowIfExpired(Attempts attempts, DateTimeOffset now)
    {
        if (now - attempts.WindowStarted < TimeSpan.FromMinutes(1)) return;
        attempts.Count = 0;
        attempts.WindowStarted = now;
        if (attempts.BlockedUntil <= now) attempts.BlockedUntil = default;
    }

    private static int RemainingSeconds(DateTimeOffset until, DateTimeOffset now) =>
        Math.Max(1, (int)Math.Ceiling((until - now).TotalSeconds));
}
