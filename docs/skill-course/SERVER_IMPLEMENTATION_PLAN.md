# Skill Course server implementation plan

Implement Skill Courses in `N:\Tournament-Server\osu-droid-tournament-server`, the inspected Express/TypeScript single-player score service. Store immutable course definitions and every valid attempt by content hash, plus each component's native result, replay and course evidence. The server determines official definition status independently from verification of an individual play.

Dan courses are obsolete, as confirmed by the user. Skill Courses should replace their API and storage assumptions rather than inherit the one-beatmap Dan model. Existing records and replay files must remain intact until a separate archival decision. Implement in an isolated clone or worktree on a Skill Course branch; the SMB checkout currently has uncommitted work on `hardware-attestation-test` and must not be switched or deployed as part of planning.

The [shared contract](SHARED_CONTRACT.md) defines the proposed v1 file and gameplay semantics. The [client plan](CLIENT_IMPLEMENTATION_PLAN.md) defines emitted evidence and upload persistence. Proposed endpoints below do not exist yet.

This work starts at client milestone C6. C1 through C5 deliver a fully functional offline MVP accepted with AVDs, including missing/outdated detection and sequential map recovery through the game's existing mirrors. They require no server deployment, official lookup, course submission, global course leaderboard or physical-device gate. C6 has two distinct completion gates: server changes/staging integration and real-device ADB end-to-end validation. Shared fixture design can precede C6, but executing the TypeScript/backend tests is not a prerequisite for C5 acceptance.

## Additional deferred leaderboard requirement

Confirmed during the post-MVP client revision: ordinary beatmap Online PP is deferred to the server phase too. The inspected `src/routes/getLeaderboard.ts` returns native score/combo/rank/mods/accuracy/avatar fields, without PP values or a PP-ranking mode. The client now cycles through an honest unavailable state for Online PP; it makes no invented PP request and never relabels native score as PP. Course view uses local component and total boards until the course global API exists.

Before enabling ordinary Online PP, define and test an explicit versioned response/ranking contract with authoritative PP values and eligibility rules. Preserve the existing score response for older clients. The exact API design and PP computation/storage policy remain server-phase decisions; no backend source or deployment was changed by the client refinements.

## Inspected backend

| Existing source | Observed behavior and consequence |
| --- | --- |
| `package.json` | Node >=18, TypeScript, Express 4, MongoDB driver, PostgreSQL/MySQL adapters, pnpm 10.28.0; build is `tsc`. `test` is a placeholder that exits unsuccessfully. |
| `src/index.ts` | API prefix `/api/droid`; installs `express-form-data` globally with `maxFilesSize: 2000000`, then JSON/urlencoded parsers and rate limiting. A multi-replay upload cannot simply reuse this whole-request parser. |
| `src/routes/submit.ts` | One map hash, one replay, whitespace-delimited score data, app protocol version exactly `51`, session/signature middleware, attestation checks and replay SHA-256 comparison. It creates DanCourseScore from client-supplied statistics. |
| `src/utils/MultiplayerUtil.ts` | `validateSession` looks up the tournament account session in PostgreSQL; request signing and exact OkHttp user-agent validation are separate helpers. New JSON/binary endpoints need explicit compatible adapters. |
| `src/database/managers/DatabaseManager.ts` | Connects to local MongoDB database `AliceDB`. No replica-set transaction capability is established by this code. |
| `src/database/managers/DBCollection.ts` | Exposes `dancoursemaps`, `dancoursescore`, and `dancourseleaderboard` collection managers. Add Skill Course managers and indexes with separate records. |
| `src/structures/database/aliceDb/IDanCourse.ts`, `src/utils/DanCourseUtil.ts` | A Dan course is one `.osu` MD5, a pass requirement and a role ID; courses are loaded into a map keyed by beatmap hash. This is not a multi-map course definition. |
| `src/routes/submit.ts` and Dan leaderboard manager | Stores the winner in a best-score collection and other results in history. New attempts need one durable attempt identity and replay references independent of best-score replacement. |
| `getLeaderboard.ts`, `getScore.ts`, `getReplay.ts` | Legacy text APIs with beatmap hash/user lookup. One detail branch treats playID as uid; it cannot identify a particular historical course attempt. Replay access includes Dan-course assumptions. |
| `src/structures/anticheat/index.ts` | Defines evidence/verdict types. The inspected submit route does not invoke a replay-based scoring/gauge verifier; checksum and attestation do not recalculate gameplay. |
| `src/utils/attestation.ts`, submit attestation branch | Existing key/signature verification can inform new domain-separated payloads. Preserve existing enforcement and development-policy distinctions. Do not treat a valid device signature as proof of a correct course result. |

The sibling `multi-server-node` has Socket.IO, Drizzle and Vitest, but is a different service. Do not add Skill Course persistence to that PostgreSQL schema merely because it has a test runner. Shared account/attestation integration may use existing adapters; sequential single-player courses do not need a multiplayer room.

## Service structure

Follow existing TypeScript style, module aliases, asynchronous handler/error conventions and manager abstractions. Proposed additions:

- `structures/skillcourse/`: versioned manifest, ruleset, attempt, component, verification and response types.
- `utils/SkillCourseCodec.ts` and `SkillCourseValidator.ts`: bounded strict JSON validation, JCS/hash and shared fixtures. Check existing dependencies before adding a validator/canonicalizer. Do not assume the sibling service's Zod is installed here.
- `database/managers/aliceDb/SkillCourse*CollectionManager.ts`: definitions, approval registry, attempts and components, with startup-created indexes or an explicit migration command.
- `services/skillcourse/`: attempt lifecycle, replay storage, evidence verification, aggregation, leaderboard query and registry administration. Match an existing service directory if one is introduced before implementation; do not force gameplay verification into route handlers.
- `routes/skillcourse/`: capability, definition verification, attempt creation/detail, component upload, finalization and leaderboard routes.

Mount the new versioned router under `/api/droid/courses/v1`. Give it its own parsers and limits before the legacy global multipart middleware. Reuse existing middleware behavior where correct, but avoid applying a body-based signature/session helper to a binary stream without an adapter. Leave the legacy route request/response formats compatible during deprecation.

## Definition registration and official status

Maintain a definition catalog keyed by server-recomputed SHA-256. Any authenticated player may submit a bounded valid custom manifest with an attempt; the server stores it under that hash even when unofficial. A client does not need to register a course publicly before offline play.

Official approval is a separate privileged registry record keyed by course hash, with status `approved` or `revoked`, ruleset support, display/catalog metadata and an audit trail. The manifest's author is display text and grants no privilege. Import locks are local editing affordances and do not affect approval.

For the first release, prefer an operator CLI that validates a manifest, prints hash plus semantic summary, and records approval/revocation using the existing deployment's operator access. Do not expose a web approval endpoint until administrative authentication and authorization are defined. The CLI must not ingest arbitrary paths from course contents or execute file content.

Recommended verification response:

```json
{
  "course_hash": "<64 lowercase hex characters>",
  "course_version": 1,
  "definition_status": "official",
  "supported_rulesets": [1],
  "checked_at": "<server timestamp>",
  "registry_revision": "<opaque revision>"
}
```

An unknown hash is `unofficial`; unreachable service is a client `unknown/unavailable` state. A revoked manifest remains retrievable with its status. Historical attempts record approval status at acceptance and can also show current revocation. Editing even a display field changes the manifest hash and requires approval for the new definition.

An online authenticated lookup with a dated client cache is enough for initial recognition. If an offline-verifiable official badge is required, add signed registry receipts and a key-rotation/revocation policy; do not put a client-asserted `official` boolean in `.odc`.

## MongoDB records and indexes

Use new collections in the existing database. Names below are proposed and should follow manager conventions.

| Collection | Key fields and purpose |
| --- | --- |
| `skillcoursedefinitions` | Unique `courseHash`; full canonical manifest and source schema version; name/author summaries, creation time, byte size. Definitions are immutable. |
| `skillcourseregistry` | Unique `courseHash`; approval/revocation state, supported rulesets, operator/audit metadata and registry revision. |
| `skillcourseattempts` | Unique server attempt ID; unique `(uid, clientAttemptId)`; courseHash, rulesetVersion, immutable definition reference and display snapshots; frozen order/mods/rates; outcome, aggregate, evidence manifest digest, verification status and timestamps. |
| `skillcoursecomponents` | Unique `(serverAttemptId, playedIndex)`; original index, map MD5/counts, native statistics, events/digests, effective mods, HP/time boundaries, replay storage object and receipt state. |
| `skillcourseverificationjobs` | Optional durable queue or job fields on attempts. Persist claiming/lease/retry state if verification runs asynchronously; no in-memory-only queue for accepted pending uploads. |

Attempt states should distinguish `uploading`, `submitted`, `verifying`, `verified`, `unverified`, `invalid` and `incomplete`. Keep gameplay outcome (`completed`, `failed`, `aborted`, `interrupted`) separate. Invalid payloads receive a reason and do not become ranked records; valid failed/aborted attempts may be retained as history. Rank only completed attempts passing all required verification.

Use `client_attempt_id` as the portable/local idempotency key and `server_attempt_id` as the separately allocated opaque remote resource ID. Creation returns both. The account-scoped uniqueness constraint uses the client ID; URLs, receipts and leaderboard/detail links use the server ID. Offline evidence sidecars bind the client ID because no server ID exists during play; receipts and final attestation bind the immutable evidence to both IDs and the authenticated uid. Camel-case names in internal TypeScript/Mongo records must map explicitly to the snake_case wire contract.

Recommended indexes:

- Definition and registry unique courseHash indexes.
- Unique `(uid, clientAttemptId)` for account-scoped retries; reject conflicting payload reuse.
- Unique `(serverAttemptId, playedIndex)` plus an index on replay digest for integrity/reference accounting.
- `(courseHash, rulesetVersion, verificationStatus, outcome, score descending, acceptedAt ascending, serverAttemptId)` for score queries; a separate active-time index for speedrun queries.
- `(uid, courseHash, createdAt descending, serverAttemptId)` for personal history and retention reporting.

Store 64-bit native and aggregate scores losslessly. The API uses decimal strings for values that may exceed JavaScript safe integer precision; validate as bounded integers, compute using BigInt, and choose BSON Long or a validated decimal representation consistently. EX event contributions and `raw_ex_total` remain signed; the published course EX score is nonnegative and equals `max(0, raw_ex_total)`. Do not stringify scores and then sort lexicographically in MongoDB. Accuracy uses integer numerator/denominator and exact comparisons with cross multiplication; rounded percentages are presentation only.

A materialized best-per-player board is optional. If added, treat it as derived/rebuildable data keyed by course hash plus ruleset and board policy. It never owns the sole copy of an attempt or replay. Requirement: submitting a lower score cannot overwrite or delete a previous attempt's evidence.

## Proposed API

All paths below are relative to `/api/droid/courses/v1`. JSON responses include machine-readable error codes and bounded human messages. Authenticate writes with the existing account/session authority; define a header adapter for streaming uploads and never place sessions in URLs. Use HTTPS through the existing deployment proxy.

| Method and path | Request and behavior |
| --- | --- |
| `GET /capabilities` | Schema/ruleset support, enabled features, upload limits, verification availability and ranking policy. No secrets or infrastructure configuration. |
| `POST /verify` | Bounded manifest plus claimed hash; recompute hash, return definition/official status and supported rulesets. Do not approve content. |
| `GET /definitions/:hash` | Retrieve stored portable manifest and separate current registry status. Subject to content/visibility policy and throttling. |
| `POST /attempts` | Session-authenticated JSON with stable client attempt ID, full manifest/claimed hash, frozen order, selected extras, effective mods/rates and ruleset. Validate; create or return the same owned upload session. |
| `PUT /attempts/:id/components/:index/replay` | Bounded streamed `.odr`, declared size/SHA-256, authenticated owner. Content-addressed or generated storage key, never a client path. Identical retry succeeds; changed digest at an acknowledged index is a conflict. |
| `PUT /attempts/:id/components/:index/evidence` | Bounded versioned event/timing sidecar with its declared digest; immutable once acknowledged. Bind to map, replay digest, course hash, attempt and indices. |
| `POST /attempts/:id/finalize` | Full ordered component metadata, outcome, claimed aggregate, manifest digest and attestation. Validate receipts, freeze the envelope and enqueue verification exactly once. |
| `GET /attempts/:id` | Aggregate, component summaries, verification state and structured reasons; authorize detailed evidence access according to ownership/public score policy. Also supports recovery after a lost response. |
| `GET /:hash/leaderboard` | Ruleset/board selector, bounded limit/cursor; default verified completed attempts, one best entry per player if that policy is confirmed. Return actual attempt IDs. |
| `GET /attempts/:id/components/:index/replay` | Stream/download the exact component, enforcing replay visibility and ownership rules; no filename or uid-only lookup. |

Register a full manifest on `POST /attempts` so unknown custom courses are retained by hash. Offline results can create an upload session after play; a pre-play server ticket is optional and recorded separately. If official policy requires a fresh pre-play ticket, the same upload API still retains ticketless results as unverified history.

Use `201` for a new attempt, `200` for idempotent repetition/detail, `202` for finalized pending verification, and `409` for conflicting attempt/evidence reuse. Distinguish malformed input (`400`), expired session (`401`), forbidden ownership or required attestation (`403`), missing referenced attempt/map (`404` or domain-specific pending state), unsupported schema/ruleset (`422`), excessive payload (`413`) and throttling (`429` plus Retry-After). Decide exact codes once with the client and test them as a contract.

Suggested error shape:

```json
{
  "error": {
    "code": "COMPONENT_DIGEST_CONFLICT",
    "message": "This component already has different replay evidence.",
    "retryable": false
  }
}
```

## Upload consistency and storage

The current global 2 MB form parser and `Util.readFile` buffering are unsuitable for a whole-course archive. Upload each component separately and stream to disk with byte counters, declared-size checks, digest calculation and aborted-request cleanup. Proposed initial limits: definition 1 MiB, replay 16 MiB per map, evidence sidecar 16 MiB per map, and total attempt evidence 256 MiB. These are configurable deployment limits to validate against replay fixtures and real long maps; advertise them before play. Enforce both per-map and aggregate limits, and do not buffer 256 maps in memory.

Use server-generated IDs or content-addressed objects under a dedicated `files/skillcourses/` root. A replay filename or course title cannot select a path. Write into private staging, close and flush, atomically promote on the same filesystem, and then acknowledge its receipt. Persist the digest, size and storage object in the component record. Confine any decoder subprocess and never execute Java serialized replay objects with a general-purpose ObjectInputStream on the server.

Do not assume MongoDB multi-document transactions work on the current deployment. A safe standalone design is:

1. Create an `uploading` attempt with a unique owner/client attempt ID.
2. Upsert immutable component receipts using unique keys; each receipt is individually atomic and accepts only an identical retry.
3. Finalize by compare-and-set from `uploading` to `submitted` with an immutable evidence manifest digest after all expected receipts exist.
4. A durable verifier claims the attempt using an atomic state/lease transition, reads immutable receipts and calculates results.
5. Mark the attempt verified only when all evidence and aggregates pass. Queries filter by that committed status; incomplete records never enter the board.
6. Update derived best-score caches afterward and allow rebuild/retry. A cache failure does not discard the verified attempt.

Reconcile promoted-but-unreferenced files and expired incomplete uploads using a grace period and references. Do not apply TTL indexes to completed attempts or their component evidence: the user asked to retain all results. Limit abandoned staging and malicious uploads with quotas/rate limits rather than silently deleting valid course history. Back up both MongoDB metadata and replay/evidence storage and test restoration of their linkage.

## Verification pipeline

### Definition and run configuration

Reparse with the exact supported schema, reject duplicate keys and invalid mod settings, recompute JCS/SHA-256, and compare with the request. Validate the explicit order as a permutation of all authored indices; `random_order = false` requires natural order. Never trust the submitted display name/author independently of the stored manifest.

Verify each effective mod list against authored required mods, the course whitelist and the speedrun exception. Prevent a whitelist addition from evicting an authored mod. Apply exactly the client's settings ranges and incompatibility rules; do not rely on a different osu-base version's defaults. Distinguish internal engine mods from selectable additions. Validate uniform additional-mod selection and per-index rate binding even after randomization.

Course hash fixes the definition, while `rulesetVersion` fixes judgment/gauge/scoring/timing interpretation. Board queries must not merge results across incompatible ruleset versions. Preserve older ruleset handlers for old accepted evidence or leave those attempts explicitly unverified when the verifier is unavailable.

### Component map and replay evidence

Client missing/outdated state is diagnostic and local: metadata tuple matches may identify a wrong revision for repair, but never satisfy server MD5/count verification. Do not add new course-server download endpoints for the C5 repair flow; clients use existing mirror difficulty-ID lookup and sequential archive download. Manual import or replacement cannot rewrite the frozen course identity.

For each played component:

1. Resolve exact beatmap MD5 from a trusted cached `.osu` source; recompute file MD5 and circle/slider/spinner counts. An online difficulty ID is a download hint. A changed upstream revision must not substitute for the course's hash. Bound downloads and cache only validated content.
2. Compare receipt byte size and SHA-256 to the finalized manifest. Decode the supported `.odr` format using a purpose-built bounded reader or established decoder. Its Java serialization and zipped payload details need explicit implementation/version coverage; the presence of osu-base does not establish droid replay decoding support.
3. Check replay map identity, mods/settings and object counts against the frozen component. Verify native statistics from replay events where the format permits; keep any unsupported case unverified.
4. Reconstruct or independently cross-check final object judgments, nested breaks and tail misses against beatmap structure and replay data. The client's six event counts are claims, not sufficient evidence of global-HP survival.
5. Validate active-time boundaries, pause/focus markers and effective rate timeline against replay timestamps and supported clock semantics. Reject seeks, local component retries, impossible object order and duplicated component evidence according to the ruleset.

The inspected legacy submit route only validates replay checksum and device signature before accepting client statistics. Reusing it unchanged would not verify the new global gauge, EX score or speedrun. Treat a proper replay/evidence verifier as a separate deliverable. If continuous input simulation is beyond the first version, label the level precisely: integrity-checked or client-attested is not equivalent to independently replay-verified.

### Course rules

Replay the semantic event sequence to recompute gauge from starting HP. Verify HP carry, clamping, deterministic same-timestamp batches and immediate terminal failure. Global gauge has no passive drain or Easy lives. Apply both break and tail deltas if both distinct events occurred. A final HP value alone cannot prove that the player did not fail and then heal.

Recompute signed raw EX from verified events across all maps, then apply exactly one final floor: `publishedEx = max(0, raw_ex_total)`. Negative component contributions and live raw values are valid; a negative published EX claim or a claim derived by flooring each event/map is invalid. For example, `[-1,+2]` must produce final 1, while map contributions `[-3,+2]` must produce final 0. Keep both raw and published values and bind both in the finalized evidence envelope. Sum native displayed map scores for ScoreV1/ScoreV2 using the fork's matching formula and mod multiplier behavior; compute pooled accuracy from judgment totals. Do not substitute an osu!standard scoring formula. Reconcile server and client arithmetic with shared expected vectors before ranking. Recompute speedrun duration from supported evidence, excluding load/transition/pause spans and retaining active breaks/stalls.

For completed outcomes, require every map exactly once in realized order and no terminal policy violation. For failed/aborted outcomes, permit only a valid ordered prefix and at most one final partial component; retain it as history without ranking it as completed. Unplayed maps have no manufactured results or replays.

### Attestation and freshness

Keep the deployment's existing account/attestation requirements; do not weaken them to make courses work. Use a distinct signature domain such as `osu-droid-skill-course-attempt-v1` and bind uid, `client_attempt_id`, `server_attempt_id`, optional server ticket/nonce, course hash, ruleset version, frozen order/mods/rates, component replay/evidence digests, outcome and aggregate claim. Canonicalize the signed envelope and exclude the signature itself and mutable session credential.

The current payload `userID|beatmapHash|scoreData` is insufficient for a multi-map run and cannot be reused by substituting the course hash. Add replay and sidecar digests to the signed envelope. Session authentication binds ownership at upload; client timestamps cannot establish freshness. Record bypass/development acceptance as a distinct verification level and keep it outside the production verified board.

A server run ticket can prevent submitting a known replay under a fresh attempt identity and bind challenge freshness, but cannot by itself prove uninterrupted play or correct local timing. If strict pre-play tickets are required for official ranking, retain offline attempts under the same course hash with their lower verification level. This policy remains to be confirmed.

## Leaderboards and retention

Keep all valid attempts and query a board separately. Proposed default is each player's best verified completed attempt for the selected course hash and ruleset; provide history/pagination for all retained attempts. A leaderboard row contains the actual attempt ID, authenticated player display name, course metric, exact accuracy values, active time for speedrun, effective mod summary, verification level and acceptance time.

Ranking proposal: score/EX/accuracy descending for normal courses; active duration ascending for speedrun, then course metric, then accuracy, then server acceptance time and attempt ID for stability. EX ranks by its nonnegative published score; a negative raw total is not a hidden extra tie-breaker among zero-score results. Compare exact integer/rational values rather than display strings. Document whether additional allowed mods share the primary board or require filter categories; do not silently split courses by a hidden client-only choice.

Definition approval and attempt verification are independent filters. Proposed v1 exposes verified-completed-only boards for any known course hash; a custom definition's board is labeled custom, while an official course board additionally requires current registry approval. Unverified attempts remain in history and do not get a separate ranked board in v1. Revoked courses retain data and historical approval status but lose the current official label. Ordinary map PP, account total score and global accuracy must not be updated from a course aggregate; a course is not one official beatmap score.

## Replacing obsolete Dan course behavior

1. Add new storage and `/courses/v1` without changing old data formats. Stop adding new dependencies on DanCourseUtil and DanCourseScore.
2. Introduce a backend feature flag/capability for Skill Course submission and official verification. Gate client rollout using that capability.
3. Retire Dan-specific boot initialization and old leaderboard/detail/replay routes once the client no longer needs them; decide whether legacy routes return a clear retired response or remain read-only temporarily.
4. Keep old MongoDB collections and `files/replays` intact until their backup/archive and retention decisions are explicit. Do not reinterpret a Dan MD5 as a Skill Course SHA-256.
5. If old Dan definitions should appear in the new catalog, write an explicit one-map conversion tool using verified source maps and generate a new manifest/hash; do not auto-migrate old replays into verified course attempts.

Avoid unrelated attestation or multiplayer refactors. The server checkout already contains modified submission/login/replay/leaderboard code; inspect those diffs in the implementation clone before integrating changes.

## Testing and rollout milestones

| Milestone | Deliverable | Exit criteria |
| --- | --- | --- |
| S1 Contract and fixtures | Types, strict validation/hash and shared vectors in C6 | Kotlin and TypeScript agree on bytes/hash, mods, signed raw EX/final floor and expected events; does not block offline C5 |
| S2 Storage and upload | Managers/indexes, streamed receipts, stable IDs and finalization | Every attempt retained; concurrent retries yield one record; crash-recovery tests pass on actual Mongo topology |
| S3 Definition registry | Operator approval/revocation and client lookup | Client cannot self-approve; modified manifest loses badge; cache/revocation behavior covered |
| S4 Evidence verifier | Replay decoder, event/gauge/score/timing reconstruction and attestation envelope | Valid and forged fixtures classified correctly; unsupported evidence stays unverified |
| S5 Query APIs | History, actual-attempt detail, component replay and leaderboard | Ownership, visibility, pagination, deterministic ties and exact sorting covered |
| S6 Retirement and deployment | Capability rollout, Dan deprecation, monitoring and recovery | Legacy data backed up; isolated staging passes end-to-end tests; rollback keeps accepted data readable |

Use a test app factory and disposable database/storage roots so HTTP tests do not start the production listener or connect to production databases. Add a real test command; the current `pnpm test` placeholder is not a passing suite. Prefer the Node test runner already available in the chosen runtime or the project's agreed runner, with a reproducible local dependency decision. `pnpm exec tsc --noEmit` is the narrow compile check; `pnpm build` writes `dist` and belongs in the isolated implementation checkout.

The offline C5 MVP may be completed and tested before any server change. In C6, deploy server storage and receipt endpoints with ranking disabled, then enable the client's network feature, then verification and official ranking after both server and real-device ADB gates pass. Monitor upload bytes/latency, incomplete sessions, verification reasons, duplicate conflicts, queue age, replay storage growth and per-course counts. Log IDs/status codes and digests as needed, never sessions, signatures, replay input streams or full credential-bearing request bodies. Roll back by disabling capability/acceptance, preserving new collections and evidence for later reprocessing; do not attempt a destructive down-migration.

## C5 UI revision implications for C6

The 23 November revision keeps allowed_mods course-wide and adds no per-map manifest allowlist. Local player choices are stored per local course/map pair, excluded from course identity, and frozen as per-map effective mods and rates in attempt evidence. C6 validation must accept supported static speedrun rates through 3.00x in 0.05 steps and must validate each map's own optional additions against the same course-wide policy. Native component snapshots now retain 300G/100K in addition to final judgment counts for faithful result presentation; these auxiliary counters do not change the pooled-accuracy denominator or EX events. Implement parity for these changes before accepting uploads.
