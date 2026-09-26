namespace TunesLinkBridge;

/// <summary>Orders selections per paired phone, including cancellation arriving before play.</summary>
internal sealed class PlaybackRequests(Func<string, bool> isAuthorized)
{
    private sealed class State
    {
        public long Sequence;
        public CancellationTokenSource? Active;
    }

    private readonly object gate = new();
    private readonly Dictionary<string, State> phones = new(StringComparer.Ordinal);

    public Lease? Begin(string bearer, long sequence, CancellationToken parent)
    {
        lock (gate)
        {
            State state = GetPhone(bearer);
            if (sequence <= state.Sequence) return null;
            state.Active?.Cancel();
            state.Sequence = sequence;
            state.Active = CancellationTokenSource.CreateLinkedTokenSource(parent);
            return new Lease(this, bearer, state.Active);
        }
    }

    public void Cancel(string bearer, long sequence)
    {
        lock (gate)
        {
            State state = GetPhone(bearer);
            if (sequence < state.Sequence) return;
            state.Sequence = sequence;
            state.Active?.Cancel();
        }
    }

    private State GetPhone(string bearer)
    {
        // Only currently authorized phones retain watermarks; token rotations cannot grow
        // this map indefinitely. An active revoked request is also canceled here.
        foreach (string key in phones.Keys.Where(key => !isAuthorized(key)).ToArray())
        {
            phones[key].Active?.Cancel();
            phones.Remove(key);
        }
        if (!phones.TryGetValue(bearer, out State? state)) phones[bearer] = state = new();
        return state;
    }

    internal sealed class Lease(PlaybackRequests owner, string bearer,
        CancellationTokenSource cancellation) : IDisposable
    {
        public CancellationToken Token => cancellation.Token;

        public void Dispose()
        {
            lock (owner.gate)
            {
                if (owner.phones.TryGetValue(bearer, out State? state) && state.Active == cancellation)
                    state.Active = null;
                cancellation.Dispose();
            }
        }
    }
}
