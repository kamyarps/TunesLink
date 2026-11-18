using System.Text;

namespace TunesLinkBridge;

internal interface IAtomicFilePersistence
{
    void WriteText(string path, string contents);
    void Write(string path, Action<Stream> write);
}

internal sealed class AtomicFilePersistence : IAtomicFilePersistence
{
    public static AtomicFilePersistence Instance { get; } = new();

    private AtomicFilePersistence() { }

    public void WriteText(string path, string contents) =>
        Write(path, stream => stream.Write(Encoding.UTF8.GetBytes(contents)));

    public void Write(string path, Action<Stream> write)
    {
        string? directory = Path.GetDirectoryName(path);
        if (string.IsNullOrWhiteSpace(directory))
            throw new ArgumentException("A persisted file must have a parent directory", nameof(path));
        Directory.CreateDirectory(directory);
        string temporary = Path.Combine(directory,
            "." + Path.GetFileName(path) + "." + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            using (FileStream stream = new(temporary, FileMode.CreateNew, FileAccess.Write,
                       FileShare.None, 16 * 1024, FileOptions.SequentialScan))
            {
                write(stream);
                stream.Flush(flushToDisk: true);
            }

            if (File.Exists(path))
                File.Replace(temporary, path, destinationBackupFileName: null,
                    ignoreMetadataErrors: true);
            else
                File.Move(temporary, path);
        }
        finally
        {
            try { File.Delete(temporary); } catch { }
        }
    }
}

internal readonly record struct PersistenceResult(bool Succeeded, bool Changed,
                                                   string? FailureCode = null)
{
    public static PersistenceResult Success(bool changed) => new(true, changed);
    public static PersistenceResult Failure(string failureCode) => new(false, false, failureCode);
}
