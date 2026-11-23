# Skill Course post-MVP refinements

Requested 23 November 2026 after commit `0aecfa764`. These refinements follow the offline MVP on `skill-course`. Unrelated working-tree changes and the refresh-rate stash are preserved. No server deployment or online course API is included.

## Results and skin compatibility

- Place Completed/Failed and maps played within viewport-owned bounds, independent of the Ranking texture's canvas height. Custom skins can supply a transparent full-screen asset, which must not push native course text off screen. Keep the text shadow and draw it above skin assets.
- Move Breakdown to the bottom left below the native combo/accuracy statistics. Keep Back/Retry and save-failure recovery reachable without overlap.
- Enlarge the component grade within the right column, reserving space for optional partial-result text and wrapped mod icons rather than leaving a fixed 90-unit grade box.

## Song selection and local course scores

- Use a native rounded Skill Courses button with a left icon, placed above the beatmap-card list and above later header/skin layers in draw order.
- Show “Press Back to exit course view” above the Mods/Options/Random controls while the projection is active. Preserve ordinary view/filter/mod state on exit.
- Implement an explicit local board mode model: ordinary offline = local beatmap only; ordinary online = local / online score / online PP; course view offline or online = individual map / total course. Online total course is deferred until C6. Switching modes must not issue ordinary online requests from course view.
- Confirmed: the course individual-map board shows component results from course attempts. Ordinary practice scores remain in the ordinary beatmap board. Total-course rows read immutable local attempts for the exact active definition hash, display the course's scoring metric and open its existing result/breakdown. Never write course components or aggregates into ordinary ScoreInfo.
- Confirmed: ordinary Online PP is deferred with an explicit unavailable state. The inspected tournament leaderboard returns no PP values or PP-ranking mode. The client must not relabel online score as PP or estimate PP for this mode.
- Guard asynchronous board loads by mode, selected map, course identity and generation so late online/local responses cannot replace the current view. Normalize mode on login/logout and restore the previous ordinary mode when exiting the course. Use the existing compact leaderboard icon button, without a persistent text mode label.

## Background lifecycle

Investigate selection and direct course-start paths. Load the selected/playing map's actual background rather than reusing the global `::background` left by an unrelated song. Use captured paths/local sprite state and generation checks for asynchronous selection loads. Avoid unloading a texture still owned by active gameplay; course transitions, practice, replay and result return must resolve to the appropriate map.

## Availability performance and course cards

- Opening the manager currently calls the full scanner once per course. Replace that repeated work with event-driven availability caching keyed by immutable course hash and committed library revision. Warm after startup/library update and course import/edit; manager reads snapshots and observes completion. Coalesce work and reject obsolete generations. Keep a fresh exact-file integrity check at actual course start.
- Use the existing RecyclerView for bounded, asynchronous course-card collages from a small deterministic sample of available map backgrounds. Crop into a horizontal strip and dim it beneath readable text; missing/unsafe backgrounds fall back to the normal card. Cancel/rebind image work for recycled holders and bound decode/cache memory.

## Verification and ownership

Codex owns background diagnosis, cache/concurrency architecture and final integration. Delegate coherent result UI, song-menu/board, and manager/cache/card slices to OpenCode. Workers must not run AVD, Gradle or manual compiler bootstraps; parent runs one build and at most one AVD driver at a time. Add meaningful regression coverage for mode cycles, score identity/arithmetic and availability cache invalidation/coalescing. Run focused JVM/Robolectric tests, verifier tests and debug assembly. Existing unrelated modifications remain unstaged; no new commit/push unless requested.

## Implemented behavior and evidence

- Results: viewport-owned, shadowed Completed/Failed and maps-played text drawn after skin layers; compact bottom-left Breakdown; enlarged component grade up to 180 units, shrinking when partial/mod rows need space. Existing skin assets, score digits and aggregate judgments remain.
- Backgrounds: direct gameplay loads the parsed map's background filename from its set directory. Selection uses captured paths and locally constructed sprites. Shared texture replacement is serialized and owned by request tokens; canceling an old preview cannot cancel gameplay. Missing/safe images replace the shared alias with the menu fallback, which is never unloaded. Result return refreshes song-menu artwork.
- Navigation: rounded themed Skill Courses button with playlist icon is attached above list/header layers; the dark course-exit hint is above the lower controls. Ordinary online modes restore after exiting a course and normalize after logout.
- Local boards: ordinary offline has local map scores only; ordinary online cycles local / online score / unavailable Online PP. Course view, online or offline, cycles component results / total attempts for the exact frozen course hash. Component identity checks both authored index and MD5. EX components remain signed, published totals floor at zero; accuracy is pooled. Completed results sort first, then metric and speedrun time, with stable ties. Course rows open the existing aggregate result and its breakdown, cannot delete ordinary scores, and never feed course metrics into the gameplay HUD.
- Async publication: score rows, avatars and online status icons publish on the engine update thread only while their request, mode, selected map and course identity remain current. Reload disposes the old board's executor and invalidates its work. Totals remain available when no course maps are installed.
- Availability: confirmed that the old manager ran the full scanner once per course on every open. Manager, course view and preflight now only read/observe a process cache. Warming follows startup, committed course content changes and the single committed-library event used by ordinary import, recovery import, deletion and clear/reload. Lock changes do not scan. Cached generations share memoized file identities, coalesce bursts, reject obsolete/deleted results and expose terminal errors. Recovery and actual course start deliberately retain fresh scans.
- Cards: deterministic sampled background collage strips, dimmed and rounded, decoded off the UI thread with two workers and an 8 MiB cache. Recycling/dismissal invalidates callbacks. Library revision is part of image-cache identity so replaced images at the same path refresh.

Verification: **199 focused JVM/Robolectric tests, zero failures/errors/skips**, debug assembly, **14 Python verifier tests**, and `git diff --check`. New tests cover the full mode matrix/restoration, exact course/component identity, ordering and EX/accuracy, repeated cache reads, event invalidation/coalescing, failure recovery, deletion races and background ownership. The authoritative final build log is `.gradle/skill-course-planning/compact-score-switcher-build.log`.

At completion of the visual refinements, no additional AVD/physical-device pass was performed, following the earlier testing waiver. The subsequent live API34 import/recovery check is recorded in the C5 acceptance report; it does not certify full custom-skin or physical-device acceptance. Earlier API24/API34 evidence remains evidence for the base MVP. Online course totals and ordinary Online PP data remain deferred server work.

## Compact selector correction

The first refinement used a nonexistent `ranking_enabled` texture for both course modes. That made the icon and its touch area collapse, leaving the oversized text label as the only visible indicator and preventing access to course totals. Remove the label and use the already loaded native assets: `ranking_disabled` (Home) for individual components, `ranking_enabled_score` (Score) for course totals, and `ranking_enabled_pp` for the deferred ordinary PP mode. Course view taps cycle Home → Score → Home, online or offline. Keep a nonzero native-sized hit area through the change and verify actual tap/release interaction and cancellation, in addition to the mode policy tests. Do not add separate mode state, new icon artwork, or a second totals entry point.

Compact-selector verification: 199 focused tests passed with zero failures/errors/skips, including actual sprite tap/release cycling to offline course totals, drag/cancel rejection and existing 50x50 asset checks. Debug assembly passed in 13s; no further AVD run was performed.

## Compact course cards

Restored content-driven card height: the collage and dimming layer are constrained to the existing text/action content instead of imposing a 160dp minimum. Center-cropping keeps backgrounds recognizable without adding empty vertical space. Expanded issue lists still grow with their content. Existing card padding and controls remain. Debug assembly passed in 7s (`compact-course-card-build.log`); no new tests or AVD run were needed for this layout-only adjustment.
