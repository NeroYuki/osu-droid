# Skill Course continuation checkpoint

Updated 23 November 2026. The C1-C5 offline MVP and the requested UI/preferences/final-layout revisions are complete on `skill-course`. No implementation worker, build or AVD test remains active. The original paused checkpoint is archived in ignored `.gradle/skill-course-planning/paused-checkpoint-original.md`.

## Start here

- Read [README](README.md), [acceptance report](C5_ACCEPTANCE_REPORT.md), and [UI revision](UI_AND_PREFERENCES_REVISION.md). Detailed [client](CLIENT_IMPLEMENTATION_PLAN.md), [server](SERVER_IMPLEMENTATION_PLAN.md), [testing](TESTING_PLAN.md), [contract](SHARED_CONTRACT.md), and [map recovery](MAP_AVAILABILITY_AND_RECOVERY.md) plans are retained.
- Workspace: `D:\dev\osu-droid`, branch `skill-course`. Refresh-rate stash `8d592f47a416886bc0177d91d1b7c3d74ecbd5f0` is intact. Preserve unrelated preexisting modifications and untracked files. A local commit was requested; no push or release was requested.
- Final debug APK: `build/outputs/apk/debug/osu!droid-2026.1123.0-tournament-debug-2026-11-23.apk`.
- Final source verification: `final-layout-build.log`, BUILD SUCCESSFUL in12s, 170 focused JVM/Robolectric tests with zero failures/errors/skips and `assembleDebug`. Python verifier tests14 passing; fixture driver syntax and `git diff --check` pass. The unscoped stock suite has unrelated legacy framework test compilation failures and is not claimed passing.
- API34 evidence:25 attempts,16 required scenarios, zero errors/warnings (`final-verification.json`). API24 evidence:3 attempts,3 required scenarios, zero errors/warnings (`api24-verification.json`). API24 actual reboot restores3.00x and HD; ordinary selector state restores. The report distinguishes full API34 coverage from compatibility checks.
- API36 standard/16KB emulator images stalled before app installation with the installed tooling. Stable tools/images were downloaded only under ignored workspace paths. The user then waived further AVD testing, so API36 is not claimed tested and all AVDs are closed.

## Final visual revision

Completed/Failed and maps played are centered between actual Ranking asset height and actual Breakdown y, with offset dark text shadows. The native breakdown caps at920x396 units with tighter spacing. Close aligns to the hit-count section on x and Watch replay on y. One page-owned replay footer action is reused safely; all mod icons wrap rather than disappearing in the narrower column. Retry saving shares the Breakdown row in the error state so it cannot overlap the verdict. Font/section bounds were reviewed. These last changes passed the focused build/tests; earlier AVD screenshots do not depict them and no additional AVD run was performed, as permitted by the user.

## Retained product decisions

- Allowed mods are course-wide. Authored mods/settings stay required. Room7 saves optional settings and speed separately for each local course ID/authored map MD5 pair; reorder does not move preferences. Portable hash/export excludes local preferences/lock state.
- Global HP is event-only, with no passive/Easy/PF override. Both slider penalties apply when distinct events occur. Raw/live/component EX is signed; only final total is floored at zero.
- Speedrun rates are0.50..3.00 in0.05 steps, typed half-up normalization/clamping, frozen at start. Active real gameplay time excludes pause/loading/fixed transitions/leadout.
- Missing/outdated detection follows committed imports. Missing entries are omitted from practice view, outdated candidates remain practice-only, full Play requires all exact maps. Recovery is sequential and keeps the repair action after unavailable/wrong revisions.

## Next phase, when requested

C6 is separate: implement server API/registry/verification, durable submission and course global leaderboard, then pass server/staging and physical-device ADB gates. The SMB server repo `N:\Tournament-Server\osu-droid-tournament-server` was inspected only and remains unchanged. Keep offline behavior intact. Current-API36 compatibility and a successful live mirror download remain explicit validation limits; no physical-device or production acceptance is implied.

Do not restart old workers or repeat the original paused compile fixes. The final layout worker47855 was stopped after its edits when it entered redundant manual compiler setup; parent integration/review and the authoritative Gradle build completed its work. Other prior workers are terminal. All source is reviewable locally; the implementation is included in the requested local commit.
