# KOSync for Boox

A standalone [KOReader-Sync (kosync)](https://github.com/koreader/koreader-sync-server)
client for Onyx Boox devices. It keeps **NeoReader** (Onyx's built-in reader) in sync
with any other kosync-speaking device — KOReader itself, or an Xteink X4 running
[CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader) firmware.

Forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin),
but repurposed: the original was a KOReader plugin plus a headless mirror app.
**KOReader is no longer required on the device.** This app watches Onyx's own
content provider for reading activity in NeoReader and talks to the sync server
directly.

## How it works

A foreground service observes Onyx's `Metadata` content provider
(`content://com.onyx.content.database.ContentProvider/Metadata`), with a
periodic poll as fallback:

- **Push** — when NeoReader's page position changes (debounced ~3 s so rapid
  page turns produce one request), the book's progress is converted to a
  percentage (`page / totalPages`) and PUT to the sync server.
- **Pull** — when a book is opened (its `lastAccess` bumps) the server is
  queried. If the remote position carries a **strictly newer timestamp** than
  the local one, the percentage is converted back to a page and written into
  the Onyx provider so NeoReader reflects it. Newest-timestamp-wins; the local
  position is never blindly overwritten.
- **Boot** — a `BOOT_COMPLETED` receiver restarts the service automatically.

### Cross-device book matching

Books are identified by **KOReader's partial-MD5 document hash**: MD5 over
1024-byte chunks read at offset 0 and at `1024 << (2*i)` for `i = 0..10`
(1 KB, 4 KB, 16 KB … 1 GB), skipping offsets past end-of-file. This matches
KOReader's `util.partialMD5()` and CrossPoint's binary match mode
byte-for-byte (note: KOReader's first chunk is offset **0**, not 256 — LuaJIT's
`lshift(1024, -2)` masks to zero). A filename-MD5 mode is also available for
setups that match by filename instead; pick the **same mode on every device**.

The unit test `KoReaderDocumentHashTest` locks the hash to reference values —
a mismatch here breaks book matching silently, with both devices happily
"syncing" to different document IDs.

### Position resolution

Onyx's provider only exposes `page/totalPages`, so pushes carry percentage
(and page number as the progress string) — no paragraph-level XPath. Pulling
into NeoReader rounds the remote percentage to the nearest page. This is the
same accepted resolution loss CrossPoint had before its XPath rework.

### How pulls reach NeoReader (verified on a Poke3, Android 10)

Two separate mechanisms, discovered the hard way:

- The `progress` column ("page/total") only drives the **library shelf**.
- NeoReader's actual reading position is `current_page_position_v2` inside
  the `extraAttributes` JSON — character-offset-like units, book-specific.
  The app learns the units by recording NeoReader-authored
  (position, percentage) pairs per book and scales pulled percentages
  accordingly (lands within a page).
- NeoReader only **reads** that field on a cold start; a cached process
  restores its private store and overwrites the row. Applying remote progress
  therefore kills NeoReader's background process first
  (`KILL_BACKGROUND_PROCESSES`, background-only — an active foreground
  reading session is never interrupted).

### Reading statistics

Reading done on other kosync devices is written into Onyx's statistics
provider (`OnyxStatisticsModel`) so it shows up in the Boox reading stats and
account. Requires a signed-in Onyx account. kosync only carries
percentage + timestamp, so per-page durations are **estimates** (the window
between syncs spread across the pages read, clamped to 15–120 s/page);
page counts and reading days are accurate. Synthetic rows carry a
`synced from <device>` comment so they can be told apart from NeoReader's
native records. Local NeoReader reading is recorded by Onyx natively and is
never touched. Known edge: if a newer-stamped remote position re-covers pages
already read locally, those pages can be double-counted.

### Manual sync

The settings screen has a "Manual sync" section for the most recently read
book: it shows the local and remote positions side by side, with **Apply
remote** / **Upload local** buttons that skip the newest-timestamp rule —
for the standoff where opening a book locally re-stamped `lastAccess` newer
than remote progress you actually want. Automatic sync is unaffected.

## Setup

1. Install the APK, long-press the icon and **Unfreeze** it (Onyx freezes new
   apps by default), then launch it.
2. Grant **All files access** when prompted — needed to read book files for
   the partial-MD5 hash.
3. Enter the same values you use in CrossPoint's KOReader Sync menu:
   - **Sync server URL** — e.g. `https://sync.koreader.rocks` or a self-hosted
     `http://<server-ip>:17200`. A bare host gets `http://` prepended, same as
     CrossPoint.
   - **Username** and **password** — only the MD5 hash of the password is
     stored (computed once at entry, identical to what CrossPoint sends as
     `x-auth-key`), never the plaintext.
4. Tap **Save & Authenticate** to round-trip test against the server.
5. **On Onyx devices, also check** Settings → Apps → this app → allow
   auto-start / background running. Onyx's own power management can kill or
   block the service after boot regardless of the manifest permissions —
   verify the persistent "Syncing reading progress" notification survives a
   reboot.

### Registering an account

The app has no register button. Create the account once from KOReader or
CrossPoint (both have a register flow), or via the server's own UI, then enter
the same credentials here.

## Known limitations

- **Calibre-Web-Automated**: CWA's kosync bridge authenticates with HTTP Basic
  using the *plaintext* password. Since this app deliberately stores only the
  MD5 hash, it sends the standard kosync `x-auth-user`/`x-auth-key` headers
  only. Stock kosync servers (and CrossPoint-compatible setups) work; CWA may
  not.
- Progress resolution is one page — see *Position resolution* above.
- A pull landing while NeoReader has the book open in the foreground cannot
  move the open reader; the synced position takes effect on the next cold
  open. If you read from the stale spot instead, that position legitimately
  wins as newest — use the manual sync buttons to recover.
- KOReader installed on the same device with the same kosync account will
  fight this app (it pushes its own positions, e.g. the cover page on open).
  Disable its kosync plugin or log it out.

## Building

```bash
cd android
./gradlew assembleDebug   # needs JDK 17+ and the Android SDK (compileSdk 36)
```

Release builds are signed when `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD` are provided via environment or `local.properties`.

## Credits

App icon by [ふにゃ猫 – funyaneko](https://iconbu.com/).

Forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin);
kosync protocol compatibility modeled on
[CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader)'s KOReaderSync client.
