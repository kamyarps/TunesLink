using System.Net;

namespace TunesLinkBridge;

internal sealed class PairingRateLimiter
{
    // Times are monotonic offsets from construction, so a wall-clock change can neither lift a
    // cooldown early nor extend it.
    private sealed class Attempts
    {
        public int Count;
        public TimeSpan WindowStarted;
        public TimeSpan BlockedUntil;
    }

    private readonly object gate = new();
    private readonly Dictionary<string, Attempts> perAddress = [];
    private readonly Attempts global;
    private readonly TimeProvider timeProvider;
    private readonly long origin;

    public PairingRateLimiter(TimeProvider? timeProvider = null)
    {
        this.timeProvider = timeProvider ?? TimeProvider.System;
        origin = this.timeProvider.GetTimestamp();
        global = new Attempts { WindowStarted = Now() };
    }

    public bool CanAttempt(IPAddress address, out int retryAfter)
    {
        lock (gate)
        {
            TimeSpan now = Now();
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
            TimeSpan now = Now();
            string key = address.ToString();
            if (!perAddress.TryGetValue(key, out Attempts? attempts))
            {
                attempts = new Attempts { WindowStarted = now };
                perAddress[key] = attempts;
            }
            ResetWindowIfExpired(attempts, now);
            attempts.Count++;
            if (attempts.Count >= 5) attempts.BlockedUntil = now + TimeSpan.FromMinutes(1);

            ResetWindowIfExpired(global, now);
            global.Count++;
            if (global.Count >= 20) global.BlockedUntil = now + TimeSpan.FromMinutes(5);
        }
    }

    public void ClearAddress(IPAddress address)
    {
        lock (gate) perAddress.Remove(address.ToString());
    }

    private TimeSpan Now() => timeProvider.GetElapsedTime(origin);

    private static void ResetWindowIfExpired(Attempts attempts, TimeSpan now)
    {
        if (now - attempts.WindowStarted < TimeSpan.FromMinutes(1)) return;
        attempts.Count = 0;
        attempts.WindowStarted = now;
        if (attempts.BlockedUntil <= now) attempts.BlockedUntil = default;
    }

    private static int RemainingSeconds(TimeSpan until, TimeSpan now) =>
        Math.Max(1, (int)Math.Ceiling((until - now).TotalSeconds));
}
