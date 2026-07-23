# Security policy

## Supported versions

Security fixes are made on the latest release. Users should upgrade both the Android app and Windows bridge together.

## Reporting a vulnerability

Please do not open a public issue for a vulnerability that could expose pairing tokens, permit unauthenticated control, or escape the local-network boundary. Use GitHub's private vulnerability reporting feature for this repository. If it has not yet been enabled, contact the maintainer privately through the contact method on their GitHub profile.

Include the affected version, component, reproduction steps, and impact. Do not include a real bearer token, pairing code, personal IP address, or music-library data.

## Trust model

TunesLink is designed for a trusted private home network:

- The bridge accepts loopback, IPv4 link-local, RFC 1918, and carrier-grade NAT source addresses; it rejects public source addresses.
- UDP discovery exposes only the bridge ID, computer name, port, protocol version, and public TLS certificate fingerprint.
- Android verifies that the selected bridge presents its advertised certificate, then pins the full certificate fingerprint. The user authorizes pairing with the temporary six-digit code.
- The six-digit pairing code expires after 10 minutes and rotates after expiry, successful
  pairing, **New code**, or **Forget all**.
- Five failed pairing attempts from one address trigger a one-minute cooldown. Twenty failed
  attempts across all addresses in one minute trigger a five-minute global cooldown.
- Paired phones receive a random 256-bit token. Windows stores only its SHA-256 hash; Android protects the token using Android Keystore.
- Playback endpoints, including the SSE state stream, require `Authorization: Bearer <token>`
  over TLS 1.2 or TLS 1.3. Long-lived streams revalidate the token before every event or heartbeat.
- The Windows configuration contains random client IDs, device names, timestamps, and token
  hashes, not plaintext tokens or library contents.

## Important limitations

UDP discovery is unauthenticated. The pairing code is the user-facing authorization step, while certificate matching and pinning happen internally. Use TunesLink on a trusted home LAN, allow the bridge through Windows Firewall on **Private** networks only, and never port-forward bridge ports `45831` or `45832`.

The Windows bridge targets the legacy iTunes COM automation interface. It does not control Apple Music for Windows.

If a network or phone may have been compromised, choose **Forget all** in the bridge and pair again on a trusted network.

The bridge automatically replaces a damaged identity or one within 30 days of expiry. Android detects the certificate change and requires the phone to pair again with the current code.
