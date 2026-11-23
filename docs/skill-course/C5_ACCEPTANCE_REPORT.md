# Skill Course C5 offline MVP acceptance report

Status on 23 November 2026: the complete offline implementation and requested UI/preferences revision are implemented on `skill-course`. API 24 and API 34 acceptance passed. Further AVD testing was waived by the user for the final visual adjustment; that adjustment is checked by code review, focused tests and debug assembly. C6 server integration and physical-device acceptance remain separate, unimplemented gates. The user authorized a local commit of this implementation; no push or release has been performed.

This report records observed evidence. Read the [client plan](CLIENT_IMPLEMENTATION_PLAN.md), [server plan](SERVER_IMPLEMENTATION_PLAN.md), [testing plan](TESTING_PLAN.md), [shared contract](SHARED_CONTRACT.md), [map recovery plan](MAP_AVAILABILITY_AND_RECOVERY.md), and [UI revision](UI_AND_PREFERENCES_REVISION.md) for the detailed design. The original paused checkpoint is archived; the [current continuation checkpoint](RESUME_CHECKPOINT.md) reflects the completed offline work.

## Implemented scope

- C1: strict bounded v1 `.odc` JSON, native mod/settings validation, RFC 8785 canonicalization and SHA-256 course identity. Room v7 stores courses, immutable attempts, and local player preferences; migrations 5->6 and 6->7 are covered.
- C2: Android file association and bounded staged intake, SAF import/export, create/edit/lock/delete, ordered map membership capturing authored mods, and a compact course-wide allowed-mod selector. Lock state and player preferences are excluded from portable identity.
- C3: exact/missing/outdated classification using actual map bytes and the display identity tuple, rescanning after committed imports, practice viewing that omits missing entries, and persistent sequential download recovery. Archive reconciliation journals file promotion and Room changes, handles renamed difficulties and rollback, and preserves siblings, assets, map options and collections.
- C4: sequential runtime with authored required mods and compatible allowed additions, none/native-standard/event-only-global gauges, native V1/V2 totals, pooled accuracy, signed EX with one final floor at zero, random order, active speedrun timing, transitions, pause policy and auto-skip. Ordinary gameplay `canFail` defaults to true again.
- C5: ordinary result layout/assets with six aggregated judgments, Completed/Failed and maps played, native paged component panel, native component replay, local history, whole-course Retry, fsynced replay/event/result evidence and startup interruption recovery. Library leases pin maps across preflight/run; incoming map imports wait through results until Back.
- UI revision: numeric rate input with 0.05 steps and half-up normalization within 0.50..3.00, explicit Save/Cancel in the native mod selector, immutable authored settings, configurable optional settings, and Room preferences keyed by `(local course ID, authored map MD5)`. Frozen results retain per-original-index player choices and native auxiliary judgment counts. Old history still decodes with defaults.

C5 has no course submission, official verification, course global leaderboard or upload queue. The aggregate bypasses ordinary single-map score persistence/submission. The inspected server at `N:\Tournament-Server\osu-droid-tournament-server` was not modified or deployed.

## Automated verification

The final focused Gradle run is `.gradle/skill-course-planning/final-layout-build.log`: BUILD SUCCESSFUL in 12s, including `assembleDebug`. JUnit XML reports **170 tests, 0 failures, 0 errors, 0 skipped**. Coverage includes codec/hash vectors, policy and selection, preferences/rates/static native replay serialization, migrations, availability/recovery/import rollback, downloader ownership, runtime arithmetic/timing, result presentation and durable attempt recovery.

The Python evidence verifier's own synthetic suite passed **14 tests**. `git diff --check` passed; Git emitted only existing line-ending notices. The unscoped stock unit suite still has unrelated legacy framework test compilation failures at this HEAD; it is not claimed passing.

The independent evidence verifier checks course hashes, replay SHA-256/ZIP CRC, native/event counts, EX, HP carry, order/rates and active timing. It does not re-simulate every ODR input and cannot establish an official score.

| Evidence snapshot | Observed result |
| --- | --- |
| API 34 final Room database + WAL + replay/event/journal directories | 25 fixture attempts, 16/16 required scenarios, zero errors or warnings |
| API 24 Room database + WAL + replay/event/journal directories | 3 fixture attempts, 3/3 required scenarios, zero errors or warnings |
| API 36 compatibility | Not completed: emulator shell stalled before app installation; further AVD runs were waived by the user |

Room on API 34 reports user_version 7 after upgrading the earlier v6 fixture installation. Its ordinary `ScoreInfo` table remains empty. The final API 34 process-filtered AndroidRuntime/SkillCourse error log is empty, including two repeated Home-during-loading interruptions after the save reservation fix.

## API 34 acceptance

Pixel_8, x86_64, 2400x1080 landscape, networking disabled. This profile supplied the full management/gameplay/lifecycle matrix. Original generated map/audio fixtures are redistributable and require no external beatmap copyright material.

| Scenario | Observed behavior |
| --- | --- |
| Import/export/locking | Direct `.odc` and octet-stream association, SAF round trip with stable hash, imported locked state, draft creation, authored-mod membership and disabled locked membership controls |
| Missing/outdated | Manager issue list and persistent recovery action, no-ID manual-import guidance, outdated practice candidate visible, missing entry omitted, committed import automatically refreshes readiness |
| Safe map update | AR8->AR9 `A.osu` -> `A Renamed.osu` update changes storage and Room together, preserving B/Sibling/D and refreshing the view |
| EX/global | All-miss raw EX -7, published 0; global HP 80->68->57; low HP fails on first miss, keeps partial replay, and never starts map 2 |
| Native gauge/PF | Standard failure ends once, PF fails on first miss without a restart loop, global PF still obeys only configured HP events, invalid None+PF rejected |
| All scoring modes | Two-map authored autoplay produces native V1 5755, V2 1989937, pooled accuracy 100%, EX14 |
| Timing/random/pause | Frozen order [1,0] retains A1.20/B1.00, active 12.463330761s excludes fixed transitions; pause clock freezes and resumes |
| Retry/history/restart | Result and pause-menu Retry create fresh whole attempts; force-stop during transition recovers interrupted history with retained replay digest |
| Focus/loading | No-pause Home and two-Back exit save terminal results; repeated Home during loading saves one zero-component interrupted attempt each with no late start or save error |
| Deferred import | Incoming update stays staged through run/results, then results Back applies the renamed map and preserves siblings |
| Longer course | 20 distinct maps complete, EX160, 100%, active122.133s; all component artifacts verify. Final PSS124964KiB is one observation, not a memory-growth profile |

## Requested UI and preference acceptance

- Aggregate results use ordinary ranking panel, score digits, six hit assets, combo/accuracy labels and Retry/Back assets. Grade is replaced by Completed/Failed with played/total below; no duplicate detailed verdict block. Speedrun keeps millisecond active time.
- The centered native breakdown matches the supplied organization: map header/mapper, component score, two judgment columns, grade and mod icons, accuracy, chevrons and Watch replay. Both directions, completed, partial and unplayed pages were inspected. Unplayed maps have no invented grade/counts/replay action. Component replay returns to the unchanged aggregate.
- A course authored with NC/HT ran at exactly 3.00x/0.50x. Frozen result mods retain the corresponding native classes/rate settings, Hidden customization and auxiliary judgments. Final EX14, seven 300s and two 300Gs match the component evidence.
- Typed3.14 clamps to3.00; typed1.225 rounds to1.25; +/- changes exact0.05 steps; 0.50 and3.00 endpoints disable the appropriate step button. Blank input blocks Start. Invalid text does not overwrite the last valid preference.
- Native player selector visibly disables disallowed mods, locks required AT/HR/NC and required rate settings, allows compatible optional Hidden/DA configuration, and preserves requirements on Clear. Cancel discards additions; Save commits them.
- Course A and B share map A but retain independent values: A1.20 + DA(CS7.0), B0.80 + HD. Native attempts freeze those exact combinations. Reordering A to B/A keeps A's1.20/DA attached to its MD5. The allowlist authoring session retains EZ and HR simultaneously.
- The revised compact preflight/editor were inspected with the landscape IME open. They use the inline keyboard, keep the active field and action bar visible, and restore headings afterward. Done normalizes/dismisses the rate keyboard.
- Restarting/reinstalling without data clear retains rates and settings, including first-save authored1.50 rather than resetting it to1.00. Room inspection confirms separate course/map rows and serialized optional settings.

## Minimum/current API compatibility

API24 uses a workspace-isolated Google APIs x86_64 revision27 image, serial emulator-5556, 2400x1080. The APK installs and launches, original map/course intents import successfully, authored NC/HT defaults are1.50/.75, numeric input clamps to3.00, optional Hidden saves, both maps complete, and native component replay returns correctly. V2 and pooled-accuracy results use the correct score assets. A real emulator reboot restored3.00 and HD. Opening the ordinary mod selector afterward restored presets, ordinary mods and unrestricted controls. All three attempts verify with no process-filtered app errors. The emulator network interface was disconnected during play; no tournament service was used.

API36 is not claimed tested. Both the installed 16KB Play image and a standard official image stalled at the emulator shell with the installed tooling, before app installation. Updated stable tools were downloaded only into the ignored workspace; the user then waived further AVD testing, so no additional emulator run was started. All test AVDs are closed and existing user profiles are preserved.

The final visual adjustment centers the verdict/map count group between Ranking and Breakdown with dark offset text shadows, caps the breakdown at920x396 native units, tightens its columns and rows, and aligns Close with the hit-count section and Watch replay footer. The parent reviewed font/section bounds and page-owned replay input, retained all mod icons through wrapping, and placed Retry saving beside Breakdown to preserve the verdict gap in the error state. The final build and all170 focused tests passed after these changes. Earlier AVD screenshots establish functionality but do not depict this last adjustment. No further AVD test is claimed.

## Evidence and reproduction

Evidence stays in ignored `.gradle/skill-course-planning/`, not in the docs or committed assets. Representative screenshots: `revision-native-result.png`, `revision-centered-partial.png`, `revision-centered-unplayed.png`, `revision-usable-keyboard.png`, `revision-editor-inline-keyboard.png`, `revision-author-ez-hr.png`, `api24-course-result.png`, `api24-scorev2-result.png`, `api24-accuracy-result.png`, `api24-reboot-preferences.png`, `api24-normal-mods-restored.png`. Some local screenshots contain a profile name; do not copy them into public documentation.

Machine-readable reports: `final-verification.json` and `api24-verification.json`. Matching data is in `final-db`/`final-evidence` and `api24-db`/`api24-evidence`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat -I tests/fixtures/skillcourse/focused-tests.init.gradle testDebugUnitTest `
  --tests 'com.osudroid.skillcourse.*' --tests 'com.osudroid.data.MigrationTest' `
  --tests 'com.osudroid.beatmaplisting.BeatmapDownloadTokenTest' assembleDebug --console=plain
python -B tests/fixtures/skillcourse/test_verify_avd_results.py
python tests/fixtures/skillcourse/verify_avd_results.py `
  --database .gradle/skill-course-planning/final-db/room.db `
  --evidence .gradle/skill-course-planning/final-evidence `
  --require '00C Authored Rate Preferences'
```

## Limits and remaining gates

- No C6 server code, deployment, official registry, submission/global leaderboard or physical-device test. The detailed server implementation plan is ready for that separate phase.
- No successful live mirror download was exercised. Typed downloader/import/cancellation tests and local committed imports cover recovery behavior; production mirror revision availability remains an external compatibility check.
- Current-API36 compatibility remains unverified after the user waived further AVD runs. API24 and API34 checks supplement, rather than duplicate, the full API34 matrix. No full replay simulation, performance certification, memory-growth study, foldable/small-window matrix or release acceptance is claimed.
- Preexisting unrelated build/attestation/untracked work is preserved. The refresh-rate stash remains `8d592f47a416886bc0177d91d1b7c3d74ecbd5f0`.
