namespace TunesLinkBridge;

/// <summary>Coordinates GUI instances and forwards later launches to the existing window.</summary>
internal sealed class SingleInstanceCoordinator : IDisposable
{
    private const string MutexName = "TunesLinkBridge.Personal.v1";
    private const string ActivationName = "TunesLinkBridge.Personal.Activate.v1";
    private readonly Mutex mutex;
    private readonly EventWaitHandle activation;
    private readonly CancellationTokenSource stopping = new();
    private Task? listener;

    public SingleInstanceCoordinator()
    {
        mutex = new Mutex(true, MutexName, out bool primary);
        IsPrimary = primary;
        activation = new EventWaitHandle(false, EventResetMode.AutoReset, ActivationName);
    }

    public bool IsPrimary { get; }

    public void SignalPrimary()
    {
        try { activation.Set(); }
        catch (ObjectDisposedException) { }
    }

    public void Listen(Action activationRequested)
    {
        if (!IsPrimary || listener is not null) return;
        listener = Task.Run(() =>
        {
            WaitHandle[] handles = [activation, stopping.Token.WaitHandle];
            while (WaitHandle.WaitAny(handles) == 0)
            {
                if (stopping.IsCancellationRequested) break;
                try { activationRequested(); }
                catch (ObjectDisposedException) { break; }
                catch (InvalidOperationException) when (stopping.IsCancellationRequested) { break; }
            }
        });
    }

    public void Dispose()
    {
        stopping.Cancel();
        try { activation.Set(); } catch { }
        try { listener?.Wait(TimeSpan.FromSeconds(1)); } catch { }
        stopping.Dispose();
        activation.Dispose();
        if (IsPrimary)
        {
            try { mutex.ReleaseMutex(); } catch (ApplicationException) { }
        }
        mutex.Dispose();
    }
}
