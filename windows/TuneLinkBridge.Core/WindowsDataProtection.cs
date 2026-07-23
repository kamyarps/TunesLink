using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace TunesLinkBridge;

internal static class WindowsDataProtection
{
    private const uint UiForbidden = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        internal int Size;
        internal IntPtr Data;
    }

    internal static byte[] Protect(byte[] value, byte[] entropy) =>
        Transform(value, entropy, protect: true);

    internal static byte[] Unprotect(byte[] value, byte[] entropy) =>
        Transform(value, entropy, protect: false);

    private static byte[] Transform(byte[] value, byte[] entropy, bool protect)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows data protection requires Windows");
        GCHandle valueHandle = default;
        GCHandle entropyHandle = default;
        DataBlob output = default;
        IntPtr description = IntPtr.Zero;
        try
        {
            valueHandle = GCHandle.Alloc(value, GCHandleType.Pinned);
            entropyHandle = GCHandle.Alloc(entropy, GCHandleType.Pinned);
            DataBlob input = new() { Size = value.Length, Data = valueHandle.AddrOfPinnedObject() };
            DataBlob optionalEntropy = new()
            {
                Size = entropy.Length,
                Data = entropyHandle.AddrOfPinnedObject()
            };
            bool succeeded = protect
                ? CryptProtectData(ref input, null, ref optionalEntropy, IntPtr.Zero,
                    IntPtr.Zero, UiForbidden, out output)
                : CryptUnprotectData(ref input, out description, ref optionalEntropy, IntPtr.Zero,
                    IntPtr.Zero, UiForbidden, out output);
            if (!succeeded)
                throw new CryptographicException(new Win32Exception(
                    Marshal.GetLastWin32Error()).Message);
            byte[] result = new byte[output.Size];
            Marshal.Copy(output.Data, result, 0, result.Length);
            return result;
        }
        finally
        {
            if (output.Data != IntPtr.Zero) LocalFree(output.Data);
            if (description != IntPtr.Zero) LocalFree(description);
            if (entropyHandle.IsAllocated) entropyHandle.Free();
            if (valueHandle.IsAllocated) valueHandle.Free();
        }
    }

    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(ref DataBlob dataIn, string? description,
        ref DataBlob optionalEntropy, IntPtr reserved, IntPtr prompt, uint flags,
        out DataBlob dataOut);

    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(ref DataBlob dataIn, out IntPtr description,
        ref DataBlob optionalEntropy, IntPtr reserved, IntPtr prompt, uint flags,
        out DataBlob dataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr memory);
}
