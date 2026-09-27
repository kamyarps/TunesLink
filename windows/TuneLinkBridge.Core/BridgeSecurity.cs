using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Globalization;

namespace TunesLinkBridge;

internal sealed class BridgeSecurity
{
    public static readonly TimeSpan PairCodeLifetime = TimeSpan.FromMinutes(10);
    internal const int MaxPairedDevices = 2;
    // Six digits allow a million guesses; a fresh code after this many wrong ones keeps any one
    // code's exposure small even across the rate limiter's windows.
    internal const int MaxFailedAttemptsPerCode = 10;
    private const int ConfigReadAttempts = 5;
    private static readonly TimeSpan ConfigReadRetryDelay = TimeSpan.FromMilliseconds(100);
    private const int MaxLoadablePairedDevices = 12;
    private static readonly JsonSerializerOptions ConfigurationJson = new()
    {
        WriteIndented = true
    };

    private sealed class Configuration
    {
        public string BridgeId { get; set; } = Guid.NewGuid().ToString("N");
        public List<PairedDevice> Devices { get; set; } = [];
    }

    internal sealed class PairedDevice
    {
        public string ClientId { get; set; } = "";
        public string Name { get; set; } = "Android phone";
        public string TokenHash { get; set; } = "";
        public DateTimeOffset PairedAt { get; set; } = DateTimeOffset.UtcNow;
        public DateTimeOffset LastSeenAt { get; set; } = DateTimeOffset.UtcNow;
    }

    internal enum PairingStatus
    {
        Succeeded,
        Rejected,
        DeviceLimitReached,
        PersistenceFailed,
        PairingClosed
    }

    internal readonly record struct PairingResult(PairingStatus Status, string Token = "")
    {
        public bool Succeeded => Status == PairingStatus.Succeeded;
    }

    private readonly object gate = new();
    private readonly string configPath;
    private readonly TimeProvider timeProvider;
    private readonly IAtomicFilePersistence persistence;
    private Configuration configuration;
    private string pairCode;
    private DateTimeOffset pairCodeExpiresAt;
    private int failedAttempts;
    private volatile bool pairingOpen = true;

    public event Action? Changed;

    /// <summary>
    /// Whether a phone may pair right now. The host closes pairing while the code is not on
    /// screen; a closed bridge refuses every code without consuming the code's attempt budget.
    /// Open by default so headless, demo, and test bridges can pair.
    /// </summary>
    public bool PairingOpen
    {
        get => pairingOpen;
        set => pairingOpen = value;
    }
    public string BridgeId { get { lock (gate) return configuration.BridgeId; } }
    public string PairCode
    {
        get
        {
            EnsureCurrentPairCode();
            lock (gate) return pairCode;
        }
    }
    public DateTimeOffset PairCodeExpiresAt
    {
        get
        {
            EnsureCurrentPairCode();
            lock (gate) return pairCodeExpiresAt;
        }
    }

    public BridgeSecurity(string? configDirectory = null, string? forcedPairCode = null,
        TimeProvider? timeProvider = null, IAtomicFilePersistence? persistence = null)
    {
        if (forcedPairCode is not null && !IsPairCode(forcedPairCode))
            throw new ArgumentException("A forced pairing code must contain exactly six ASCII digits.",
                nameof(forcedPairCode));
        string directory = configDirectory ?? BrandPaths.UserConfigDirectory();
        Directory.CreateDirectory(directory);
        configPath = Path.Combine(directory, "config.json");
        this.timeProvider = timeProvider ?? TimeProvider.System;
        this.persistence = persistence ?? AtomicFilePersistence.Instance;
        configuration = LoadConfiguration();
        pairCode = forcedPairCode ?? GeneratePairCode();
        pairCodeExpiresAt = this.timeProvider.GetUtcNow().Add(PairCodeLifetime);
    }

    public IReadOnlyList<PairedDevice> Devices
    {
        get
        {
            lock (gate)
            {
                return configuration.Devices
                    .Select(d => new PairedDevice
                    {
                        ClientId = d.ClientId,
                        Name = d.Name,
                        TokenHash = d.TokenHash,
                        PairedAt = d.PairedAt,
                        LastSeenAt = d.LastSeenAt
                    }).ToList();
            }
        }
    }

    public bool TryPair(string suppliedCode, string clientId, string deviceName, out string token)
    {
        PairingResult result = Pair(suppliedCode, clientId, deviceName);
        token = result.Token;
        return result.Succeeded;
    }

    public PairingResult Pair(string suppliedCode, string clientId, string deviceName)
    {
        bool wellFormed = IsPairCode(suppliedCode) && IsValidClientId(clientId);
        EnsureCurrentPairCode();
        PairingResult result;
        bool rotated = false;
        lock (gate)
        {
            if (!pairingOpen) return new PairingResult(PairingStatus.PairingClosed);
            if (timeProvider.GetUtcNow() >= pairCodeExpiresAt)
            {
                RotatePairCodeLocked();
                rotated = true;
                result = new PairingResult(PairingStatus.Rejected);
            }
            else if (!wellFormed || !CryptographicOperations.FixedTimeEquals(
                         Encoding.UTF8.GetBytes(suppliedCode),
                         Encoding.UTF8.GetBytes(pairCode)))
            {
                if (++failedAttempts >= MaxFailedAttemptsPerCode)
                {
                    RotatePairCodeLocked();
                    rotated = true;
                }
                result = new PairingResult(PairingStatus.Rejected);
            }
            else
            {
                result = CompletePairingLocked(clientId, deviceName);
                rotated = result.Succeeded;
            }
        }
        if (rotated) Changed?.Invoke();
        return result;
    }

    private PairingResult CompletePairingLocked(string clientId, string deviceName)
    {
        bool replacingExistingDevice = configuration.Devices.Any(device =>
            string.Equals(device.ClientId, clientId, StringComparison.Ordinal));
        if (!replacingExistingDevice && configuration.Devices.Count >= MaxPairedDevices)
            return new PairingResult(PairingStatus.DeviceLimitReached);

        string token = Base64Url(RandomNumberGenerator.GetBytes(32));
        string hash = HashToken(token);
        Configuration proposed = Clone(configuration);
        proposed.Devices.RemoveAll(d =>
            string.Equals(d.ClientId, clientId, StringComparison.Ordinal));
        proposed.Devices.Add(new PairedDevice
        {
            ClientId = clientId,
            Name = SafeDeviceName(deviceName),
            TokenHash = hash,
            PairedAt = timeProvider.GetUtcNow(),
            LastSeenAt = timeProvider.GetUtcNow()
        });
        if (!TrySaveConfiguration(proposed))
            return new PairingResult(PairingStatus.PersistenceFailed);
        configuration = proposed;
        RotatePairCodeLocked();
        return new PairingResult(PairingStatus.Succeeded, token);
    }

    /// <summary>Checks a token without recording use, for work done on another phone's behalf.</summary>
    public bool IsAuthorized(string? token)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length < 32) return false;
        byte[] supplied = Convert.FromHexString(HashToken(token));
        lock (gate) return FindDeviceLocked(supplied) is not null;
    }

    public bool ValidateToken(string? token)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length < 32) return false;
        byte[] supplied = Convert.FromHexString(HashToken(token));
        lock (gate)
        {
            PairedDevice? match = FindDeviceLocked(supplied);
            if (match is null) return false;
            if (timeProvider.GetUtcNow() - match.LastSeenAt > TimeSpan.FromMinutes(5))
            {
                Configuration proposed = Clone(configuration);
                PairedDevice? proposedMatch = proposed.Devices.FirstOrDefault(device =>
                    string.Equals(device.TokenHash, match.TokenHash, StringComparison.Ordinal));
                if (proposedMatch is not null)
                {
                    proposedMatch.LastSeenAt = timeProvider.GetUtcNow();
                    if (TrySaveConfiguration(proposed)) configuration = proposed;
                }
            }
            return true;
        }
    }

    private PairedDevice? FindDeviceLocked(byte[] suppliedHash) =>
        configuration.Devices.FirstOrDefault(device =>
        {
            try
            {
                return CryptographicOperations.FixedTimeEquals(
                    suppliedHash, Convert.FromHexString(device.TokenHash));
            }
            catch { return false; }
        });

    public void RegeneratePairCode()
    {
        lock (gate) RotatePairCodeLocked();
        Changed?.Invoke();
    }

    public bool EnsureCurrentPairCode()
    {
        bool changed = false;
        lock (gate)
        {
            if (timeProvider.GetUtcNow() >= pairCodeExpiresAt)
            {
                RotatePairCodeLocked();
                changed = true;
            }
        }
        if (changed) Changed?.Invoke();
        return changed;
    }

    public PersistenceResult TryForgetAll()
    {
        PersistenceResult result;
        lock (gate)
        {
            Configuration proposed = Clone(configuration);
            proposed.Devices.Clear();
            if (!TrySaveConfiguration(proposed))
                return PersistenceResult.Failure("security-write-failed");
            configuration = proposed;
            RotatePairCodeLocked();
            result = PersistenceResult.Success(changed: true);
        }
        Changed?.Invoke();
        return result;
    }

    public PersistenceResult TryForgetDevice(string tokenHash)
    {
        PersistenceResult result;
        lock (gate)
        {
            Configuration proposed = Clone(configuration);
            bool removed = proposed.Devices.RemoveAll(device =>
                string.Equals(device.TokenHash, tokenHash, StringComparison.Ordinal)) > 0;
            if (!removed) return PersistenceResult.Success(changed: false);
            if (!TrySaveConfiguration(proposed))
                return PersistenceResult.Failure("security-write-failed");
            configuration = proposed;
            result = PersistenceResult.Success(changed: true);
        }
        Changed?.Invoke();
        return result;
    }

    public PersistenceResult TryForgetToken(string? token)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length < 32)
            return PersistenceResult.Success(changed: false);
        byte[] supplied = Convert.FromHexString(HashToken(token));
        PersistenceResult result;
        lock (gate)
        {
            Configuration proposed = Clone(configuration);
            bool removed = proposed.Devices.RemoveAll(device =>
            {
                try
                {
                    return CryptographicOperations.FixedTimeEquals(
                        supplied, Convert.FromHexString(device.TokenHash));
                }
                catch { return false; }
            }) > 0;
            if (!removed) return PersistenceResult.Success(changed: false);
            if (!TrySaveConfiguration(proposed))
                return PersistenceResult.Failure("security-write-failed");
            configuration = proposed;
            result = PersistenceResult.Success(changed: true);
        }
        Changed?.Invoke();
        return result;
    }

    private Configuration LoadConfiguration()
    {
        string? stored = ReadConfigurationText();
        if (stored is null) return NewConfiguration();
        try
        {
            Configuration? loaded = JsonSerializer.Deserialize<Configuration>(stored);
            if (loaded is null || !IsValidConfiguration(loaded))
                throw new JsonException("The bridge security configuration is invalid");
            if (loaded.Devices.Count > MaxPairedDevices)
            {
                loaded.Devices = loaded.Devices
                    .OrderByDescending(device => device.LastSeenAt)
                    .Take(MaxPairedDevices)
                    .ToList();
                _ = TrySaveConfiguration(loaded);
            }
            return loaded;
        }
        catch (Exception exception) when (exception is JsonException or NotSupportedException)
        {
            BridgeDiagnostics.Record("config.invalid", exception,
                Path.GetDirectoryName(configPath));
            PreserveInvalidConfiguration();
            return NewConfiguration();
        }
    }

    // Only content that was read and found invalid is quarantined. A file that cannot be read,
    // for example while a backup or antivirus scanner holds it, still holds every pairing, so
    // after a short retry the bridge fails to start instead of discarding them.
    private string? ReadConfigurationText()
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                return File.Exists(configPath) ? File.ReadAllText(configPath) : null;
            }
            catch (Exception exception) when (exception is FileNotFoundException
                                                  or DirectoryNotFoundException)
            {
                return null;
            }
            catch (Exception exception) when (exception is IOException
                                                  or UnauthorizedAccessException)
            {
                if (attempt < ConfigReadAttempts)
                {
                    Thread.Sleep(ConfigReadRetryDelay);
                    continue;
                }
                BridgeDiagnostics.Record("config.read", exception,
                    Path.GetDirectoryName(configPath));
                throw;
            }
        }
    }

    private void PreserveInvalidConfiguration()
    {
        if (!File.Exists(configPath)) return;
        string backup = configPath + ".invalid-" + DateTime.UtcNow.ToString(
            "yyyyMMddHHmmssfff", CultureInfo.InvariantCulture);
        try { File.Move(configPath, backup, false); } catch { }
    }

    private Configuration NewConfiguration()
    {
        Configuration fresh = new();
        SaveConfiguration(fresh);
        return fresh;
    }

    private void SaveConfiguration(Configuration value)
    {
        string json = JsonSerializer.Serialize(value, ConfigurationJson);
        persistence.WriteText(configPath, json);
    }

    private bool TrySaveConfiguration(Configuration value)
    {
        try
        {
            SaveConfiguration(value);
            return true;
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("config.write", exception,
                Path.GetDirectoryName(configPath));
            return false;
        }
    }

    private static Configuration Clone(Configuration source) => new()
    {
        BridgeId = source.BridgeId,
        Devices = source.Devices.Select(device => new PairedDevice
        {
            ClientId = device.ClientId,
            Name = device.Name,
            TokenHash = device.TokenHash,
            PairedAt = device.PairedAt,
            LastSeenAt = device.LastSeenAt
        }).ToList()
    };

    private void RotatePairCodeLocked()
    {
        failedAttempts = 0;
        pairCode = GeneratePairCode();
        pairCodeExpiresAt = timeProvider.GetUtcNow().Add(PairCodeLifetime);
    }

    private static string GeneratePairCode() =>
        RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6", CultureInfo.InvariantCulture);

    private static bool IsPairCode(string code) =>
        code is { Length: 6 } && code.All(character => character is >= '0' and <= '9');

    internal static bool IsValidClientId(string clientId) =>
        clientId is { Length: >= 16 and <= 80 }
        && clientId.All(character => char.IsAsciiLetterOrDigit(character)
                                     || character is '.' or '_' or '-');

    internal static bool IsValidBridgeId(string bridgeId) =>
        bridgeId is { Length: >= 1 and <= 128 }
        && bridgeId.All(character => char.IsAsciiLetterOrDigit(character)
                                      || character is '.' or '_' or '-');

    private static bool IsValidConfiguration(Configuration value)
    {
        if (!IsValidBridgeId(value.BridgeId) || value.Devices is null
            || value.Devices.Count > MaxLoadablePairedDevices)
        {
            return false;
        }

        HashSet<string> clientIds = new(StringComparer.Ordinal);
        HashSet<string> tokenHashes = new(StringComparer.OrdinalIgnoreCase);
        foreach (PairedDevice device in value.Devices)
        {
            if (!IsValidClientId(device.ClientId)
                || device.Name is null
                || !string.Equals(device.Name, SafeDeviceName(device.Name), StringComparison.Ordinal)
                || !IsValidTokenHash(device.TokenHash)
                || device.PairedAt == default
                || device.LastSeenAt == default
                || device.LastSeenAt < device.PairedAt
                || !clientIds.Add(device.ClientId)
                || !tokenHashes.Add(device.TokenHash))
            {
                return false;
            }
        }
        return true;
    }

    private static bool IsValidTokenHash(string tokenHash) =>
        tokenHash is { Length: 64 } && tokenHash.All(char.IsAsciiHexDigit);

    private static string SafeDeviceName(string name)
    {
        string safe = new string((name ?? "Android phone")
            .Where(c => !char.IsControl(c)).Take(60).ToArray()).Trim();
        return string.IsNullOrWhiteSpace(safe) ? "Android phone" : safe;
    }

    private static string HashToken(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
