using System.Threading.Channels;

namespace TunesLinkBridge;

internal sealed record PlaybackStateUpdate(long Sequence, PlaybackState State);

internal sealed class PlaybackStateSubscription : IAsyncDisposable
{
    private readonly Action unsubscribe;
    private int disposed;

    internal PlaybackStateSubscription(ChannelReader<PlaybackStateUpdate> reader, Action unsubscribe)
    {
        Reader = reader;
        this.unsubscribe = unsubscribe;
    }

    public ChannelReader<PlaybackStateUpdate> Reader { get; }

    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) == 0) unsubscribe();
        return ValueTask.CompletedTask;
    }
}

internal sealed class PlaybackStateHub : IDisposable
{
    private static readonly TimeSpan FreshStateAge = TimeSpan.FromMilliseconds(650);
    private readonly IMediaController media;
    private readonly TimeProvider timeProvider;
    private readonly CancellationTokenSource cancellation = new();
    private readonly SemaphoreSlim sampleGate = new(1, 1);
    private readonly SemaphoreSlim wake = new(0, 1);
    private readonly object gate = new();
    private readonly Dictionary<Guid, Channel<PlaybackStateUpdate>> subscribers = [];
    private readonly Task sampler;
    private PlaybackState? current;
    private DateTimeOffset sampledAt;
    private long sequence;
    private bool disposed;

    public PlaybackStateHub(IMediaController media, TimeProvider? timeProvider = null)
    {
        this.media = media;
        this.timeProvider = timeProvider ?? TimeProvider.System;
        sampler = Task.Run(SampleLoopAsync);
    }

    public PlaybackState? Current
    {
        get { lock (gate) return current; }
    }

    public async Task<PlaybackState> GetStateAsync(CancellationToken token = default)
    {
        lock (gate)
        {
            if (current is not null && timeProvider.GetUtcNow() - sampledAt <= FreshStateAge)
                return current;
        }
        return await SampleAsync(token).ConfigureAwait(false);
    }

    public async Task<PlaybackStateSubscription> SubscribeAsync(CancellationToken token)
    {
        _ = await GetStateAsync(token).ConfigureAwait(false);
        Channel<PlaybackStateUpdate> channel = Channel.CreateBounded<PlaybackStateUpdate>(
            new BoundedChannelOptions(1)
            {
                FullMode = BoundedChannelFullMode.DropOldest,
                SingleReader = true,
                SingleWriter = false
            });
        Guid id = Guid.NewGuid();
        lock (gate)
        {
            subscribers.Add(id, channel);
            channel.Writer.TryWrite(new PlaybackStateUpdate(sequence, current!));
        }

        SignalSampler();
        return new PlaybackStateSubscription(channel.Reader, () => RemoveSubscriber(id));
    }

    public void Wake()
    {
        if (disposed) return;
        lock (gate) sampledAt = default;
        SignalSampler();
    }

    private void SignalSampler()
    {
        wake.ReleaseIfAvailable();
    }

    private async Task SampleLoopAsync()
    {
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                if (!HasSubscribers())
                {
                    await wake.WaitAsync(cancellation.Token).ConfigureAwait(false);
                    continue;
                }
                await SampleAsync(cancellation.Token).ConfigureAwait(false);
                PlaybackState? state = Current;
                TimeSpan interval = state?.Playing == true
                    ? TimeSpan.FromMilliseconds(750)
                    : TimeSpan.FromSeconds(2);
                using CancellationTokenSource intervalCancellation =
                    CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
                Task delay = Task.Delay(interval, intervalCancellation.Token);
                Task signal = wake.WaitAsync(intervalCancellation.Token);
                Task completed = await Task.WhenAny(delay, signal).ConfigureAwait(false);
                intervalCancellation.Cancel();
                try { await completed.ConfigureAwait(false); } catch (OperationCanceledException) { }
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { break; }
            catch
            {
                try { await Task.Delay(TimeSpan.FromSeconds(2), cancellation.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { break; }
            }
        }
    }

    private async Task<PlaybackState> SampleAsync(CancellationToken token)
    {
        await sampleGate.WaitAsync(token).ConfigureAwait(false);
        try
        {
            lock (gate)
            {
                if (current is not null && timeProvider.GetUtcNow() - sampledAt <= FreshStateAge)
                    return current;
            }

            using CancellationTokenSource operation = CancellationTokenSource.CreateLinkedTokenSource(token);
            operation.CancelAfter(BridgeProtocol.StateTimeout);
            PlaybackState sampled = await media.GetStateAsync(operation.Token).ConfigureAwait(false);
            List<Channel<PlaybackStateUpdate>> targets = [];
            PlaybackStateUpdate? update = null;
            lock (gate)
            {
                bool changed = current is null || current != sampled;
                current = sampled;
                sampledAt = timeProvider.GetUtcNow();
                if (changed)
                {
                    update = new PlaybackStateUpdate(++sequence, sampled);
                    targets.AddRange(subscribers.Values);
                }
            }
            if (update is not null)
                foreach (Channel<PlaybackStateUpdate> target in targets) target.Writer.TryWrite(update);
            return sampled;
        }
        finally
        {
            sampleGate.Release();
        }
    }

    private void RemoveSubscriber(Guid id)
    {
        Channel<PlaybackStateUpdate>? channel = null;
        lock (gate)
        {
            if (subscribers.Remove(id, out Channel<PlaybackStateUpdate>? removed)) channel = removed;
        }
        channel?.Writer.TryComplete();
    }

    private bool HasSubscribers()
    {
        lock (gate) return subscribers.Count > 0;
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        cancellation.Cancel();
        try { sampler.Wait(7000); } catch { }
        List<Channel<PlaybackStateUpdate>> channels;
        lock (gate)
        {
            channels = [.. subscribers.Values];
            subscribers.Clear();
        }
        foreach (Channel<PlaybackStateUpdate> channel in channels) channel.Writer.TryComplete();
        wake.Dispose();
        sampleGate.Dispose();
        cancellation.Dispose();
    }
}

internal static class SemaphoreSlimExtensions
{
    public static void ReleaseIfAvailable(this SemaphoreSlim semaphore)
    {
        try { semaphore.Release(); }
        catch (SemaphoreFullException) { }
    }
}
