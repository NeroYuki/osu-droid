# Skill Course implementation plans and status

The offline implementation and requested UI/preferences revision are implemented on `skill-course`. The [post-MVP refinements](POST_MVP_REFINEMENT_PLAN.md) add skin-safe result text, background lifecycle fixes, cached availability, course-card collages and local course score modes. See the [acceptance report](C5_ACCEPTANCE_REPORT.md) for passing API 24/API 34 evidence and the limits of that coverage. The user waived further AVD testing for the visual adjustments. The [continuation checkpoint](RESUME_CHECKPOINT.md) records the completed offline work and the separate C6 gates.

Skill Courses are portable `.odc` JSON playlists that preserve map order and authored mods, run as one attempt, and retain a component score and replay for every played map. The client supplies course management and gameplay; the tournament API stores attempts by course hash and determines official status.

The plan documents are dated 22 November 2026 and remain the design record, including choices they label as proposals. The implementation includes the [23 November UI and preferences revision](UI_AND_PREFERENCES_REVISION.md). C6 server integration, official verification, the course global leaderboard and real-device acceptance are **not** implemented. Ordinary gameplay failure was restored by removing the two unconditional `canFail = false` assignments from `GameScene.java`; `StatisticV2.canFail` keeps its `true` default.

## Artifacts

| Artifact | Purpose |
| --- | --- |
| [C5 acceptance report](C5_ACCEPTANCE_REPORT.md) | Implemented C1-C5 offline MVP, focused and AVD evidence, known limits, and the checklist for final parent confirmation |
| [Client implementation plan](CLIENT_IMPLEMENTATION_PLAN.md) | Existing code seams, data and UI changes, gameplay lifecycle, persistence, and implementation milestones |
| [Server implementation plan](SERVER_IMPLEMENTATION_PLAN.md) | Changes to the inspected Express tournament API, MongoDB records, official verification, replay validation, and rollout |
| [Testing plan](TESTING_PLAN.md) | Shared fixtures, unit and integration coverage, AVD and ADB procedures, failure injection, and release gates |
| [Shared course contract](SHARED_CONTRACT.md) | Proposed version 1 JSON format, hashing, mod policy, judgment semantics, result format, and unresolved decisions |
| [Map availability and recovery plan](MAP_AVAILABILITY_AND_RECOVERY.md) | Post-import detection, missing versus outdated maps, sequential mirror downloads, safe file and database updates, and rescanning |
| [UI and preferences revision](UI_AND_PREFERENCES_REVISION.md) | Normal result assets, native paged breakdown, numeric rates and persistent per-course/map player choices |
| [Post-MVP refinements](POST_MVP_REFINEMENT_PLAN.md) | Custom skin compatibility, backgrounds, event-driven availability cache, card collages and offline course score navigation; 199 focused tests |

Read the shared contract before changing either side. Proposed API paths, types, limits, and unconfirmed product choices are identified as proposals; they describe the intended design, not necessarily the implemented behaviour. Where the implementation resolved a proposal differently, the [C5 acceptance report](C5_ACCEPTANCE_REPORT.md) and the source are authoritative.

## Implementation status

As of 23 November 2026. "Implemented" means present in the working tree and exercised as recorded in the acceptance report; it does not mean committed, released, or accepted without the remaining review.

| Milestone | Status | Notes |
| --- | --- | --- |
| C1 contract and storage | Implemented | Strict bounded portable JSON, RFC 8785 canonicalization and SHA-256 course identity; required authored mods plus allowed additions; Room v7 with courses, attempts and local course/map preferences. |
| C2 import, export and management | Implemented | `.odc` intent association, SAF import/export, staged intake, manager/editor, locking and membership. Availability scanning follows committed imports. |
| C3 viewing, practice and recovery | Implemented | Missing entries omitted, outdated entries labelled and practice-only; sequential typed mirror repair with ownership/cancellation; journaled updates including renamed difficulties and sibling preservation. |
| C4 offline gameplay | Implemented | Standard/global/none gauge, all scoring modes, authored/allowed mods, speedrun, random order, pause, auto-skip and transitions. |
| C5 results, history and acceptance | Offline MVP implemented | Normal aggregate result layout, native paged breakdown, component replay/history, durable result journal, startup recovery and persistent player choices. See the acceptance report for observed coverage. |
| C6 server and real device | Not implemented | No submission, official verification, course global leaderboard or upload-queue network API; no server deployment and no physical-device gate was attempted. |
| C7 rollout | Not started | Requires both C6 gates. |

The latest focused verification is 170 JVM/Robolectric tests with 0 failures, errors, or skips, plus `assembleDebug`; the independent evidence verifier's synthetic suite passes 14 tests. The unscoped stock unit suite has pre-existing legacy framework test compile failures at this HEAD and is not claimed passing. The [acceptance report](C5_ACCEPTANCE_REPORT.md) records the AVD evidence and its limits.

## Confirmed requirements

- Courses support the complete settings and management flows in the request, with `.odc` import and export.
- Add an `allowed_mods` course setting. Authored map mods remain required; the whitelist permits additional mods and their settings. An empty whitelist locks player mods. Speedrun permits speed changes even with an empty whitelist.
- Speedrun measures active gameplay time and excludes loading and configured transitions.
- Global HP changes only through the six configured events. It has no passive drain or Easy extra lives.
- If a slider failure produces both a slider break and a missed tail, apply both configured deltas. Distinct emitted events must be distinguished from duplicate callbacks.
- Dan courses are obsolete. Replace their server routes and storage assumptions for Skill Courses; preserve old data until an explicit archival or deletion decision.
- Retain every submitted valid attempt by course hash, rather than retaining only a player's best attempt.
- A map is missing when neither its MD5 nor its artist/title/mapper/difficulty tuple is installed; it is outdated when only that tuple matches. Check after course import and after beatmap import completes. Keep course viewing available and omit missing entries from song selection.
- Show per-map missing/outdated issues in the course manager. Replace Play with Download missing maps while any entry is unresolved; download through the existing beatmap mirror one set at a time, rescan after import, and keep the download action visible after an unavailable/wrong-version response with manual-import guidance.
- EX keeps signed running values and component contributions, but the final course score is `max(0, raw EX total)`. Do not clamp after each event or separately per map. A course-specific minus-sign fallback makes negative live display a small UI change.
- C5 is the fully functional offline MVP, accepted using AVDs without a server deployment or physical phone. Submission, official verification and the course global leaderboard are C6 work, with both the server-change gate and real-device ADB acceptance gate there.

## Repositories and workspace

As of 23 November 2026:

Client: `D:\dev\osu-droid`, branch `skill-course`, created from `tournament-dev` at the existing HEAD. The C1-C5 changes and follow-up refinements are on this branch, with commit and push authorized by the user. The refresh-rate experiment remains stashed as `8d592f47a416886bc0177d91d1b7c3d74ecbd5f0`, message `Refresh-rate unlock experiment before Skill Course planning`, and has not been modified. Other pre-existing build, version, attestation and untracked work remains outside that stash and outside the course changes; it is not part of C5 acceptance.

Server: `N:\Tournament-Server\osu-droid-tournament-server`, an SMB view of a Linux checkout. It was inspected only; no server source, branch, deployment, or database was changed, and C5 has no course network API to exercise against it. The sibling `multi-server-node` is the multiplayer socket service, not the single-player score API. Implement the server plan in a separate local clone or worktree on a Skill Course branch; do not switch the shared service checkout while it is in use.

## Implementation order

The planned sequence is unchanged; current status is tracked above.

1. C1: freeze the offline client contract/vectors and implement storage. Cross-runtime server execution is deferred to C6.
2. C2: implement import/export, course management and post-import availability detection.
3. C3: implement viewing/practice, missing/outdated issue lists, sequential mirror recovery and safe updates.
4. C4: implement complete offline course gameplay, including all gauge/scoring/timing settings.
5. C5: implement aggregate results, component panel, replay/history durability and pass the offline MVP acceptance suite on AVDs.
6. C6: implement server registry, uploads, verification and the course global leaderboard, then pass staging-service and real-device ADB acceptance. These are separate explicit gates within C6.
7. C7: perform final rollout checks after C6, without moving an offline MVP requirement out of C5.

Through C5, creation, import/export, management, map recovery, practice, every course gameplay option, results, breakdown and component replay/history are implemented; the exercised cases are recorded in the acceptance report. All required installed-map workflows also work with networking disabled. Optional map recovery uses the already accessible beatmap mirror and does not require a new course server. There are no course submission calls, course global leaderboard requests or login/attestation prerequisites in the C5 path. After C6 integration, an unsupported/unreachable server must still leave a durable local result and a clear upload status.
