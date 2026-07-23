using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace TunesLinkBridge;

internal sealed class BridgeTlsIdentity : IDisposable
{
    private const string CertificateFile = "bridge-identity.pfx";
    private static readonly byte[] ProtectedHeader = "TunesLink-DPAPI-V1\n"u8.ToArray();
    private static readonly byte[] ProtectionEntropy =
        SHA256.HashData("TunesLink bridge identity"u8.ToArray());
    private readonly X509Certificate2 certificate;

    public X509Certificate2 Certificate => certificate;
    public string Fingerprint { get; }
    public string DisplayFingerprint => FormatFingerprint(Fingerprint);

    public BridgeTlsIdentity(string? configDirectory = null)
    {
        string directory = configDirectory ?? BrandPaths.UserConfigDirectory();
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, CertificateFile);

        certificate = LoadOrCreate(path);
        Fingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData));
    }

    public bool Matches(X509Certificate2 candidate) =>
        CryptographicOperations.FixedTimeEquals(
            SHA256.HashData(candidate.RawData),
            SHA256.HashData(certificate.RawData));

    private static X509Certificate2 LoadOrCreate(string path)
    {
        X509KeyStorageFlags storageFlags = OperatingSystem.IsWindows()
            ? X509KeyStorageFlags.UserKeySet
            : X509KeyStorageFlags.EphemeralKeySet;
        if (File.Exists(path))
        {
            try
            {
                byte[] stored = File.ReadAllBytes(path);
                bool protectedAtRest = OperatingSystem.IsWindows()
                    && stored.AsSpan().StartsWith(ProtectedHeader);
                byte[] loadedPfx = protectedAtRest
                    ? WindowsDataProtection.Unprotect(
                        stored.AsSpan(ProtectedHeader.Length).ToArray(), ProtectionEntropy)
                    : stored;
                X509Certificate2 loaded;
                try
                {
                    loaded = X509CertificateLoader.LoadPkcs12(loadedPfx, null, storageFlags);
                }
                finally
                {
                    if (!ReferenceEquals(loadedPfx, stored))
                        CryptographicOperations.ZeroMemory(loadedPfx);
                    CryptographicOperations.ZeroMemory(stored);
                }
                if (loaded.HasPrivateKey && loaded.NotAfter.ToUniversalTime()
                        > DateTime.UtcNow.AddDays(30))
                {
                    if (OperatingSystem.IsWindows() && !protectedAtRest)
                    {
                        byte[] migratedPfx = loaded.Export(X509ContentType.Pfx);
                        try { ProtectAndWrite(path, migratedPfx); }
                        catch
                        {
                            loaded.Dispose();
                            throw;
                        }
                        finally { CryptographicOperations.ZeroMemory(migratedPfx); }
                    }
                    return loaded;
                }
                loaded.Dispose();
                File.Move(path, path + ".expired", true);
            }
            catch (Exception exception)
            {
                BridgeDiagnostics.Record("tls.identity_invalid", exception,
                    Path.GetDirectoryName(path));
                try { File.Move(path, path + ".invalid", true); } catch { }
            }
        }

        using RSA key = RSA.Create(3072);
        CertificateRequest request = new("CN=TunesLink Bridge", key,
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        OidCollection usages = new();
        usages.Add(new Oid("1.3.6.1.5.5.7.3.1"));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages, true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        using X509Certificate2 generated = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        byte[] pfx = generated.Export(X509ContentType.Pfx);
        try
        {
            X509Certificate2 loaded = X509CertificateLoader.LoadPkcs12(pfx, null, storageFlags);
            if (OperatingSystem.IsWindows()) ProtectAndWrite(path, pfx);
            else WriteAtomically(path, pfx);
            return loaded;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    private static void ProtectAndWrite(string path, byte[] pfx)
    {
        byte[] protectedPfx = WindowsDataProtection.Protect(pfx, ProtectionEntropy);
        try
        {
            byte[] stored = new byte[ProtectedHeader.Length + protectedPfx.Length];
            ProtectedHeader.CopyTo(stored, 0);
            protectedPfx.CopyTo(stored, ProtectedHeader.Length);
            try { WriteAtomically(path, stored); }
            finally { CryptographicOperations.ZeroMemory(stored); }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(protectedPfx);
        }
    }

    private static void WriteAtomically(string path, byte[] contents)
    {
        string temporary = path + ".tmp";
        File.WriteAllBytes(temporary, contents);
        File.Move(temporary, path, true);
    }

    private static string FormatFingerprint(string fingerprint)
    {
        string shortValue = fingerprint[..20];
        return string.Join("-", Enumerable.Range(0, 5)
            .Select(index => shortValue.Substring(index * 4, 4)));
    }

    public void Dispose() => certificate.Dispose();
}
