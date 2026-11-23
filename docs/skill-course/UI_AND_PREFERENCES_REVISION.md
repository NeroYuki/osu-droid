# Skill Course UI and preferences revision

This 23 November 2026 revision is required for the C5 offline MVP. It supersedes the initial course result rendering, Android AlertDialog breakdown, speed spinner, and inline optional-mod controls. The user supplied a component-result mockup and confirmed that allowed mods remain course-wide. No server deployment or physical-device gate is added to C5.

## Decisions and integration boundaries

1. Copy the ordinary beatmap result layout from `ScoringScene.load`, using its existing skin assets, six judgment icons, ScoreNumber digits, accuracy and combo positions. Feed it a typed course presentation without calling the ordinary score save/submit path. Sum hit300/hit100/hit50/misses and native 300G/100K across played components. Display the selected course metric (native total, final EX or pooled accuracy) using score assets. Replace the grade with Completed/Failed and place played/total maps below. Remove the duplicate detailed outcome/reason block. Retain a compact active-time statistic for speedrun, Breakdown, full-course Retry and Back.
2. Replace the generic Android breakdown with the game's themed panel/overlay pattern used from song selection. Follow the mockup: artist/title/difficulty header and mapper subtitle; score at upper left; two columns of six native judgments; grade and mod icons on the right; accuracy below; Watch Replay at lower right. Left/right chevrons move through authored entries in realized played order, including explicit unplayed pages. Prefer existing score/judgment/grade/mod assets, with correctly sized readable text fallback. Keep raw signed EX contribution/HP/time as compact secondary information when applicable. Replay returns to the same course result; browsing never saves or submits a score.
3. Replace preflight speed spinners with compact `[-] [numeric rate] [+]` rows for each map. Use integer hundredths internally: 50..300 inclusive, step 5. Typed finite numbers round half-up to the nearest 0.05 then clamp. Preserve the last valid saved value on invalid text and block Start while input is invalid. Commit accepted +/- and normalized input to local storage. Raise the native static-rate metadata ceiling to 3.00 so native serialization and replay do not silently clamp the choice.
4. Each preflight map row has Select mods and a compact selected-mod summary. Open the existing native mod selector in an isolated player-selection session. Show authored required mods selected and locked, whitelist-disallowed mods grayed and disabled, and reject incompatible additions without evicting required mods. Required settings cannot be edited. Optional mods may be configured through existing customization controls. Save commits this map's additions; Cancel/Back restores its previous choices. The dedicated speed control owns the speedrun rate; it cannot be bypassed by an optional rate mod.
5. Add Select allowed mods to the course editor. Reuse the same native selector in allowlist-authoring mode. The selection is a set of course-wide permitted acronyms, not an active mod combination: incompatible alternatives may both be allowed. No map-level allowed-mod field is added. Existing ordered map rows remain compact and continue showing required authored mods.

## Storage and frozen attempt contract

Add Room migration 6 to 7 and a local play-preference entity keyed by `(courseId, mapHash)`, with integer `speedHundredths`, serialized optional mods and updated timestamp. Use the local course ID so two courses using the same map keep independent choices. Reordering keeps preferences bound to MD5. Export/hash never includes preferences. On reopening after an edit, revalidate saved additions against the current required mods and allowlist; explain any dropped incompatible/disallowed choice and keep Start disabled for unresolved invalid input.

Extend the frozen start request with `additionalModsByIndex: Map<Int, ModHashMap>`. Keep the existing global additional-mod argument as a legacy fallback. Extend persisted result snapshots with per-original-index additions, defaulting to the old global field when reading old history; Retry uses the frozen per-map choices and rates. Add defaulted native `hit300k`/`hit100k` component fields and capture them from StatisticV2. These do not alter accuracy/EX arithmetic.

The native selector needs an explicit scoped session with Save/Cancel, required/allowed constraints, a preview beatmap, and mode (player additions or author allowlist). Preserve and restore ordinary selected mods, presets, song-menu selection/music effects and asynchronous calculations. Course sessions must not send multiplayer/ordinary online updates. Temporarily suspend Android fragment overlays while the native selector owns input, then restore the same fragment state; handle Back/focus/activity destruction without leaving hidden overlays or a stale selector restriction.

## Implementation slices and ownership

- Local preferences, migration, numeric normalization and rate validation tests.
- Isolated native mod-selector session API and overlay bridge, with ordinary-selector regression review.
- Course editor/preflight integration after those APIs are fixed.
- Native aggregate layout and component-panel presentation, retaining read-only replay/history behavior.
- Parent integration: frozen per-map runtime/result contract, native auxiliary counts, lifecycle review, final builds and single-AVD visual/persistence acceptance.

OpenCode may implement coherent slices and review one another's changes. Codex owns architecture, integration and critical lifecycle checks. Do not run concurrent Gradle builds or competing AVD control processes.

## Required acceptance before wrapping up

### Final visual adjustment (23 November, after AVD review)

Center the Completed/Failed and maps-played group vertically in the available space between the Ranking asset and Breakdown button, with a dark offset text shadow for bright backgrounds. Reduce the native breakdown card's width, height, column spacing and vertical whitespace while retaining readable assets and counts. Put Close on the same baseline as Watch replay, aligned horizontally with the start of the judgment section. The user waived further AVD testing for these final layout changes; verify with focused tests/build and code/layout review, and retain the earlier AVD evidence without claiming it screenshots this last adjustment.

- Compare ordinary and course results on the same skin, with native totals and EX/accuracy modes, failures, long titles and speedrun time.
- Inspect the component panel against the mockup, page both ways, inspect unplayed/partial results, play a native component replay, and return without score writes.
- Check 0.50/3.00 boundaries, 0.05 increments, typed half-step rounding, clamping and invalid input. Restart the process and verify per-course/map persistence and exact native effective rates.
- Confirm disabled disallowed mods, locked authored mods/settings, optional customization, Save versus Cancel, and authoring an allowlist with incompatible alternatives. Restore the ordinary selector afterward.
- Exercise compact editor/preflight with the keyboard open, course reorder/policy changes, and two courses sharing a map.
- Re-run focused JVM/Robolectric tests, migration checks, debug assembly and independent AVD evidence verification. Screenshots predating this revision do not accept it. The acceptance report identifies the revised API24/API34 evidence and the final visual changes verified without another AVD run.


## Completion record

Implemented on `skill-course`. The revised UI/preferences were exercised on API24 and API34, including actual reboot persistence, independent course/map choices, native mod Save/Cancel and restoration, and native results/replay. The final requested verdict-shadow/compact-panel/footer adjustment was reviewed and passed all170 focused tests plus debug assembly (`final-layout-build.log`,12s). No AVD was run after the user waived further testing. See [acceptance evidence and limits](C5_ACCEPTANCE_REPORT.md).
