# Frozen Icon Keeper

Standalone Java LSPosed module for the supplied Oplus Launcher 15.8.21 build.
The only supported scope is `com.android.launcher`.

## What is fixed

- Snapshots are taken before launcher deletion and during `LoaderCursor.markDeleted`.
- The delete hook applies to the package-task deletion path rather than only assuming one callback operation.
- Both snapshot and restore paths require launcher user 0 and the main profile serial.
- `PackageUpdatedTask` and `OplusPackageUpdatedTask` are supported; the latter is the task enqueued by this Oplus build.
- Restore is limited to update/un-suspend events, so uninstall/remove events cannot resurrect an app.
- Existing identical intents are treated as already restored, preventing duplicates.
- A conflicting ID or provider failure keeps the pending snapshot for a later retry.
- A deliberately occupied cell or deleted folder is treated as a hard skip and the stale snapshot is discarded.
- The launcher is allowed to remove the icon normally while the package is frozen.
- No launcher database clear, launcher force-stop, or global/system-server hook is used.

## Build

The repository includes `.github/workflows/build.yml`. Push to GitHub, open **Actions**, run **build**, and download the `FrozenIconKeeper-apk` artifact. The workflow builds a debug APK using compile SDK 34 and Java 17.

## Install and test

Install the artifact APK, enable it in LSPosed, and scope it only to **System Launcher** (`com.android.launcher`). The app now has a **Frozen Icon Keeper — Diagnostics** screen. Open it, tap **Clear Logcat**, restart the launcher, freeze one normal main-user app with Ice Box root mode, unfreeze it, then tap **Refresh**. Use **Copy Logs** to copy the complete result.

The useful sequence is: `attached` -> `hooked ...` -> `delete path` or `saved from loader` -> `task complete` -> `restore candidate` -> `restore result`. If `delete path` and `saved from loader` never appear, the launcher callback path is different on the device. If `saved` appears but no `restore candidate`, the unfreeze task operation is different. If `restore result inserted=true` appears but the icon is still misplaced, the launcher reload is overwriting the provider insert.

Then test a folder and a batch of apps. Do not test first with widgets or a work-profile app.

## Logs and rollback

Search LSPosed logs for `FrozenIconKeeper`. To roll back, disable or uninstall the module and restart the launcher. The module never clears launcher data. If a provider write fails, the pending snapshot remains in launcher-private preferences and is retried on a later supported package update.

## Limitations

This source was not tested on a physical OxygenOS 15 phone in this environment. It intentionally handles only main-user shortcut rows, not widgets or work-profile rows. It relies on the exact class and provider names from the supplied `com.android.launcher` 15.8.21 APK; a different launcher build may require new signatures or provider checks.
