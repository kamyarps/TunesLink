# TunesLink Privacy Policy

_Last updated: October 18, 2026_

TunesLink is an open-source remote control for the iTunes library on your own Windows PC. It
consists of the TunesLink Android app and the TunesLink Bridge for Windows. This policy covers
both, including the version distributed on Google Play.

## Summary

**The developer of TunesLink collects no data.** TunesLink has no accounts, no cloud service, no
analytics, no advertising, no crash reporting service, and no tracking. Nothing the app handles is
sent to the developer or to any third party.

## What the app handles, and where it goes

TunesLink connects your Android device directly to the TunesLink Bridge on a computer you control,
over your own private local network. The following information moves only between those two
devices:

| Information | Why | Where it is kept |
|---|---|---|
| Library metadata (song, album, artist, playlist, and genre names) and album artwork | To browse and display your library | Read from iTunes on your PC; cached on your Android device so the library opens quickly |
| Playback commands and playback state | To control and show what is playing | Sent between your devices only; not stored beyond the current state |
| The six-digit pairing code | To confirm that you are pairing with your own computer | Entered once; never stored |
| The Android device's manufacturer and model (for example "Google Pixel 9") and a random device identifier | So the bridge can show and remove paired devices | Stored by the bridge on your PC |
| A per-device access token and the bridge's certificate fingerprint | To keep the connection authenticated and encrypted | Stored on your Android device in Android Keystore-protected storage, and on your PC |
| The computer's local network address | To reconnect to your computer | Stored on your Android device |

All of this stays on your devices. TunesLink only connects to private local network addresses and
refuses connections to the internet. Traffic between the devices is encrypted with HTTPS and pinned
to the bridge's own certificate.

## Permissions

- **Internet and network state**: needed to open the local network connection to your computer and
  to notice when Wi-Fi changes. TunesLink does not contact internet servers.
- **Local network access** (Android 17 and newer): needed to find your computer on the local
  network and control it.

TunesLink does not request location, contacts, camera, microphone, storage, or nearby-device
permissions.

## Demo mode

The Google Play version includes a demo mode with a built-in sample library, so the app can be
tried without a computer. Demo mode makes no network connections.

## Removing your data

- **On Android:** choose **Forget computer**, or uninstall TunesLink. This deletes the saved
  connection, credentials, and cached library. Android backup is disabled for TunesLink.
- **On Windows:** remove the device in TunesLink Bridge, or uninstall the bridge.

Because the developer never receives any data, there is nothing for the developer to delete.

## Children

TunesLink is not directed to children and collects no personal information from anyone.

## Changes

Changes to this policy are published in this file, and its history is public on
[GitHub](https://github.com/kamyarps/TunesLink/commits/main/PRIVACY.md).

## Contact

Open an issue at <https://github.com/kamyarps/TunesLink/issues>, or see [SUPPORT.md](SUPPORT.md).

---

iTunes is a trademark of Apple Inc., registered in the U.S. and other countries. TunesLink is an
independent, open-source project and is not affiliated with, endorsed by, or sponsored by Apple Inc.
