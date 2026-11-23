# Skill Course shared contract

This proposed version 1 contract is the common implementation target for the client, tournament API, and tests. Confirmed choices are recorded in [the plan index](README.md); the remaining proposed choices are collected at the end of this document.

## Course identity and versioning

`course_version` is the file specification version and starts at `1`. It is not an editable course revision. A content edit produces a new course hash. Official registry entries may group revisions under a separate server catalog ID later, but that ID does not replace the content hash.

Beatmap identity remains the existing MD5 of the exact `.osu` bytes. Course identity uses a separate SHA-256 digest. Object counts supplement map resolution and corruption detection; equal counts do not prove equal map content. Beatmap IDs and display metadata cannot override a hash mismatch.

Proposed hash input: UTF-8 bytes of the validated manifest canonicalized with [RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785). Hash the complete manifest, including its display fields, authored order, and policy. Object member order and whitespace do not matter; array order, strings, numbers, and omitted versus present mod settings do matter. There is no extra Unicode normalization. Reordering maps or changing a title therefore changes identity. Reject duplicate JSON object keys before a decoder can discard them. Both implementations need the same canonicalization vectors, especially non-ASCII keys and number formatting; sorting object keys alone is insufficient.

Do not include local lock state, database IDs, installed paths, official badges, downloaded assets, attempt order, selected run speeds, player identity, or replays in this hash. Those do not appear in the portable manifest. The calculated hash is carried in API requests, not trusted as a self-declared field inside `.odc`.

Prefer an audited JCS implementation after checking existing dependencies; otherwise implement the bounded subset with the RFC vectors and differential tests. Do not silently substitute ordinary Kotlin or JavaScript JSON serialization.

## Portable JSON

Use snake_case keys, UTF-8, and the existing APIMod object representation. The exporter writes every top-level field shown below, even when a setting group is inactive. Inactive groups retain their values and participate in the hash, allowing the editor to preserve settings when switching types. All top-level and map fields shown are required in the proposed v1 format. `settings` inside an APIMod is optional, as in the current mod representation.

The following is a structural example, not a playable fixture. Its map hash, metadata, ID, and counts must be replaced with values from one real or generated `.osu` file together.

```json
{
  "format": "osu-droid-skill-course",
  "course_name": "Starter Course",
  "course_version": 1,
  "author": "Course Author",
  "life_gauge": "global",
  "gauge_settings": {
    "total_hp": 100,
    "starting_hp": 80,
    "hit300": 1,
    "hit100": 0,
    "hit50": -1,
    "hit0": -3,
    "sliderbreak": -1,
    "sliderend": 0
  },
  "scoring": "exscore",
  "exscore_settings": {
    "hit300": 2,
    "hit100": 1,
    "hit50": 0,
    "hit0": -1,
    "sliderbreak": 0,
    "sliderend": 0
  },
  "allow_pause": false,
  "map_transition_ms": 0,
  "auto_skip": true,
  "is_speedrun": false,
  "random_order": false,
  "allowed_mods": ["HD"],
  "maps": [
    {
      "map_hash": "00000000000000000000000000000001",
      "object_counts": [10, 5, 1],
      "artist": "Example Artist",
      "artist_unicode": "Example Artist",
      "title": "Example Song",
      "title_unicode": "Example Song",
      "mapper": "Example Mapper",
      "difficulty": "Course Difficulty",
      "beatmapid": null,
      "mods": [
        {"acronym": "DA", "settings": {"ar": 9, "od": 8}}
      ]
    }
  ]
}
```

`format` is a proposed discriminator so a JSON file received through an opaque content URI can be identified without treating every JSON file as a course. It does not provide authenticity. `beatmapid` is a difficulty ID, not a set ID; `null` denotes an unavailable online ID.

### Validation and defaults

| Field | Version 1 rule |
| --- | --- |
| `course_name`, `author` | Nonempty, bounded display text; propose 128 and 128 Unicode scalar values respectively, no control characters |
| `life_gauge` | `none`, `standard`, or `global` |
| `scoring` | `scorev1`, `scorev2`, `exscore`, or `accuracy` |
| `course_version` | Integer exactly `1`; newer versions require a supported codec |
| `gauge_settings` | Proposed integer HP units; `1 <= total_hp <= 1000000`, `0 < starting_hp <= total_hp`, each delta between -1000000 and 1000000 |
| `exscore_settings` | Six signed integer deltas between -1000000 and 1000000; running totals/component contributions may be negative, while the final course EX score has minimum 0 |
| Boolean settings | Actual JSON booleans, without string or integer coercion |
| `map_transition_ms` | Proposed integer range 0 through 60000 inclusive |
| `allowed_mods` | Array of unique known user-playable acronyms, with no duplicate values |
| `maps` | Proposed 1 through 256 entries for export/play; empty lists allowed only for local drafts |
| `map_hash` | Exactly 32 lowercase hexadecimal characters |
| `object_counts` | Exactly `[circleCount, sliderCount, spinnerCount]`, nonnegative integers; propose total 1 through 1000000 per map |
| Map display strings | Bounded text, propose 512 scalar values per string; Unicode variants may be empty and fall back to romanized values |
| `beatmapid` | Positive safe integer, or `null` |
| `mods` | JSON APIMod array, never a double-encoded JSON string; validate acronyms, settings, duplicates, and compatibility |

Propose a 1 MiB definition limit and bounded nesting, read incrementally with an explicit limit. Validate settings before constructing mod instances because existing setters can clamp invalid values and `deserializeMods` can drop unknown acronyms. A course import must report an unsupported or invalid mod, rather than silently changing the rules. Reject unknown v1 fields as unsupported extensions rather than discarding them and calculating another hash. Reject non-finite numbers, unsafe integer mod values, invalid Unicode, and duplicate keys.

New-course UI defaults: `standard`, `scorev1`, pause allowed, transition `0`, auto skip off, speedrun off, random order off, and `allowed_mods: []`. Gauge and EX defaults are the values in the example. Proposed membership rule: one entry per map MD5 in v1. Add snapshots the selected difficulty and its current user-playable mods; remove identifies the MD5 and ignores selected mods. Support ordering in the editor. Supporting repeated maps later requires a separate entry identity and unambiguous removal UI.

## Local map availability

Availability is local derived state, never part of the manifest or course hash. Resolve the exact MD5 first. If absent, use the four-field tuple `(artist, title, mapper, difficulty)` against installed `(artist, title, creator, version)` metadata: a match is `outdated`, no match is `missing`. The tuple identifies a possible wrong revision, not a playable substitute. Matching uses the stored romanized fields independently of the display-language preference; proposed conservative normalization and ambiguity handling are defined in [the recovery plan](MAP_AVAILABILITY_AND_RECOVERY.md).

An exact map must also exist on disk and pass its object-count check. A missing file invalidates a stale database match; an exact-hash/count mismatch is a blocked corruption condition, not ready. Course viewing remains enabled with missing entries omitted from the song list and outdated candidates visibly labeled. Full-course Play requires every entry ready; its manager action stays Download missing maps until then, even after failed automatic recovery.

Rescan after course import, after the beatmap-import database/library commit, and after a repair batch. Resolve `beatmapid` using the selected existing mirror, download sequentially by set, validate exact bytes before replacing storage, update/reconcile the map database, and rescan. Wrong/unavailable versions keep their issue and manual-import guidance; do not rewrite the course's MD5/counts or silently accept matching metadata. This feature is in the offline MVP; the optional download uses the existing mirror, not the future course backend.

## Mods and rule precedence

1. Start each map with a deep copy of its authored APIMod list. Settings such as Difficulty Adjust are part of the required mods.
2. A player may add and configure whitelist mods. The whitelist does not permit changing settings on, removing, or replacing an authored mod. Incompatible additions are rejected with a reason; do not let ModHashMap silently evict required mods.
3. Speedrun grants an explicit speed-only exception: expose one effective per-map rate control before the run and freeze all chosen rates at start. Existing `ModCustomSpeed` (`CS`) and its `rateMultiplier` support a 0.50 through 3.00 range in 0.05 steps. Propose those limits for v1; the server must use identical values. Do not accidentally allow Difficulty Adjust or variable-rate Wind Up/Down through the speed exception.
4. For speedrun maps with authored DT/HT/NC/CS, compute one effective rate rather than stacking another multiplier. Preserve authored pitch behavior where possible, record both base mods and effective mods, and whitelist only the rate change. Reject variable-rate authored mods in speedrun v1 unless a verified timing integrator is implemented.
5. Course scoring chooses ScoreV1 or ScoreV2. Propose rejecting an authored SV2 mod that conflicts with `scorev1`, and injecting the internal effective SV2 choice for `scorev2`. EX and accuracy use native per-map statistics for the component detail while their course total follows the selected course rule. Record engine choices separately from user mods.
6. Course gauge and pause policy take precedence over ordinary gameplay shortcuts. Reject conflicting fail-controller mods such as NF with a failing gauge, and PF/SD with `none`; permitted PF/SD failures terminate a run rather than auto-restarting a map. Preserve normal behavior outside courses.

An additional-mod selection should apply consistently across the run. Preflight all maps and reject a selection that conflicts with any authored map. Speed choices are per authored map index and remain attached to that index after randomization. Effective mods must be visible before starting and recorded for each component.

## Events and gauges

Define a semantic event stream with stable map-local sequence numbers, object IDs, nested IDs where applicable, and map timestamps. The six policy events are `hit300`, `hit100`, `hit50`, `hit0`, `sliderbreak`, and `sliderend`.

- Emit exactly one final `hit*` judgment for each circle, slider, and spinner. Slider nested successes and spinner bonus points are not additional final judgments.
- Emit `sliderbreak` for each genuine break-producing missed slider head, tick, or repeat according to gameplay behavior, not every frame a finger is untracked.
- Emit `sliderend` for an actually missed tail. Current callbacks overload the final slider judgment and tail combo award, so derive this from the slider's explicit tail state; do not infer it from final judgment alone.
- If a tail miss also produces a real slider break, emit both, as confirmed. A whole-slider miss can also have a final `hit0`; preserve distinct events and do not manufacture extra duplicates.
- All distinct events at the same gameplay timestamp form one batch in deterministic object/nested order. Apply their net gauge delta, clamp once, then evaluate failure; this avoids callback-order-dependent results. EX adds every event delta in the batch. This batching rule is proposed and needs a shared fixture.

| Gauge | Behavior |
| --- | --- |
| `none` | No HP failure. Preserve judgment/scoring events; custom gauge settings are inactive. |
| `standard` | Normal gameplay HP, reset at each map; terminal failure ends the course immediately. Preserve normal HP modifiers such as Easy behavior where compatible. |
| `global` | Start once at `starting_hp`. For each event batch, add configured deltas and clamp to `[0,total_hp]`; zero fails immediately. Carry absolute HP to the next map. No passive drain, standard judgment healing, combo bonus healing, or Easy extra lives. |

The course gauge must be authoritative during global runs. Mirroring its normalized value to a HUD field must not invoke ordinary `changeHp` life and resurrection behavior. Failure is terminal even if a later callback would heal. Freeze HP during loading, transitions, pauses, and after the terminal event.

## Aggregation and timing

| Mode | Authoritative aggregate |
| --- | --- |
| ScoreV1 | Sum each completed map's native displayed ScoreV1 after its effective mod multiplier; no cross-map combo multiplier |
| ScoreV2 | Sum each completed map's native displayed ScoreV2 after its effective mod multiplier; do not average or normalize across maps |
| EX score | Keep the signed sum of the six configured event deltas across all maps; final published course score is `max(0, rawExTotal)` |
| Accuracy | Ratio of pooled judgment counts: `(300*N300 + 100*N100 + 50*N50) / (300*(N300+N100+N50+N0))`; no averaging per-map percentages |

Store numerator and denominator for accuracy and round only for display. Slider-break and tail events do not add denominator objects. Preserve per-map native statistics separately from course scoring. Proposed aggregate combo display is the largest component max combo, labeled accordingly; do not imply a cross-map native combo. Course PP is undefined and should not be displayed or credited as ordinary PP. Define a course grade presentation without manufacturing a single-beatmap grade or perfect flag.

EX starts at raw zero once per course. Do not floor individual events, per-map contributions, or intermediate totals: later hits first offset earlier negative values. Display the signed running value during gameplay and signed raw contributions in the breakdown; show the floored final aggregate on completion or any terminal result. Retain `raw_ex_total` with the nonnegative published score so the client and verifier can explain/recompute it. For example, event deltas `[-1,+2]` produce raw/final `1`, not `2`; map contributions `[-3,+2]` produce raw `-1` and final `0`, not `2`. The existing sprite score font lacks a minus glyph, so implement a small course-only sign/fallback rather than changing the scoring rule for a skin limitation.

Speedrun time is elapsed real active time measured by a monotonic clock, not scaled map time, wall-clock date, frame count, or `length / selectedRate`. Proposal: start at the first playable engine update after initial auto-skip and stop when the final object is judged. Include playable intro when not auto-skipped, in-map breaks, and active stalls; exclude pauses, loading, results animation, the existing lead-out, and configured transitions. Accumulate high-resolution durations and round once to integer milliseconds at the total. The precise start/stop event identifiers belong in the ruleset and timing fixtures. Do not allow manual skipping to gain an advantage unless its behavior is identical to the frozen auto-skip rule.

Proposed speedrun leaderboard priority is lowest verified active time, then course metric descending, then accuracy descending, then earliest server acceptance. Non-speedrun priority is course metric descending, then accuracy descending, then earliest acceptance. Ranking priority still needs product confirmation.

## Frozen attempts and lifecycle

Freeze the validated manifest bytes/hash, `ruleset_version`, selected extra mods, all speeds, and an order permutation before map 1. With `random_order`, use an unbiased shuffle and persist the realized permutation. Record a seed for diagnosis, but the explicit permutation is the verification input; do not depend on JVM and JavaScript default RNGs agreeing. Map entries are referenced by original index and played index.

Suggested states: `preflight`, `ready`, `loading`, `playing`, `saving_component`, `transition`, `completed`, `failed`, `aborted`, and `interrupted`. Upload state is separate. Completion and failure callbacks must be idempotent. A generation token prevents a canceled or old loader from starting another map.

Pause false blocks manual pause and uses the multiplayer-style two-step exit UX without setting multiplayer globals. Proposed lifecycle policy: focus loss or backgrounding during a no-pause run marks it interrupted and stops it; do not continue hidden audio/input or allow a resumable ranked attempt. Pause true suspends the active timer. No retry of a component inside a full run; Retry starts a fresh whole attempt. Process-death recovery preserves completed evidence and interruption status, not a resumable ranked run.

## Result and upload envelope

The portable course and an attempt are separate objects. Propose `CourseAttemptV1` with:

- `client_attempt_id` generated once by the client as its local identity and account-scoped idempotency key; authenticated account ID, course hash, course version, full manifest snapshot, course name/author snapshot, and independent `ruleset_version`. A separately assigned `server_attempt_id` identifies the remote resource after registration; it is absent during offline play.
- Outcome, original-to-played order, selected extra mods, effective per-map mods/rates, timing boundaries, pause/focus markers, and optional server run ticket.
- One component per played map, containing indices, map MD5/counts, native score and judgment counts, six event counts, event evidence reference, starting/ending HP, completed/failed state, and replay size plus SHA-256.
- Aggregate score, signed `raw_ex_total` for EX, accuracy numerator/denominator, largest map combo, active duration when speedrun, and component count. Published EX is nonnegative; its raw total/component contributions may be signed. Integer scores and large counters use decimal strings in API JSON where needed to avoid JavaScript precision loss; IDs/count indices stay bounded JSON integers.

Replays remain existing per-map `.odr` files. A separate course evidence sidecar binds the new event/timing data to `client_attempt_id`, component indices, mods, and replay digests, so offline evidence is immutable before any server ID exists. Upload receipts and the finalized signed envelope bind that evidence to both attempt IDs and authenticated uid. Component URLs and leaderboard/detail links use `server_attempt_id`; retry detection uses `(uid, client_attempt_id)`. Replaying one component opens its map; there is no fabricated multi-map `.odr`. Existing replay evidence may not reconstruct every timing/policy event, so official verification must wait for the required event/replay analysis support.

Official definition status and verified play status are separate: an approved manifest can have an unverified, invalid, or offline attempt. The server registry grants official status only after recomputing the manifest hash. Client locks and author display names confer no trust. Proposed v1 boards contain only verified completed plays for any known course hash. An approved definition grants the official course label; a verified custom course board remains labeled custom. Unverified attempts remain in history without a ranked board in v1. An official course leaderboard entry therefore needs both an approved definition and a verified completed play.

## Proposed decisions to freeze before gameplay implementation

1. Confirm field names, `format`, required inactive groups, numeric bounds, integer-only gauge/EX settings, unique-map membership, and hash sensitivity to all metadata and array ordering.
2. Confirm additional mods apply to all maps, speedrun rate limits and base-rate override behavior, and handling of fail-controller and ScoreV2 conflicts.
3. Confirm active-time boundaries, no-pause focus-loss termination, timestamp batching, failed-attempt retention, and aggregate combo/grade presentation.
4. Confirm speedrun time as the ranking primary, whether allowed-mod variants share a board, and whether offline attempts can become verified official scores. Recommended first release: retain all valid attempts, but only evidence-complete verified attempts appear on the official board.
5. Choose registry administration ownership and whether offline official badges require signed registry records. Default proposal: online lookup with a clearly dated cache; imported files cannot self-assert official status.

## 23 November UI and local preference clarification

Allowed mods remain course-wide; no per-map allowed_mods manifest field is added. The course editor opens the existing mod selector in allowlist-authoring mode, where selecting incompatible alternatives together is valid because they are options, not an active combination. Authored map mods remain required and immutable for a player. Each course/map pair may select its own compatible optional additions from that course-wide list. Save those player choices and its speed locally in Room, keyed by local course ID and authored map MD5; neither preference belongs in the exported definition or course hash. Frozen attempts retain per-map effective choices for replay/retry and future verification.

Speedrun rates are 0.50x through 3.00x inclusive in 0.05 steps. Manual numeric input rounds to the nearest step (half-up), then clamps to the supported interval. Store integer hundredths (50..300, divisible by 5) to avoid floating point step drift. Invalid/nonfinite text cannot overwrite a valid saved preference or start a run. The native rate-setting metadata and serialization must support 3.00x as well. Older result snapshots without per-map optional additions or 300G/100K fields decode using their existing global additions and zero defaults respectively.
