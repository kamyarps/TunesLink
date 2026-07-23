using System.Text.Json;

namespace TunesLinkBridge;

/// <summary>UI preferences stored beside, but independently from, protocol credentials.</summary>
internal sealed class BridgePreferences
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string path;
    private readonly object gate = new();
    private readonly IAtomicFilePersistence persistence;
    private Settings settings;

    private sealed class Settings
    {
        public bool KeepRunningOnClose { get; set; } = true;
        public bool HasShownCloseToTrayTip { get; set; }
    }

    public BridgePreferences(string? configDirectory = null,
                             IAtomicFilePersistence? persistence = null)
    {
        string directory = configDirectory ?? BrandPaths.UserConfigDirectory();
        Directory.CreateDirectory(directory);
        path = Path.Combine(directory, "ui.json");
        this.persistence = persistence ?? AtomicFilePersistence.Instance;
        settings = Load();
    }

    public bool KeepRunningOnClose
    {
        get { lock (gate) return settings.KeepRunningOnClose; }
        set => _ = TrySetKeepRunningOnClose(value);
    }

    public bool HasShownCloseToTrayTip
    {
        get { lock (gate) return settings.HasShownCloseToTrayTip; }
        set => _ = TrySetHasShownCloseToTrayTip(value);
    }

    public PersistenceResult TrySetKeepRunningOnClose(bool value) => Update(
        current => current.KeepRunningOnClose == value,
        proposed => proposed.KeepRunningOnClose = value);

    public PersistenceResult TrySetHasShownCloseToTrayTip(bool value) => Update(
        current => current.HasShownCloseToTrayTip == value,
        proposed => proposed.HasShownCloseToTrayTip = value);

    private Settings Load()
    {
        try
        {
            return File.Exists(path)
                ? JsonSerializer.Deserialize<Settings>(File.ReadAllText(path)) ?? new Settings()
                : new Settings();
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("ui.preferences.invalid", exception,
                Path.GetDirectoryName(path));
            return new Settings();
        }
    }

    private PersistenceResult Update(Func<Settings, bool> unchanged, Action<Settings> apply)
    {
        lock (gate)
        {
            if (unchanged(settings)) return PersistenceResult.Success(changed: false);
            Settings proposed = Clone(settings);
            apply(proposed);
            try
            {
                persistence.WriteText(path, JsonSerializer.Serialize(proposed, JsonOptions));
                settings = proposed;
                return PersistenceResult.Success(changed: true);
            }
            catch (Exception exception)
            {
                BridgeDiagnostics.Record("ui.preferences.write", exception,
                    Path.GetDirectoryName(path));
                return PersistenceResult.Failure("preferences-write-failed");
            }
        }
    }

    private static Settings Clone(Settings source) => new()
    {
        KeepRunningOnClose = source.KeepRunningOnClose,
        HasShownCloseToTrayTip = source.HasShownCloseToTrayTip
    };
}
