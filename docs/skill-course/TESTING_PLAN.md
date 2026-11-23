# Skill Course testing plan

Accept the complete offline C5 MVP with deterministic client tests and Android AVDs, without a course server deployment or physical device. C6 separately adds server evidence/submission/global leaderboard tests and real-device ADB acceptance. A correct total alone is insufficient: C5 proves map availability/order, safe recovery, gauge history, effective mods and durable local component replays; C6 additionally proves verified idempotent server attempts.

This plan follows the [shared contract](SHARED_CONTRACT.md), [client implementation plan](CLIENT_IMPLEMENTATION_PLAN.md) and [server implementation plan](SERVER_IMPLEMENTATION_PLAN.md). Test names and files below are proposed additions unless identified as existing. Course features are not implemented yet, so their tests have not been executed.

## Observed environment and checks

Inspection on 22 November 2026 found:

- Client Gradle wrapper `8.14.4`; root project is the Android application, with `:AndEngine` and `:LibBASS` modules. Do not prepend an invented `:app:` module to commands.
- Android `compileSdk 35`, minimum API 24, target API 29, NDK `22.1.7171670`, Java/Kotlin bytecode target 17. Existing tests use JUnit 4.13.2, Robolectric 4.14.1 and Room testing 2.7.2 under `tests/test/kotlin/`, with fixtures under `tests/test/resources/`.
- `tests/test/kotlin/com/osudroid/data/MigrationTest.kt` uses Robolectric and MigrationTestHelper, and tests the entire registered migration chain. `TestResourceManager.kt` supplies the existing fixture pattern.
- ADB is available at `E:\dev\scrcpy-win64-v3.0\adb.exe`. An elevated `adb devices -l` completed and listed no connected devices. An earlier sandboxed probe failed to start/connect to the daemon; that did not establish device availability.
- Android SDK is `C:\Users\ADMIN\AppData\Local\Android\Sdk`. `emulator.exe -list-avds` listed `Pixel_8`, `Pixel_9_Pro`, and `Pixel_Fold_API_35`. No emulator was launched and their configured API/system-image details were not queried.
- The default Java command resolves through the Oracle Java 8 path. Android Studio JBR exists at `C:\Program Files\Android\Android Studio\jbr` and reported OpenJDK 21.0.10. The observed client compile used that JBR and passed. Target bytecode is still 17; this is not a claim that the shell default Java can build the project.
- Server `osu-droid-tournament-server` has `pnpm build`/TypeScript and a placeholder failing `test` script. No server build, database write or service test was run on the SMB checkout.

### Default gameplay failure restoration check

The separate requested change removed exactly two unconditional `stat.canFail = false` assignments from GameScene. Source inspection confirmed `StatisticV2.canFail` remains initialized to `true`, and no replacement assignment was introduced. `git diff --check` for the changed file passed. `gradlew.bat compileDebugJavaWithJavac --console=plain` completed with `BUILD SUCCESSFUL`, using Android Studio JBR. The output included toolchain/resource/deprecation warnings; no compile failure was observed.

Actual HP failure, fail animation and retry were not exercised on a device in this planning pass. Include them in the first baseline smoke test before course gameplay changes.

## Test layers and ownership

C1 through C5 use JVM/Robolectric and AVD tests only; a missing phone or unimplemented server is not an incomplete C5 gate. Course creation/import/export/management, all gameplay settings, map recovery, practice, final results/breakdown and local history/replay must work by C5. With maps installed, run acceptance with the network disabled and no account login. Mirror download cases may use the existing public mirror or a controlled mirror-transport fixture and require no new tournament service. No course-submit or course-global-leaderboard request is allowed in the C5 build/path.

| Layer | Purpose | Execution location |
| --- | --- | --- |
| Shared vectors | Manifest/hash, mods, events, EX final floor, HP/timing expectations | JVM reference checks in C1-C5; identical TypeScript execution/parity is a C6 gate |
| Pure client unit tests | Validation, aggregation, shuffle permutation, state transitions, timer and mod policy | Existing root `testDebugUnitTest` suite |
| Room and Android-bound tests | Migrations, transactions, URI handling, lock state, persistence and recovery | Existing Robolectric patterns; instrumentation where a real provider is needed |
| Server unit and HTTP integration tests | C6 server-change gate: auth/ownership, registry, receipts, verification and queries | Isolated server checkout with disposable MongoDB and replay directories; not required for C5 |
| Emulator UI and lifecycle tests | Real file-manager import, engine rendering, Android lifecycle and end-to-end sequence | Existing AVDs; add API-specific AVDs only if coverage requires them |
| Physical device tests | C6 real-device gate: native audio/touch, frame pacing, providers, lifecycle and upload end-to-end | Connected device selected explicitly by ADB serial; not required for C1-C5 |

No existing Android instrumentation runner/source setup was established from the build file. Configure a runner, test dependencies and source set deliberately before claiming `connectedDebugAndroidTest` covers the feature. Native AndEngine/BASS behavior requires a device/AVD; Robolectric alone cannot establish it.

## Shared fixture set

Place client fixtures under `tests/test/resources/skillcourse/` with independently specified expected values. In C6, bring the same checked-in bytes/expected values into the isolated server repo and execute cross-runtime parity; C5 does not wait for that repository/runtime. Include a fixture revision and checksum list so drift is detectable. Use small original/generated `.osu` and audio assets that may be redistributed; compute real MD5s and object counts from their bytes instead of using invented map hashes.

| Fixture | Content and purpose |
| --- | --- |
| `definition-v1` | Complete portable manifest with the user's default gauge/EX values and APIMod settings |
| `canonicalization` | Reordered JSON keys/whitespace, non-ASCII strings, numeric mod settings, duplicate keys, large/invalid numbers and unpaired surrogates |
| `map-circle` | Short map with controlled final judgments and no slider nested events |
| `map-slider` | Head, tick, repeat and tail opportunities, including a final slider judgment independent from tail tracking |
| `map-spinner` | Spinner final judgment plus bonus points; proves bonuses are not EX/accuracy objects |
| `map-break-intro` | Long intro, authored break, final object and lead-out to separate active time from display delays |
| `same-set-other-difficulty` | Two difficulties in one set; course contains only one, proving exact view filtering |
| `missing-revision` | Same online difficulty ID but different `.osu` bytes/hash; proves download does not substitute revisions |
| `availability-catalog` | Exact hash, metadata-only outdated, fully missing, multiple tuple matches, stale DB/file hash and object-count corruption |
| `repair-archives` | Exact revision, wrong revision, invalid ZIP, shared-set difficulties, same-filename overwrite and renamed difficulty, with preserved sibling maps/assets |
| `trace-passed`, `trace-failed`, `trace-interrupted` | Ordered event/timing traces, native replay, HP boundaries and exact expected aggregate |
| `attempt-upload` | Frozen manifest/order/mods, component receipts/digests and finalized signed-envelope shape |

Expected values must be written independently from the implementation under test. A fixture that computes its expected result using the same production accumulator cannot establish correctness.

### Required arithmetic oracles

1. **Pooled accuracy:** map A contains one 300, map B contains nine misses. Combined accuracy is `300 / 3000 = 10%`, not the average of 100% and 0%. Check numerator/denominator and round only in UI.
2. **Default global gauge:** start at 80/100; apply `hit300`, `hit100`, `hit50`, `hit0`, `sliderbreak`, `sliderend`. Expected HP is `81,81,80,77,76,76`. The next map starts at 76, not 80 or 100. With the default EX table, the same trace contributes `2` EX.
3. **Final-only EX floor:** set sliderbreak to -2 and sliderend to -4 in the EX table. The preceding six-event trace has raw EX `-4`; display/persist that running/raw value and signed contributions, but publish final EX `0`. `[-1,+2]` must produce final `1`, distinguishing it from per-event flooring (`2`). Map contributions `[-3,+2]` must produce final `0`, distinguishing it from summing separately floored map values (`2`). Zero-total attempts save normally; C6 rejects a negative published EX claim while retaining valid negative raw evidence.
4. **Terminal global failure:** starting HP 2, final miss delta -3 reaches 0 and fails. A later queued heal cannot revive the course or start the next map. A batch that contains both a real break and a missed tail applies both deltas once.
5. **Same-timestamp clamp:** proposed batch policy at HP 99/100 with +2 and -3 at the same timestamp yields 98 after summing, clamping once and checking failure. Freeze this rule before writing a fixture that assumes a callback order.
6. **Native score sum:** use fixed independently verified native displayed scores, for example 123456 and 234567, and expect 358023 with per-map combo reset. Separate native-scoring fixtures must verify the fork's actual ScoreV2 and mod multipliers; stubbing values alone does not establish native formula parity.
7. **Active speedrun time:** active spans of 5000ms and 3000ms total 8000ms even with 1500ms of loads, 2000ms configured transition and a 700ms allowed pause. An authored in-map break inside the 5000ms span remains included. Active engine stalls remain included. Lead-out is excluded.
8. **Random order:** a recorded order `[2,0,1]` is a permutation and preserves each original index's speed/mod binding. `[2,0,0]` is invalid even if map titles or object counts coincide. Do not use platform default RNG output as a cross-language oracle.

## Client automated coverage

### Codec and hashing

Test all enum values, required fields, exact type checking, every boundary value, empty drafts versus playable/exportable manifests, unknown schema/fields, duplicate keys and mods, invalid acronyms/settings, asymmetric incompatibility, and duplicate map membership. Verify exact byte-size/nesting limits without allocating an unbounded input string first.

Hash tests must show equal hash for key-order/whitespace variants and different hash for changed order, mods/settings, gauge, EX, pause/skip, speedrun/random flags, whitelist, author/name or map metadata. Test imported/exported APIMod JSON is an array, not a JSON string, and default settings do not disappear on round-trip. Check actual RFC canonicalization vectors, not just ASCII key sorting.

### Mod policy and practice context

Use existing ModHashMap/ModUtils test patterns for copied settings, null/default Difficulty Adjust values, whitelist additions, required authored settings and incompatibility in both directions. Validate settings before any clamping/drop behavior. Test speedrun's speed-only exception with an empty whitelist, minimum/maximum/step, authored DT/HT/NC/CS replacement semantics and rejection of unsupported variable-rate mods.

Test applying a map's mods before a second-tap gameplay start, rapid A/B selection while calculations finish out of order, and restoring pre-view mods/filter/sort/ordinary-board state. View must omit fully missing entries, show outdated candidates with a wrong-revision label, and show an empty state when all entries are missing, without leaking unrelated siblings/the whole library. Full-course Play stays blocked until every entry is exact. In C6, separately test the extra course leaderboard mode and stale-response protection; that mode is not a C3/C5 requirement.

### Availability scanning and map recovery

Test exact MD5 precedence, four-field metadata fallback versus no match, normalization/case/whitespace, Unicode/romanized-display independence, multiple candidates, null IDs, stale rows and deleted files. Metadata-only matches are outdated even when object counts or online IDs match. Exact-hash/count inconsistencies are blocked and reported.

Assert the common rescan hook runs after successful `.odc` commit and after every map import's file/Room/library commit: manual file/picker, archive directory scan, ordinary mirror, multiplayer and course recovery. Verify it does not publish Ready on failed extraction/DB write, cannot apply an older generation over a newer scan, and refreshes other affected courses too.

Use a fake downloader/import completion interface to prove one active job, waiting behind listing/multiplayer use, difficulty-ID-to-set resolution, shared-set deduplication, and next-job start only after validation/import/rescan. Cover mixed missing/outdated lists, 404, wrong revision, unavailable ID, retry, cancel and manual import. The Download missing maps action remains after failed automatic recovery and cannot become Play until all entries are ready. No completion automatically starts gameplay.

Follow [the recovery plan](MAP_AVAILABILITY_AND_RECOVERY.md) for disk/Room integration fixtures: same-key overwrite updates actual bytes/MD5/counts/derived metadata; renamed `.osu` removes only the superseded row; other difficulties/assets/options/collections survive; invalid archive cannot delete an installed set. Inject errors and process death at each journal phase, then verify file/row agreement, cache invalidation and rescan before acknowledging success. Force a refresh of an already imported folder to catch the current scan-skip hazard. These are C2/C3/C5 gates on JVM/Robolectric/AVD, not C6 backend gates.

### Repository and migration

Extend MigrationTest with migration from database v5 to the new version and the entire supported chain. Prepopulate beatmaps, ordinary scores, collections, options and mod presets, migrate, and assert their contents are unchanged. Verify exported Room schema is tracked and matches the entity definitions.

Test transaction rollback on malformed import, ordered insert/reorder/remove, same-name distinct hashes, idempotent reimport, default imported lock and new-course unlock, locked mutation prevention below the UI layer, badge invalidation on any content edit, and snapshot immutability after starting a run. Simulate replay staging, rename and DB failures independently; no acknowledged component may reference a missing replay.

### Coordinator and policies

Drive a pure coordinator with fake loader/storage and clock interfaces. Cover every state and meaningful failure transition: exact-once map advance; double completion; late load completion after cancel; pause during loading; transition 0; nonzero delay with load finishing earlier/later; save failure; missing map mid-run; quit; immediate HP zero; Perfect/Sudden Death without auto-retry; process death and fresh attempt retry.

Run each scoring mode against every gauge, including one-map and multi-map courses. Check standard HP resets while global HP persists; none never fails from HP; global suppresses native passive/judgment HP and Easy lives. Test circle/slider/spinner final judgments, nested successes, all nested failures, tail misses, simultaneous objects, and spinner bonus filtering. Include both live and replay-derived event streams and demand the same aggregate.

Use a fake monotonic clock to test pauses, clock changes, active stalls, rate changes, intros, breaks and completion lead-out. Verify time freezes after a terminal event and cannot be reduced by changing the wall clock. Test no-pause focus loss as the chosen interrupted outcome, not a hidden pause/resume path.

### Results and uploads

For C5, verify course results cannot make any course submission/leaderboard request, call legacy single-map submission for the aggregate, or insert an aggregate into ScoreInfo; component detail/replay inspection is read-only. Cover empty/failed/partial/unplayed rows, signed live/raw EX with final minimum zero, very large score formatting, pooled accuracy, speedrun time, full-course Retry and Back restoration. Test minus-sign fallback under skins with no minus asset, including negative-to-positive transitions; no missing sign or `x` substitution is acceptable.

In C6, upload-queue tests cover process restart, loss of response after server commit, replay receipt reuse, duplicate finalize, throttling and Retry-After, session expiry, account switch, unsupported server, disk full and permanent evidence rejection. Queued files must survive temporary failure; two queue triggers must not upload two different attempts for the same stable ID. None of these server-dependent cases blocks C5's durable local result acceptance.

### Commands

Use a configured JDK compatible with the existing Gradle/AGP setup. These commands describe the implementation verification sequence; only the compile recorded above was run in this planning pass.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests 'com.osudroid.skillcourse.*' --console=plain
.\gradlew.bat testDebugUnitTest --tests 'com.osudroid.data.MigrationTest' --console=plain
.\gradlew.bat compileDebugJavaWithJavac --console=plain
.\gradlew.bat assembleDebug --console=plain
```

Run existing mod/scoring/import tests when those seams change, then the full JVM suite before release. Run lint for modified Android resources/manifest/UI. Avoid repeated full builds once useful checks pass unless code changes or failures justify them. Record environment/toolchain errors separately from regressions; do not repair unrelated failures as part of Skill Course work.

## Server automated coverage

This entire section is the C6 server-change gate. Complete and accept the C5 offline MVP before requiring these checks or deploying new course APIs.

Use an isolated server checkout, disposable database name, temporary replay root and test app factory. No test may connect to production AliceDB, official account write endpoints or the SMB deployment listener. Stub account reads where appropriate; integration-test actual database atomicity on the deployment's supported MongoDB topology.

| Area | Required assertions |
| --- | --- |
| Definition/hash | Same Kotlin/TypeScript vectors, exact schema/mod validation, hash mismatch rejection, unknown custom course accepted as unofficial |
| Approval | Normal user cannot approve; edit changes identity; revocation/cached status correct; author string cannot impersonate operator ownership |
| Auth/ownership | Missing/expired/wrong session, wrong owner attempt access, cross-user stable ID collision, replay visibility and no credential logging |
| Receipt upload | Size/digest limits, chunked/unknown-size streams, stream abort, temp cleanup, identical retry, conflicting digest, component index overflow and path injection |
| Finalization | Missing/mixed evidence, wrong order/map/mod/rate, repeated finalize and lost response, concurrent claim/finalize and immutable envelope conflict |
| Verification | Forged totals, HP drop-to-zero then heal, lost break/tail events, invalid pause/seek, unsupported replay, altered map revision, malformed serialized/zip content and bounded decoding |
| Attestation | Missing/invalid signature, wrong domain/course/attempt/replay digest/order/ruleset, nonce replay if tickets required; bypass stays outside verified production board |
| Retention | Two identical-score plays with different attempt IDs retained; lower scores retained; best replacement cannot delete earlier replay; failed prefix stored without ranking as completed |
| Exact arithmetic | Negative raw EX/contributions accepted with exactly one final floor at 0; negative published claims rejected; >2^53 scores round-trip losslessly, rational accuracy ties, speedrun active time and deterministic sort ties |
| Query/detail | Real attempt IDs, correct course/ruleset partition, bounded cursor pagination, stable ties, ownership and component replay linkage |
| Recovery | Crash before/after rename, receipt insert, finalize and verification commit; job lease expiry; metadata/storage restore; best-board cache rebuild |
| Compatibility | Shared login/attestation/update integrations retain the required behavior, capability flags reflect deployment, obsolete Dan routes follow the agreed retirement response, and Dan data remains preserved until an archival decision |

Current server `pnpm test` intentionally exits as a placeholder. Add a real test script and runner configuration before using it as an acceptance command. In the isolated checkout, run `pnpm exec tsc --noEmit`, the newly configured contract/HTTP/verification suite, and `pnpm build`. Use the actual agreed lockfile/package manager; do not generate a new lockfile on the deployment share as a side effect of testing.

## Android emulator and device runbook

### Select a target and install

Use [Android's ADB guidance](https://developer.android.com/tools/adb) for explicit serial selection, installation and logging. AVDs are installed but not running based on the observed device list; choose an AVD and verify its API/ABI after boot. Test API 24 compatibility and API 35 behavior at minimum across the final matrix; add an API 24 AVD if none of the installed images covers it.

Example commands for later execution, not actions already taken:

```powershell
$adbPath = 'E:\dev\scrcpy-win64-v3.0\adb.exe'
$emulatorPath = 'C:\Users\ADMIN\AppData\Local\Android\Sdk\emulator\emulator.exe'
& $emulatorPath -list-avds
Start-Process -WindowStyle Hidden -FilePath $emulatorPath -ArgumentList @('-avd', 'Pixel_8', '-no-window', '-no-snapshot-save')
& $adbPath devices -l
$deviceSerial = '<serial returned by devices -l>'
& $adbPath -s $deviceSerial shell getprop ro.build.version.sdk
& $adbPath -s $deviceSerial shell getprop ro.product.cpu.abi
& $adbPath -s $deviceSerial shell getprop sys.boot_completed
$debugApks = @(Get-ChildItem -LiteralPath 'D:\dev\osu-droid\build\outputs\apk\debug' -Filter '*.apk')
if ($debugApks.Count -ne 1) { throw 'Select the intended debug APK explicitly.' }
& $adbPath -s $deviceSerial install -r $debugApks[0].FullName
```

Determine application ID from the built APK/metadata before launch. Expected debug namespace/suffix combination is `ru.nsu.ccfit.zuev.osuplus.tournament.debug`; verify it for the artifact under test. Do not uninstall or clear app data to work around a test failure on a user's physical device. Use an isolated debug installation and fixture course names.

### Baseline smoke and file association

1. Launch normal single-player gameplay before course changes. Confirm HP can now reach the ordinary fail path; inspect fail animation, retry and Back. Check ordinary multiplayer behavior was not accidentally changed by the default restoration.
2. Put known fixture `.odc` files in Downloads using `adb push`. Open the file using the real system or third-party file manager and record that osu!droid appears in the chooser. A direct `am start -n ...` cannot prove chooser association.
3. Repeat with JSON MIME, octet-stream MIME, the custom MIME, uppercase extension, spaces/non-ASCII names, and an opaque content URI. Test a real provider plus a test provider that supplies no filename, denies access, returns a null stream or throws mid-read.
4. Test cold-start, warm-start, second import through `onNewIntent`, import while a course is playing, exact duplicate import and same-name changed content. The active run cannot be replaced.
5. Test picker import and export, cancellation, write failure and reimport. The reexported portable manifest has the same course hash; local lock and official cache are not exported.

An exported fixture opened through a shell file URI is a useful route smoke test only. For content URIs, use an actual provider/read grant; constructing an arbitrary `content://` string with ADB does not supply a readable document.

### Management and practice acceptance

- Long-press a specific difficulty in a set with multiple difficulties, create a course, and add with DA/custom settings active. Change mods and remove; membership removes by MD5.
- Verify imported default lock, disabled membership, unlocked edit/reorder, compact conditional gauge/EX fields, saved values after toggling modes, author default, long names, Unicode/romanized preference and error messages.
- Inspect cards on phone and foldable/small-window layouts. No clipped actions, inaccessible kebab or overlapping touch targets. Check content descriptions and focus order.
- View the resolved course difficulties; fully missing entries are omitted, outdated candidates labeled and limited to normal practice. Check an all-missing course is empty, select rapidly and inspect authored mods, then restore previous filters/mods/selection. Course global leaderboard behavior is a separate C6 test.
- Trigger scans through all import paths. Inspect the manager's per-map missing/outdated list and Play replacement. Repair multiple maps sequentially, including two in one set, and verify progress plus file/DB/cache refresh. Test 404/unavailable ID/network failure/cancel/wrong revision, persistent Download missing maps and manual-import guidance. Import the exact file manually and verify automatic rescan restores Play only when every map resolves.

### Full run acceptance matrix

Execute all twelve gauge/scoring combinations on short deterministic fixtures, plus focused combinations of flags rather than every possible Boolean cross product. Include these cases:

| Case | Required visible and stored result |
| --- | --- |
| One-map course | One aggregate, one component, no extra transition or fabricated second map |
| Three-map ordered course | A then B then C, no intermediate result screen, per-map mods reset correctly, final breakdown has all three |
| Standard failure on B | Course stops on B, C unplayed, no map-only retry path |
| Global carry and failure | Exact fixture HP on boundary; no passive drain or extra life; both break/tail deltas if emitted |
| None plus many misses | Completes despite HP loss, still records misses and valid EX/accuracy |
| Speedrun mixed rates | Choices frozen before start, time equals active spans, no loading/transition/lead-out contribution |
| Randomized speedrun | Realized order and original-index rate binding match history, replays and server detail |
| Pause allowed | Timer suspends/resumes once; resume does not alter totals or reset HP |
| Pause forbidden | Back uses exit flow; Home/focus loss interrupts according to frozen policy; no hidden continuation |
| Zero and long transition | Zero has no artificial loader timeout; nonzero waits at least configured delay while allowing required load time |
| Auto skip on/off | Skip occurs once at safe boundary, active-time start matches rules, authored break still counted |
| Signed raw and zero-floor EX | Live/raw negatives and component contributions display with a minus; final total floors once at 0 and persists locally; server upload checked only in C6 |
| Final results and history | Correct aggregate plus scrollable per-map scores; opening/replaying a row cannot resubmit |
| Offline completion by C5 | Logged-out, network-disabled run still supports all settings, final breakdown, component replay/history and restart durability, with no course submit/global-board requests |
| C6 online completion | After C5 acceptance, session-aware retry creates one verified/explicitly unverified server attempt and the global leaderboard uses the actual attempt ID |

Autoplay or scripted injection may help deterministic diagnostic runs, but those runs must not be submitted as verified player scores. Pair automated fixture traces with manual real-input runs. Evidence hooks must be debug-only and disabled in the production verification path.

### Failure injection and recovery

For C1-C5, use a disposable emulator profile or isolated debug data for force-stop/process-death/disk-pressure experiments. Capture active state, component count, replay digests and import-journal state. Kill during gameplay, save, transition, map-file promotion, Room reconciliation and availability rescan. After restart, expect interrupted gameplay, recoverable local results and a completed/rolled-back map import before Ready is published. In C6, add kill points during upload, after receipt acknowledgement and after finalize but before its response; expect idempotent upload recovery.

For C5, test network loss/timeouts/cancel against map-recovery transport and run the complete installed-map course with all networking disabled. For C6, test slow uploads, throttling, expired login and account switch using staging endpoints, and confirm originating-player ownership plus retention of earlier evidence. Do not change global physical-device network or refresh-rate settings for unrelated tests.

Run a long course and a maximum supported definition to inspect memory/resource release, transition latency, audio cleanup, touch options, storyboard/video jobs and replay-file growth. Compare heap/PSS after warmup and after many components; investigate sustained growth rather than imposing an invented device-independent numeric threshold. Global HP and timing must remain deterministic at both low and high frame rates.

## Evidence and acceptance gates

For each C5 end-to-end run, record client revision, course fixture hash, ruleset, AVD serial/model/API/ABI, chosen order/mods/rates, availability state/repair outcomes, gameplay outcome, component totals, raw/final EX and replay/evidence digests. Server IDs/revisions and verification are not applicable and must not be requested as C5 evidence. In C6 add server revision/attempt ID, verifier status and the physical device details. Save targeted screenshots/video and filtered logs to a task evidence directory. Use screenshot capture/pull tools that preserve binary bytes on Windows; do not pipe PNG bytes through legacy PowerShell text redirection. Redact sessions, account credentials and attestation material from logs.

### C5 offline MVP gate

1. Client contract/hash/mod/arithmetic reference vectors pass, including signed running EX and exactly one final floor at zero. TypeScript execution is deferred to C6.
2. Database migration preserves existing data; creation/editor/locking/membership and `.odc` import/export pass on AVD.
3. All import paths trigger availability scans after a successful file/Room/library commit. Missing/outdated states, omitted missing entries and all-missing View behave correctly.
4. Sequential mirror recovery, set deduplication, existing-folder overwrite, renamed-row cleanup, preserved siblings and journal recovery pass. Wrong/unavailable revisions keep the download action and manual-import path; exact manual import rescans successfully.
5. Authored/allowed mods, every gauge/scoring combination, speedrun, random order, pause/skip/transitions and ordinary practice work on the engine. No offline feature is deferred beyond C5.
6. Fail, abort, focus loss, load/save errors and cancellation cannot advance or revive a terminal run.
7. Aggregate results, scrolling breakdown, signed component contributions, nonnegative final EX, component replay and local history remain correct after restart; viewing has no result-write side effect.
8. With every map installed, logged-out and network-disabled AVD runs complete without course submit/global-leaderboard/capability requests or login/attestation prerequisites.
9. Run the mandatory engine/UI cases on at least one suitable installed AVD and cover minimum-API/current-API compatibility using AVDs. A physical device is not required and its absence does not leave C5 pending.
10. Relevant ordinary gameplay/collections/import/replay regressions and final diff review pass; preserve the stashed refresh-rate work and unrelated existing changes.

Passing these gates is acceptance of the offline MVP. Server work or a connected phone must not be added as an implicit prerequisite.

### C6 server change gate

After C5, implement/test the course API, registry, durable upload/finalization, verifier and global leaderboard in the isolated backend checkout. Require identical cross-runtime vectors, owner/session isolation, retry/concurrency retention, actual-attempt detail/replay linkage and staging end-to-end submission. Invalid/missing/unsupported evidence cannot appear as verified, and official definition recognition alone cannot verify a play. Confirm EX published-score floor and raw evidence agree with C5, and preserve logged-out/offline behavior.

### C6 real device ADB gate

Separately require a representative connected physical device selected by serial to pass real-input course play, signed EX rendering, file-provider import, map replacement/rescan, native audio/touch/frame pacing, pause/focus handling, results/replay and staging submission/global leaderboard. Emulator automation does not satisfy this physical-device gate. If no device is available, C6 remains pending while the accepted C5 MVP remains complete.

### C7 rollout gate

Require both C6 gates, final combined regressions, data backup/recovery and deployment review before production online rollout. C7 cannot be used to postpone required offline functionality or AVD acceptance beyond C5.

## Required C5 UI revision acceptance (23 November)

Follow [UI and preferences revision](UI_AND_PREFERENCES_REVISION.md). Compare the aggregate directly with ordinary results on the same skin: assets, six summed counts, selected metric, verdict replacing grade, maps below, no duplicate reason text. Page the themed breakdown both ways through played/unplayed entries and replay a partial component without writing/submitting a new score. Check long metadata, zero/signed EX contribution, narrow screens and missing skin assets.

Exercise speed +/- from 0.50 to 3.00 by exact 0.05 steps, manual half-step rounding/clamping, invalid input, keyboard dismissal and process-restart persistence. Verify the selected rate survives native mod/replay serialization. Save different optional mods/rates for the same map in two courses and for different maps in one course; reboot, reorder/edit the course, remove an allowed mod, then ensure identities and validation remain correct. Reuse the native mod selector: disallowed and required choices visibly disabled, required settings immutable, incompatible additions cannot evict required mods, Cancel discards, Save persists, and ordinary song-menu mods/presets/music/network state are restored. In authoring mode, permit an allowlist containing alternatives such as HR and EZ together without activating either. Only one AVD runs at a time.


## Executed coverage and final user waiver (23 November 2026)

The [C5 acceptance report](C5_ACCEPTANCE_REPORT.md) records the executed matrix: full offline feature/lifecycle acceptance on API34, compatibility and reboot-persistence checks on API24, 170 focused JVM/Robolectric tests, 14 verifier tests, and independently audited evidence. API36 emulator infrastructure stalled before app installation; no API36 application success is claimed. The user explicitly allowed skipping further AVD testing while requesting the final verdict/shadow/compact-breakdown/footer adjustment. That final adjustment uses code/layout review and the focused build/tests, with no new AVD screenshots. Minimum/current compatibility remains part of the broader release matrix; this explicit user waiver bounds the current MVP acceptance and does not imply physical-device or server acceptance.
