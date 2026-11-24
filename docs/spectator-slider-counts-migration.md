# Live spectator slider counts migration — 2026-11-25

The Android client now sends cumulative slider head, tick, repeat, and end hit
counts with its live spectator events. The web spectator needs the embedded
patch to decode and retain these counts. The multiplayer server already relays
the binary packet unchanged; it needs no source, database, or configuration changes.

This guide and its patch were prepared against the current source in
`Tournament-Server/osu-droid-spectator`. Only this migration guide is written to
the SMB share by Codex. The web source changes were implemented and tested in a
local snapshot; they have not been deployed to the Linux server.

## Behavior and compatibility

- The client captures counters at successful slider heads, ticks, repeats, and
  slider completion, using the existing `StatisticV2` counters without changing
  gameplay scoring. Packets retain the existing five-second submission cadence.
- All existing 32-byte event records remain unchanged. An optional trailer follows
  the final record: big-endian int32 `0x534c4452` (`SLDR`), int32 version `1`,
  int32 event count, then four int32 counts per event in head/tick/repeat/end order.
  Trailer size is `12 + 16 * eventCount` bytes. Empty event blocks have no trailer.
- The current deployed web decoder continues reading the base event records and
  ignores this trailer. The patched decoder accepts the older 20-byte accuracy
  records and 32-byte hit-count records as well as the new extension.
- Unknown future extensions are ignored. Recognized version-1 trailers must have
  the correct event count and exact length. Missing counts from older clients
  remain `undefined`; actual zero values are preserved.
- Each decoded `SpectatorScoreEvent` has optional `sliderCounts`, with fields
  `sliderHeadHit`, `sliderTickHit`, `sliderRepeatHit`, and `sliderEndHit`. For a
  playback-time consumer, use
  `manager.events.score.eventAtOrDefault(time).sliderCounts`; do not read a future
  packet's last event when drawing an earlier playback time. No new HUD widget
  is introduced by this patch.
- Score event redundancy includes the counters, preserving updates even when
  only a slider count changes. Final multiplayer result JSON already carries
  the counters through the preceding client fix.
- Keep online version `51`; this backward-compatible spectator extension requires
  no further online version bump. Attestation and login behavior are unaffected.

## Apply on Linux

Review local work first and resolve overlapping edits deliberately. Run the
following from `Tournament-Server/osu-droid-spectator`; the migration guide sits
one directory above it. The patch is also stored in the Android repository at
`docs/patches/spectator-slider-counts.patch`.

```bash
git status --short
spectator_patch_file="$(mktemp /tmp/spectator-slider-counts.XXXXXX.patch)"
python3 - ../SPECTATOR_SLIDER_COUNTS_MIGRATION_2026-11-25.md "$spectator_patch_file" <<'PY'
from pathlib import Path
import sys
guide = Path(sys.argv[1]).read_text(encoding="utf-8")
patch = guide.split("```diff\n", 1)[1].split("\n```", 1)[0] + "\n"
Path(sys.argv[2]).write_bytes(patch.encode("utf-8"))
PY
git apply --check "$spectator_patch_file" && git apply "$spectator_patch_file"
npm test && npx tsc --noEmit && npm run build-production
```

Use the server's existing dependency installation and deployment procedures.
Deploy the rebuilt web assets and refresh the viewer to load the updated bundle.
No service was restarted by Codex. If the apply check fails, reconcile the
overlapping files before applying; do not force or discard existing server work.

## Verification completed before handoff

- 143 targeted Android tests passed, including six spectator serialization tests.
- Android debug assembly and lint passed; lint reported zero issues.
- All 11 web spectator protocol tests passed with the real TypeScript/osu-base
  dependencies; the full local web source passed `tsc --noEmit`.
- The Kotlin serializer and TypeScript decoder use the same independently checked
  124-byte packet containing two events, wide scores, nonzero counters and zeros.
- The unchanged deployed decoder successfully parsed that extended packet's
  scores, combos and accuracies, demonstrating compatibility during rollout.
- Tests cover both older layouts, empty packets, bounded client event chunks,
  malformed/truncated trailers, unknown versions, and count-only changes.

The live phone-to-browser flow has not been exercised. After deployment, check a
map containing slider heads, ticks, repeats, and ends and verify the count
snapshots at their playback times. An older client should continue working with
`sliderCounts === undefined` until final-result data arrives.

## Embedded web spectator patch

```diff
diff --git a/src/ts/spectator/SpectatorDataProcessor.ts b/src/ts/spectator/SpectatorDataProcessor.ts
--- a/src/ts/spectator/SpectatorDataProcessor.ts
+++ b/src/ts/spectator/SpectatorDataProcessor.ts
@@ -7,6 +7,7 @@
 import { SpectatorCursorEvent } from "./events/SpectatorCursorEvent";
 import { SpectatorObjectDataEvent } from "./events/SpectatorObjectDataEvent";
 import { SpectatorScoreEvent } from "./events/SpectatorScoreEvent";
+import { SpectatorSliderCounts } from "./rawdata/SpectatorSliderCounts";
 import { MultiplayerPlayer } from "./structures/MultiplayerPlayer";
 import { SpectatorDataManager } from "./managers/SpectatorDataManager";
 import { MovementType } from "./structures/MovementType";
@@ -16,6 +17,16 @@
 interface Counter {
     counter: number;
 }
+
+/**
+ * The magic value identifying the optional slider count trailer.
+ */
+const sliderCountsMagic = 0x534c4452; // "SLDR"
+
+/**
+ * The version of the optional slider count trailer.
+ */
+const sliderCountsVersion = 1;
 
 /**
  * A handler for holding and processing spectator data.
@@ -231,14 +242,75 @@
         //#endregion
 
         //#region Parse events
+        if (view.byteLength - counter.counter < 4) {
+            return false;
+        }
+
         const eventsSize = this.readInt(view, counter);
+        if (eventsSize < 0) {
+            return false;
+        }
+
+        const eventsStart = counter.counter;
+        const remainingBytes = view.byteLength - counter.counter;
 
         // Older clients send a single accuracy float per event, whereas newer
         // clients send the four hit counts. The protocol has no version marker,
-        // so infer the layout from the amount of remaining data.
+        // so infer the layout from the amount of remaining data before reading
+        // any event.
         const isLegacyEventLayout =
-            eventsSize > 0 &&
-            view.byteLength - counter.counter === eventsSize * 20;
+            eventsSize > 0 && remainingBytes === eventsSize * 20;
+        const eventRecordSize = isLegacyEventLayout ? 20 : 32;
+        const eventRecordsSize = eventsSize * eventRecordSize;
+
+        if (remainingBytes < eventRecordsSize) {
+            return false;
+        }
+
+        let sliderCounts: SpectatorSliderCounts[] | undefined;
+
+        if (!isLegacyEventLayout && remainingBytes > eventRecordsSize) {
+            const extensionOffset = eventsStart + eventRecordsSize;
+            const extensionLength = remainingBytes - eventRecordsSize;
+
+            if (
+                extensionLength >= 4 &&
+                view.getInt32(extensionOffset) === sliderCountsMagic
+            ) {
+                // A recognized trailer must at least contain its 12-byte header.
+                if (extensionLength < 12) {
+                    return false;
+                }
+
+                const version = view.getInt32(extensionOffset + 4);
+                const count = view.getInt32(extensionOffset + 8);
+
+                if (version === sliderCountsVersion) {
+                    if (count !== eventsSize) {
+                        return false;
+                    }
+
+                    if (extensionLength !== 12 + eventsSize * 16) {
+                        return false;
+                    }
+
+                    sliderCounts = [];
+
+                    for (let i = 0; i < eventsSize; ++i) {
+                        const offset = extensionOffset + 12 + i * 16;
+
+                        sliderCounts.push({
+                            sliderHeadHit: view.getInt32(offset),
+                            sliderTickHit: view.getInt32(offset + 4),
+                            sliderRepeatHit: view.getInt32(offset + 8),
+                            sliderEndHit: view.getInt32(offset + 12),
+                        });
+                    }
+                }
+                // Unknown future versions and their extension data are ignored.
+            }
+            // Unknown future extensions and their data are ignored.
+        }
 
         for (let i = 0; i < eventsSize; ++i) {
             const time = this.readFloat(view, counter);
@@ -260,7 +332,9 @@
                     total === 0 ? 1 : (6 * n300 + 2 * n100 + n50) / (6 * total);
             }
 
-            events.score.add(new SpectatorScoreEvent(time, score));
+            events.score.add(
+                new SpectatorScoreEvent(time, score, sliderCounts?.[i]),
+            );
             events.combo.add(new SpectatorComboEvent(time, combo));
             events.accuracy.add(new SpectatorAccuracyEvent(time, accuracy));
         }
diff --git a/src/ts/spectator/events/SpectatorScoreEvent.ts b/src/ts/spectator/events/SpectatorScoreEvent.ts
--- a/src/ts/spectator/events/SpectatorScoreEvent.ts
+++ b/src/ts/spectator/events/SpectatorScoreEvent.ts
@@ -1,7 +1,8 @@
+import { SpectatorSliderCounts } from "../rawdata/SpectatorSliderCounts";
 import { SpectatorEvent } from "./SpectatorEvent";
 
 /**
- * Emitted whenever the player's score changes.
+ * Emitted whenever the player's score or slider hit counts change.
  */
 export class SpectatorScoreEvent extends SpectatorEvent {
     /**
@@ -9,13 +10,37 @@
      */
     readonly score: number;
 
-    constructor(time: number, score: number) {
+    /**
+     * The cumulative slider hit counts of the player, if the client sent them.
+     */
+    readonly sliderCounts?: SpectatorSliderCounts;
+
+    constructor(
+        time: number,
+        score: number,
+        sliderCounts?: SpectatorSliderCounts,
+    ) {
         super(time);
 
         this.score = score;
+        this.sliderCounts = sliderCounts;
     }
 
     override isRedundant(existing: SpectatorScoreEvent): boolean {
-        return this.score === existing.score;
+        if (this.score !== existing.score) {
+            return false;
+        }
+
+        if (!this.sliderCounts || !existing.sliderCounts) {
+            return this.sliderCounts === existing.sliderCounts;
+        }
+
+        return (
+            this.sliderCounts.sliderHeadHit === existing.sliderCounts.sliderHeadHit &&
+            this.sliderCounts.sliderTickHit === existing.sliderCounts.sliderTickHit &&
+            this.sliderCounts.sliderRepeatHit ===
+                existing.sliderCounts.sliderRepeatHit &&
+            this.sliderCounts.sliderEndHit === existing.sliderCounts.sliderEndHit
+        );
     }
 }
diff --git a/src/ts/spectator/rawdata/SpectatorSliderCounts.ts b/src/ts/spectator/rawdata/SpectatorSliderCounts.ts
new file mode 100644
--- /dev/null
+++ b/src/ts/spectator/rawdata/SpectatorSliderCounts.ts
@@ -0,0 +1,26 @@
+/**
+ * The cumulative slider hit counts of a player at the time of a spectator event.
+ *
+ * The field names match the slider statistics sent in the multiplayer score submission payload.
+ */
+export interface SpectatorSliderCounts {
+    /**
+     * The cumulative amount of slider head hits.
+     */
+    readonly sliderHeadHit: number;
+
+    /**
+     * The cumulative amount of slider tick hits.
+     */
+    readonly sliderTickHit: number;
+
+    /**
+     * The cumulative amount of slider repeat hits.
+     */
+    readonly sliderRepeatHit: number;
+
+    /**
+     * The cumulative amount of slider end hits.
+     */
+    readonly sliderEndHit: number;
+}
diff --git a/tests/spectator-protocol.test.cjs b/tests/spectator-protocol.test.cjs
--- a/tests/spectator-protocol.test.cjs
+++ b/tests/spectator-protocol.test.cjs
@@ -46,8 +46,18 @@
     return { parser, manager, events };
 }
 
-function packet(legacy, entries) {
-    const data = new ArrayBuffer(16 + entries.length * (legacy ? 20 : 32));
+function packet(legacy, entries, extension) {
+    const recordSize = legacy ? 20 : 32;
+    const counts =
+        extension && extension.counts
+            ? extension.counts
+            : entries.map(event => event.counts ?? [0, 0, 0, 0]);
+    const extensionSize = extension
+        ? 12 + counts.length * 16 + (extension.extraBytes ?? 0)
+        : 0;
+    const data = new ArrayBuffer(
+        16 + entries.length * recordSize + extensionSize,
+    );
     const view = new DataView(data);
     let offset = 0;
     const write = (method, size, value) => { view[method](offset, value); offset += size; };
@@ -62,7 +72,41 @@
         if (legacy) write("setFloat32", 4, event.accuracy);
         else for (const count of event.hits) write("setInt32", 4, count);
     }
+    if (extension) {
+        write("setInt32", 4, extension.magic ?? SLIDER_COUNTS_MAGIC);
+        write("setInt32", 4, extension.version ?? 1);
+        write("setInt32", 4, extension.count ?? entries.length);
+        for (const sliderCounts of counts) {
+            for (const count of sliderCounts) write("setInt32", 4, count);
+        }
+        for (let i = 0; i < (extension.extraBytes ?? 0); i++) {
+            write("setUint8", 1, 0);
+        }
+    }
     return data;
+}
+
+const SLIDER_COUNTS_MAGIC = 0x534c4452;
+
+const GOLDEN_PACKET_HEX =
+    "41480000000000000000000000000002" +
+    "42c80000000000012a05f2000000000a00000001000000000000000000000001" +
+    "434800000000000165a0bc000000001400000003000000000000000000000001" +
+    "534c4452000000010000000200000003000000040000000500000006" +
+    "00000000000000000000000000000000";
+
+function arrayBufferFromHex(hex) {
+    const bytes = Buffer.from(hex, "hex");
+    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
+}
+
+function sliderCounts(head, tick, repeat, end) {
+    return {
+        sliderHeadHit: head,
+        sliderTickHit: tick,
+        sliderRepeatHit: repeat,
+        sliderEndHit: end,
+    };
 }
 
 for (const legacy of [true, false]) {
@@ -77,6 +121,8 @@
         assert.deepEqual(events.score.values.map(e => e.score), [5_000_000_000, 6_000_000_000]);
         assert.deepEqual(events.combo.values.map(e => e.combo), [10, 20]);
         assert.deepEqual(events.accuracy.values.map(e => e.accuracy), [0.5, 0.75]);
+        assert.equal(events.score.values[0].sliderCounts, undefined);
+        assert.equal(events.score.values[1].sliderCounts, undefined);
     });
 }
 
@@ -85,6 +131,137 @@
     assert.equal(parser.process(123, packet(false, [])), true);
     assert.equal(manager.latestDataTime, 12_500);
     assert.equal(events.score.values.length, 0);
+});
+
+test("attaches extended slider counts to the matching time-indexed events", () => {
+    const { parser, manager, events } = processor();
+    const data = packet(false, [
+        { time: 100, score: 5_000_000_000, combo: 10, hits: [1, 0, 0, 1], counts: [3, 4, 5, 6] },
+        { time: 200, score: 6_000_000_000, combo: 20, hits: [3, 0, 0, 1], counts: [0, 0, 0, 0] },
+        { time: 300, score: 7_000_000_000, combo: 30, hits: [0, 1, 0, 0], counts: [1, 2, 0, 9] },
+    ], {});
+
+    assert.equal(parser.process(123, data), true);
+    assert.equal(manager.latestDataTime, 12_500);
+    assert.deepEqual(events.score.values.map(e => e.time), [100, 200, 300]);
+    assert.deepEqual(events.score.values.map(e => e.score), [5_000_000_000, 6_000_000_000, 7_000_000_000]);
+    assert.deepEqual(events.combo.values.map(e => e.combo), [10, 20, 30]);
+    assert.deepEqual(events.score.values.map(e => e.sliderCounts), [
+        sliderCounts(3, 4, 5, 6),
+        sliderCounts(0, 0, 0, 0),
+        sliderCounts(1, 2, 0, 9),
+    ]);
+    // Real zero counts must be retained instead of being fabricated or dropped.
+    assert.notEqual(events.score.values[1].sliderCounts, undefined);
+    assert.equal(events.score.values[1].sliderCounts.sliderHeadHit, 0);
+});
+
+test("parses the golden extended packet shared with the Android sender", () => {
+    const { parser, manager, events } = processor();
+    const data = arrayBufferFromHex(GOLDEN_PACKET_HEX);
+
+    assert.equal(data.byteLength, 124);
+    assert.equal(parser.process(123, data), true);
+    assert.equal(manager.latestDataTime, 12_500);
+    assert.deepEqual(events.score.values.map(e => e.time), [100, 200]);
+    assert.deepEqual(events.score.values.map(e => e.score), [5_000_000_000, 6_000_000_000]);
+    assert.deepEqual(events.combo.values.map(e => e.combo), [10, 20]);
+    assert.deepEqual(events.accuracy.values.map(e => e.accuracy), [0.5, 0.75]);
+    assert.deepEqual(events.score.values.map(e => e.sliderCounts), [
+        sliderCounts(3, 4, 5, 6),
+        sliderCounts(0, 0, 0, 0),
+    ]);
+});
+
+test("rejects truncated extended packets", () => {
+    const { parser, events } = processor();
+    const entries = [
+        { time: 100, score: 5_000_000_000, combo: 10, hits: [1, 0, 0, 1], counts: [3, 4, 5, 6] },
+    ];
+    const data = packet(false, entries, {});
+
+    // Truncated core event record.
+    assert.equal(parser.process(123, data.slice(0, 30)), false);
+
+    // Recognized magic with a truncated trailer header.
+    assert.equal(parser.process(123, data.slice(0, 16 + 32 + 6)), false);
+
+    // Recognized trailer with a truncated count body.
+    assert.equal(parser.process(123, data.slice(0, data.byteLength - 4)), false);
+
+    assert.equal(events.score.values.length, 0);
+});
+
+test("rejects missing or negative event counts", () => {
+    const { parser, events } = processor();
+    const data = packet(false, []);
+    assert.equal(parser.process(123, data.slice(0, 14)), false);
+    new DataView(data).setInt32(12, -1);
+    assert.equal(parser.process(123, data), false);
+    assert.equal(events.score.values.length, 0);
+});
+
+test("rejects recognized trailers with a mismatched event count or length", () => {
+    const { parser, events } = processor();
+    const entries = [
+        { time: 100, score: 5_000_000_000, combo: 10, hits: [1, 0, 0, 1], counts: [3, 4, 5, 6] },
+        { time: 200, score: 6_000_000_000, combo: 20, hits: [3, 0, 0, 1], counts: [0, 0, 0, 0] },
+    ];
+
+    assert.equal(parser.process(123, packet(false, entries, { count: 1 })), false);
+    assert.equal(parser.process(123, packet(false, entries, { extraBytes: 4 })), false);
+    assert.equal(events.score.values.length, 0);
+});
+
+test("ignores unknown future trailer versions and magic while retaining base events", () => {
+    const entries = [
+        { time: 100, score: 5_000_000_000, combo: 10, hits: [1, 0, 0, 1], counts: [3, 4, 5, 6] },
+        { time: 200, score: 6_000_000_000, combo: 20, hits: [3, 0, 0, 1], counts: [0, 0, 0, 0] },
+    ];
+
+    for (const extension of [{ version: 2 }, { magic: 0x12345678 }]) {
+        const { parser, events } = processor();
+
+        assert.equal(parser.process(123, packet(false, entries, extension)), true);
+        assert.deepEqual(events.score.values.map(e => e.time), [100, 200]);
+        assert.deepEqual(events.score.values.map(e => e.score), [5_000_000_000, 6_000_000_000]);
+        assert.equal(events.score.values[0].sliderCounts, undefined);
+        assert.equal(events.score.values[1].sliderCounts, undefined);
+    }
+});
+
+test("score event redundancy preserves count-only changes", () => {
+    const { SpectatorScoreEvent } = loadTypescript("spectator/events/SpectatorScoreEvent.ts", {});
+    const counts = sliderCounts(1, 0, 0, 0);
+
+    assert.equal(
+        new SpectatorScoreEvent(100, 500, counts).isRedundant(
+            new SpectatorScoreEvent(100, 500, sliderCounts(1, 0, 0, 0)),
+        ),
+        true,
+    );
+    assert.equal(
+        new SpectatorScoreEvent(100, 500, counts).isRedundant(
+            new SpectatorScoreEvent(100, 500, sliderCounts(1, 0, 0, 1)),
+        ),
+        false,
+    );
+    assert.equal(
+        new SpectatorScoreEvent(100, 500, counts).isRedundant(
+            new SpectatorScoreEvent(100, 500),
+        ),
+        false,
+    );
+    assert.equal(
+        new SpectatorScoreEvent(100, 500).isRedundant(new SpectatorScoreEvent(100, 500)),
+        true,
+    );
+    assert.equal(
+        new SpectatorScoreEvent(100, 500, counts).isRedundant(
+            new SpectatorScoreEvent(100, 501, counts),
+        ),
+        false,
+    );
 });
 
 test("scoreSubmission retains the server's mods and all slider counts", () => {
```
