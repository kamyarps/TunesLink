# Demo bridge verification

`TuneLinkDemoBridge` uses the real TLS, pairing, HTTP, and state-stream code with an in-memory music library. It never opens iTunes or modifies a user's library. Use a dedicated `--config-directory` for each test session.

Optional scenarios:

- `--large-library`: 720 songs, including a 120-track album and 601 albums/artists/genres. Scroll past the Android 480-item window and back to the beginning to verify forward and backward pagination. Expand **A Long Album** on a tablet to verify track paging inside the album detail.
- `--library-fault-file <path>`: while this file exists, library requests fail while playback state stays available. Open an uncached album to verify the error and retry UI. Remove the file and retry to recover. This option is confined to the demo harness.
- `--playback-fault-file <path>`: while this file exists, song selections and playback commands fail. Verify the shared error banner in Library, Search, Now Playing, and the tablet workspace, then dismiss it or remove the file and retry.
- `--artwork-fault-file <path>`: while this file exists, cover downloads fail. Device integration tests age saved covers by a day, restart the app, and verify that cached artwork survives refresh failures and recovers afterward.
- `--library-delay-ms <milliseconds>`: delay the first continuation request for songs/search to verify loading states and cancellation.
- `--legacy-state`: disable the state stream to exercise polling fallback.

For the Android emulator, connect manually to `10.0.2.2:<port>`. The default test pairing code is `123456`. Run the executable with `--discovery-port 0` to verify that recovery at a saved, reachable address works without UDP discovery.

Automated release regressions run with the existing Android unit tests and Windows `--self-test` command. They cover unavailable optional storage, revoked pairing recovery, lifecycle cancellation, bounded page windows, non-advancing pages, worker failure/recovery, large HTTP offsets, and absolute metadata/artwork cache expiry.

Storage and cancellation regressions also cover pairing responses arriving after cancellation or
shutdown, rollback while credentials are being saved, durable revocation retries, and re-pairing
with the same computer. Credentials and playback sequence commits run on one storage worker;
tests verify that later playback commands cannot overtake those commits or run after cancellation.
Artwork tests exercise queue saturation, removal of cancelled reads, cancellation during decoding,
and forgotten-computer cleanup while the cache is busy.

Run `python3 scripts/android-ui-contract-self-test.py` to check the device capture validator.
It covers API 23/31 song rows whose reported bounds are clipped to a scroll viewport, while
still rejecting undersized controls within the list, clipping on the wrong axis, and controls
outside the screen. CI runs these checks before the Android build.

Issue #4 regressions additionally cover album pages within artists/genres, distinct album artists,
authenticated scoped browsing, queue order with shuffle/repeat, and phone navigation history.

Release-audit regressions cover reconnect retries, abandoned browse cursors, shrinking
unversioned pages, long international collection names, pairing cooldowns, empty-search Back,
and sequenced playback cancellation. The TLS tests deliberately overlap a slow selection with
a newer one and send cancellation both before and after request arrival. The COM fixture also
fails during queue activation and verifies that the user's global mute setting is never changed.

The emulator integration suite checks cooldown expiry and command errors across phone and
tablet surfaces. Release publishing waits for CI, which runs this suite, to pass on the tagged
commit. It then installs only the signed, optimized release APK for the viewport and launch
smoke checks, without repeating the debug suites.
When a previous GitHub APK exists, the release workflow also checks its package ID, signing
certificate, and version code, then installs it on compatible emulators and upgrades to the new
signed APK without removing the installation.

## Manual release checklist

- [ ] On a phone paired with the previous GitHub release, open the new `TunesLink.apk` from a
  browser or file manager. Confirm that Android offers an update and the pairing still works.
- [ ] On an Android 17 (API 37) device or working emulator, grant local-network permission and
  verify pairing. Deny access, retry, and verify the Settings action after permanent denial.
  Revoke access after pairing, restart the app, and verify that granting it restores the connection.

API 37 is also required in the [device matrix](../.github/workflows/android-device.yml), including
the [integration script](../scripts/android-emulator-integration.sh) permission scenarios. Release
publishing depends on that matrix passing; a failed emulator boot does not count as validation.
Use Command-line Tools 22.0 when creating the `android-37.0` AVD and check its `.ini` contains
`target=android-37.0`. Older tools can write `target=android-0`, causing repeated SurfaceFlinger
`hasReadColorBufferDma` crashes before app testing. The workflow checks this metadata explicitly.

## Live iTunes queue regression

`dotnet run --project test-support/TuneLinkItunesQueueTest --configuration Release -- --run-live`

Run with iTunes already open. This opt-in test temporarily changes playback, imports six silent WAV fixtures, and creates uniquely named QA playlists. It exercises the real bridge controller for Albums, Artists, Genres, and Playlists: starting at a middle track, choosing another track in the same queue, Next, Previous, natural completion, and shuffle preservation. It also verifies artist/genre album drill-down. The test restores the original track, position, playing/paused state, volume, mute, shuffle and repeat, and removes its library entries and playlists in `finally`. iTunes' pre-existing Up Next list is not exposed by COM and cannot be snapshotted or restored by this test. Temporary WAV files and the test's isolated cache remain under the printed/unique temporary directory for diagnosis.

The live test also checks Previous before the initially selected track, repeat-all wrapping,
repeat-off stopping at the collection end, preserving pause/position during mode changes, and
restoring queue identity and earlier tracks after the controller restarts. Recovery metadata is
stored alongside the bridge's optional library cache.
