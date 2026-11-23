# Skill Course map availability and recovery

Course entries keep their authored MD5 and object counts even when a map is missing or another revision is installed. The client detects the distinction, keeps course viewing useful, and offers one sequential repair action through the existing beatmap mirror. Exact matches are required for a full run. This entire flow belongs to C2/C3 and is complete by the offline C5 MVP; it requires no new tournament-server endpoint.

## Availability states

| State | Detection | Manager and song selection behavior |
| --- | --- | --- |
| Checking | No completed scan for the current library generation | Keep View available; show checking, avoid a false-ready Play action |
| Ready | Exact course MD5 is installed, file exists, and object counts match | Show the exact difficulty; allow Play only if every course entry is ready |
| Outdated | Exact MD5 absent, but installed artist/title/mapper/difficulty tuple matches | List `Map is outdated` with map identity; show the installed candidate in View with an Outdated label; never treat it as course-ready |
| Missing | Neither exact MD5 nor the four-field metadata tuple matches an installed file | List `Map is missing`; omit that entry from the song-selection list; keep course View enabled |
| Invalid | Exact candidate has inconsistent counts, cannot be parsed/read or has stale DB/file identity | Show an explanatory blocked issue; require repair/reimport and validation instead of starting a run |

An outdated candidate can be selected for ordinary single-map practice with a clear wrong-revision label, using the authored mods. It cannot advance a course or generate a valid component for the authored MD5. If multiple outdated candidates match, expose the candidates for inspection instead of choosing a storage overwrite target arbitrarily. A course whose entries are all missing opens an empty filtered view with guidance; an empty filter must never expose the whole library.

The four-field lookup uses the manifest's stored romanized `artist`, `title`, `mapper`, `difficulty` and BeatmapInfo's `artist`, `title`, `creator`, `version`. Romanized-display preferences and localized UI strings do not change identity. Proposed normalization is Unicode NFC, trim surrounding whitespace, and locale-independent case folding on each field, without dropping punctuation or fuzzy matching. Freeze this policy with fixtures. Matching only title or online ID is insufficient. Metadata is a diagnostic fallback and never overrides a failed hash check.

Treat actual installed files as authoritative, not stale database rows. A deleted file cannot make an entry ready. Reconcile a row whose stored MD5 differs from the on-disk bytes before classification. If the correct MD5 exists in more than one location, prefer a valid existing library selection deterministically and retain all legitimate rows; do not deduplicate files by deleting them.

## Scan timing and publication

Add a `CourseAvailabilityScanner` over immutable library snapshots plus targeted file validation. Publish results with a library-generation identifier and discard late scans for older generations. A course result snapshot stores availability per authored entry and counts by state; this is local derived state outside the course JSON/hash.

Required triggers:

1. After an `.odc` course transaction is committed, scan its entries against the current library snapshot.
2. After every successful beatmap import has updated the `.osu` files, reconciled Room rows and reloaded the in-memory library, publish one import-completed event with affected directories/MD5s. This includes manual ACTION_VIEW/picker/archive scans, ordinary mirror downloads, multiplayer downloads and course repairs. Hook course scanning here, not just on manager open or network-download completion.
3. After each repair job imports successfully, rescan affected entries before advancing the queue. Rescan the whole affected course set at batch completion, cancellation or partial failure.
4. On library refresh/deletion, restored storage permission, app startup and preflight, invalidate/recheck affected state. An exact preflight byte/count check remains mandatory even after a cached Ready result.

A failure to import must not publish a successful library generation. If the current scanner reports errors only by logging, add a typed success/failure result at the relevant seam. Coalesce repeated import events and update affected courses in the background; use MD5 and metadata indexes/snapshots instead of performing a full filesystem hash pass for every card on the UI thread. Cross-course updates matter: installing a newer revision can satisfy one course while making another revision unavailable.

Scanning never starts downloads or rewrites the portable definition. An active run keeps its frozen map selection; queue file mutations until gameplay/practice/replay readers release the affected files. If an external process changes a frozen map, terminate through the normal interruption/load-error path rather than silently switching content.

## Manager action and recovery feedback

Expand the course card or provide an issue subpanel inside the course manager listing each unresolved entry with its authored position, artist/title/difficulty, and `Map is outdated` or `Map is missing`. Keep invalid/read failures clearly distinct. Show issue counts on the card and preserve the full course map count even when missing entries are omitted from View.

While any entry is unresolved, replace that card's Play icon/action with **Download missing maps**; it includes outdated entries requiring the course revision. Keep View, export and management usable, including on locked imports. Lock prevents course edits, not installing the files the course requires.

The replacement button remains visible after 404, wrong-version response, missing beatmap ID, network failure or confirmed automatic unavailability. Never restore Play until a post-import scan proves all entries ready. When no automatic target is available, clicking the retained action opens the unresolved list and manual-import guidance rather than starting an infinite retry loop. Users may retry after the mirror changes or manually import the correct version; the common import-completed hook must clear the issue automatically.

Show batch progress such as `Downloading set 2 of 4`, the current map/set, importing/scanning status and per-item outcomes. A failed job does not hide other results or mark the entire batch successful. Retain a completion summary for unresolved entries: map unavailable, required version unavailable, no online ID, canceled or import failed, with the required MD5 and metadata available to copy for manual recovery.

## Existing downloader and importer evidence

| Source and symbol | Observed constraint |
| --- | --- |
| `BeatmapMirror.handleBeatmapLink` | Resolves a difficulty ID to BeatmapSetModel using the configured mirror. The manifest's `beatmapid` is a difficulty ID, not the set ID used for archive download. |
| `RoomScene.updateBeatmap` | Multiplayer uses `BeatmapListing.mirror.download.request(parentSetID, !Config.isPreferNoVideoDownloads())`, then BeatmapDownloader. Reuse this mirror/no-video policy without depending on a multiplayer room. |
| `BeatmapDownloader.download` | Singleton has `isDownloading`, returns Unit and silently ignores a call while busy. It exposes no awaitable import-completion result for a course queue. |
| `BeatmapDownloader.onDownloadEnd` | Extracts with `FileUtils.extractZip`, then calls `MainActivity.loadBeatmapLibrary()`. Download success alone is not proof of the course hash or correct DB refresh. |
| `MainActivity.loadBeatmapLibrary` | Ordinary archive imports collect `forceImportedBeatmaps`, call `LibraryManager.scanDirectory(forceImportedBeatmaps)`, then `LibraryManager.loadLibrary()`. The direct downloader extraction does not itself add its directory to that force set. |
| `LibraryManager.LibraryDatabaseManager.start` | Skips directories already imported unless explicitly forced. Inserts parsed records with `insertAll`; removes rows for missing whole directories, but does not reconcile renamed/deleted difficulties inside an existing directory. |
| `IBeatmapInfoDAO` in `Beatmaps.kt` | Primary key is `(filename,setDirectory)`, with REPLACE insertion and row update. Replacing an identical filename can refresh its MD5; a renamed `.osu` leaves the old key unless removed explicitly. |
| `FileUtils.extractZip`, `extractBeatmapset` | Uses archive basename as destination; the latter strips ` [no video]`, the former does not. Extraction writes directly into the target folder. Invalid-ZIP handling can delete the destination folder, so an existing installed folder must not be used for validation/staging. |
| `BeatmapCache` | Caches parsed content by MD5; the BeatmapInfo overload can use the stored hash. Invalidate old/new affected identities and refresh references after file/DB updates. |

These are reasons to extend the existing import path with an explicit result/target/forced-refresh contract. A loop that merely calls the current void downloader once per map can silently drop jobs, leave stale hashes and report the wrong version as installed.

## Sequential download queue

1. Snapshot current unresolved authored entries. Recheck availability before each job so a manual import or earlier shared-set download can satisfy later entries.
2. For each entry with `beatmapid`, call the selected mirror's existing `handleBeatmapLink` to resolve its parent set. A null ID or lookup failure produces a per-entry manual-recovery outcome; it does not remove the button.
3. Group entries by mirror and resolved set ID. Download each needed archive once per batch, even when the course contains several difficulties from that set or several courses share it. Do not assume a set ID proves a particular revision exists in today's archive.
4. Extend BeatmapDownloader with an explicit completion/result or shared job handle that reports started/busy, download, validated import, library commit, cancellation and failure. Preserve existing caller behavior with a compatible wrapper if useful. Use one global downloader reservation across listing, multiplayer and course requests; do not implement a separate competing Boolean.
5. Process one set at a time. Wait for download, staged validation, storage/DB import, library reload and course rescan before releasing the reservation and starting the next set. An already busy external download queues/reports waiting instead of dropping the request. Avoid polling UI visibility or `isDownloading` to infer completion.
6. Validate each requested revision in staging. If the mirror archive lacks the required MD5/counts, record Required version unavailable and leave its installed candidate intact. Import other exact requested difficulties from the same archive only if their complete file/asset dependencies can be applied safely. Continue unrelated jobs after ordinary not-found/wrong-version failures; stop on a storage-integrity failure requiring recovery.
7. Cancellation stops scheduling new jobs and cancels the current transfer at a safe point. An import commit already underway completes or rolls back before releasing its reservation; do not cancel between a file replacement and DB reconciliation.
8. Recompute all relevant course statuses and show a summary. Never automatically launch the course when the batch finishes; the action changes back to Play only when all entries are ready, and the player starts it explicitly.

The queue is not the course-result upload queue. It uses existing public beatmap services and is independently testable on an AVD before any C6 server change.

## Updating installed maps safely

Use a shared import/update operation reached by both mirror repair and ordinary `.osz` import so manual recovery receives identical DB refresh and rescan guarantees. Add targeted behavior rather than rewriting unrelated library systems.

### Staging and target resolution

- Download into a unique job staging path, outside installed set folders. Validate the archive and bound extracted sizes/paths before writing installed storage. A corrupt archive must never enter the existing FileUtils branch that deletes an installed destination.
- Parse the staged `.osu` files and compute actual MD5/counts. For automatic course repair, require the target revision before replacing an outdated installed `.osu`; downloading today's different revision is a failed repair, even if the map ID and metadata match.
- Resolve a replacement target using the current installed candidate's `(setDirectory,filename)` and verified online difficulty ID when available, plus the metadata tuple. Do not choose a destination from the downloaded archive's display filename alone. An ambiguous match must preserve existing files and request a target decision or install the validated revision in a distinct managed directory.
- Prefer in-place overwrite for one unambiguous outdated difficulty. If its staged filename differs, handle rename and both old/new primary keys explicitly. Preserve unrelated sibling difficulties, manually added `.osu` files, local collection/set-directory associations and beatmap options.
- Track the target map's audio/background/storyboard/sample dependencies. If replacing a shared asset would break untouched siblings and cannot be reconciled safely, install the validated map with its dependencies in a separate managed directory rather than silently altering those siblings. Never delete a whole set merely to update one requested difficulty.

### Files and database consistency

Filesystem operations and SQLite are not one atomic transaction. Add a small durable import journal identifying touched paths, backups, old/new row keys, staged digests and commit phase, so startup can finish or roll back an interrupted update.

1. Acquire the shared import lock and confirm no active reader owns affected files. Capture affected rows and create backups only for paths the validated update will change. Preserve path containment and symlink rules throughout.
2. Promote validated `.osu`/dependency files from staging using temporary files and same-filesystem rename where available; journal every touched path. Do not announce import success yet.
3. Parse the actual final files into fresh BeatmapInfo values. In one Room transaction, upsert changed/new primary keys, remove only superseded/deleted keys in the import's declared affected set, and reconcile the affected directory. Preserve untouched sibling rows; never run a database-wide delete/rebuild.
4. For renames, delete the old `(filename,setDirectory)` row only after its replacement is validated; for overwrite with unchanged key, REPLACE/UPDATE refreshes MD5, counts, metadata and derived attributes. Do not merely patch MD5 while retaining old counts/star-rating data. Do not leave both the old and new key pointing at nonexistent files.
5. On file, parse or DB failure, restore touched files and rows using the journal. A failure after a DB commit must also be recoverable on restart. Keep recovery state until disk and rows agree; never leave Play enabled on a partially updated set.
6. Invalidate affected old/new BeatmapCache and difficulty caches, cancel stale calculations, reload LibraryManager and song-menu references, and publish one successful import-completed event with affected directories/identities. Ensure the forced-refresh path actually reparses an already imported directory.
7. Rescan affected courses against the new library generation; only then acknowledge that job as repaired. Release the reservation, clean successful staging/backups according to recovery policy, and move to the next set.

The journal belongs to the importer, not the portable course. Keep old ScoreInfo/course attempt/replay metadata attached to their original hashes; updating the installed map must never rewrite historical scores to claim the new revision. Other courses referring to an overwritten revision are rescanned and may become outdated. Preserve their definitions and results.

## Acceptance before the C5 MVP

- Automated scanner fixtures cover exact/missing/outdated, disk-versus-row disagreement, count corruption, conservative metadata normalization, multiple candidates, language preference independence and all-missing View.
- Import tests prove the scan runs after the library/DB commit for manual, ordinary mirror, multiplayer and course paths; late scans cannot overwrite newer results.
- Queue tests prove one concurrent downloader job, shared-set deduplication, waiting behind an external job, continuation on per-item failure, safe cancellation, and no Play restoration after a wrong version/404.
- File/Room integration tests cover same-key overwrite, renamed difficulty, removed stale row, preserved siblings/assets/options/collections, invalid archive against an installed folder, and crash/failure in each journal phase.
- AVD runs show issue labels, persistent Download missing maps after failed recovery, successful manual-import rescan, a correct map overwrite and full-course launch only after all entries resolve.
- Existing mirror tests or a controllable mirror transport supply sequential download fixtures; no live course backend, official approval, score submission or physical phone is required to accept these features through C5.
