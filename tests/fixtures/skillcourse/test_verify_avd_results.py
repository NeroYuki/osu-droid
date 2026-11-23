"""Focused unit tests for the offline Skill Course evidence verifier.

Run from the repository root:
    python tests/fixtures/skillcourse/test_verify_avd_results.py
"""

from __future__ import annotations

import copy
import hashlib
import io
import json
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_avd_results as verifier  # noqa: E402


HASH_A = "a" * 32
HASH_B = "b" * 32


def _map(map_hash: str, counts: list[int], difficulty: str) -> dict:
    return {
        "map_hash": map_hash,
        "object_counts": counts,
        "artist": "Codex Fixture",
        "artist_unicode": "Codex Fixture",
        "title": "Skill Course Fixture",
        "title_unicode": "Skill Course Fixture",
        "mapper": "Codex Fixture",
        "difficulty": difficulty,
        "beatmapid": None,
        "mods": [],
    }


def _definition(
    name: str,
    *,
    maps: list[dict],
    life_gauge: str = "none",
    scoring: str = "exscore",
    is_speedrun: bool = False,
    random_order: bool = False,
    starting_hp: int = 80,
) -> dict:
    return {
        "format": "osu-droid-skill-course",
        "course_name": name,
        "course_version": 1,
        "author": "Codex Fixture",
        "life_gauge": life_gauge,
        "gauge_settings": {
            "total_hp": 100,
            "starting_hp": starting_hp,
            "hit300": 1,
            "hit100": 0,
            "hit50": -1,
            "hit0": -3,
            "sliderbreak": -1,
            "sliderend": 0,
        },
        "scoring": scoring,
        "exscore_settings": {
            "hit300": 2,
            "hit100": 1,
            "hit50": 0,
            "hit0": -1,
            "sliderbreak": 0,
            "sliderend": 0,
        },
        "allow_pause": True,
        "map_transition_ms": 500 if is_speedrun else 0,
        "auto_skip": True,
        "is_speedrun": is_speedrun,
        "random_order": random_order,
        "allowed_mods": [],
        "maps": maps,
    }


def _component(
    *,
    original: int,
    played: int,
    map_hash: str,
    hit300: int,
    misses: int,
    score: int,
    raw_ex: int,
    starting_hp: int | None,
    ending_hp: int | None,
    active_nanos: int,
    completed: bool,
    replay: str | None,
    replay_sha: str | None,
    events_file: str | None,
    mods: list[dict] | None = None,
) -> dict:
    return {
        "originalIndex": original,
        "playedIndex": played,
        "mapHash": map_hash,
        "score": score,
        "maxCombo": hit300,
        "hit300": hit300,
        "hit100": 0,
        "hit50": 0,
        "misses": misses,
        "sliderHeadHits": 0,
        "sliderTickHits": 0,
        "sliderRepeatHits": 0,
        "sliderEndHits": 0,
        "modsJson": json.dumps(mods or []),
        "rawExContribution": raw_ex,
        "startingHp": starting_hp,
        "endingHp": ending_hp,
        "activeNanos": active_nanos,
        "completed": completed,
        "replayFilename": replay,
        "replaySha256": replay_sha,
        "eventsFilename": events_file,
    }


def _events(judgments: list[str], *, object_ids: list[int] | None = None) -> list[dict]:
    ids = object_ids if object_ids is not None else list(range(len(judgments)))
    return [
        {
            "sequence": index,
            "objectId": ids[index],
            "mapTimeMs": 1000 + index * 200,
            "judgment": judgment,
        }
        for index, judgment in enumerate(judgments)
    ]


def _event(
    sequence: int, object_id: int, map_time_ms: int, judgment: str
) -> dict:
    return {
        "sequence": sequence,
        "objectId": object_id,
        "mapTimeMs": map_time_ms,
        "judgment": judgment,
    }


def _write_evidence(
    root: Path,
    attempt_id: str,
    component_index: int,
    events: list[dict],
    *,
    payload: bytes | None = None,
) -> tuple[str, str]:
    directory = root / "Attempts" / attempt_id
    directory.mkdir(parents=True, exist_ok=True)
    replay_name = "%d.odr" % component_index
    events_name = "%d.events.json" % component_index

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "data",
            payload if payload is not None else b"component-replay-%d" % component_index,
        )
    replay_bytes = buffer.getvalue()
    (directory / replay_name).write_bytes(replay_bytes)
    (directory / events_name).write_text(json.dumps(events), encoding="utf-8")
    return replay_name, hashlib.sha256(replay_bytes).hexdigest()


def _speedrun_attempt(root: Path, attempt_id: str = "attempt-speedrun") -> dict:
    definition = _definition(
        "99 Synthetic",
        maps=[
            _map(HASH_A, [1, 0, 0], "A Circles"),
            _map(HASH_B, [1, 0, 0], "B Slider Spinner"),
        ],
        life_gauge="global",
        is_speedrun=True,
        random_order=True,
    )

    events_b = _events(["HIT300"])
    events_a = _events(["HIT300"])
    replay_b, sha_b = _write_evidence(root, attempt_id, 0, events_b)
    replay_a, sha_a = _write_evidence(root, attempt_id, 1, events_a)

    components = [
        _component(
            original=1,
            played=0,
            map_hash=HASH_B,
            hit300=1,
            misses=0,
            score=1000,
            raw_ex=2,
            starting_hp=80,
            ending_hp=81,
            active_nanos=1_000_000_000,
            completed=True,
            replay=replay_b,
            replay_sha=sha_b,
            events_file="0.events.json",
            mods=[{"acronym": "CS", "settings": {"rateMultiplier": 0.9}}],
        ),
        _component(
            original=0,
            played=1,
            map_hash=HASH_A,
            hit300=1,
            misses=0,
            score=1200,
            raw_ex=2,
            starting_hp=81,
            ending_hp=82,
            active_nanos=2_000_000_000,
            completed=True,
            replay=replay_a,
            replay_sha=sha_a,
            events_file="1.events.json",
            mods=[{"acronym": "CS", "settings": {"rateMultiplier": 1.1}}],
        ),
    ]
    return {
        "clientAttemptId": attempt_id,
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": "completed",
        "reason": None,
        "rulesetVersion": 1,
        "order": [1, 0],
        "components": components,
        "rawExTotal": 4,
        "activeNanos": 3_000_000_000,
        "replayDirectory": str(root / "Attempts" / attempt_id),
        "additionalModsJson": "[]",
        "speeds": {"0": 1.1, "1": 0.9},
    }


def _single_map_attempt(
    root: Path,
    *,
    judgment: str,
    attempt_id: str,
    outcome: str = "completed",
    reason: str | None = None,
    include_evidence: bool = True,
) -> dict:
    definition = _definition(
        "99 Synthetic",
        maps=[_map(HASH_A, [1, 0, 0], "A Circles")],
        life_gauge="none",
    )
    events = _events([judgment])
    if include_evidence:
        replay, sha = _write_evidence(root, attempt_id, 0, events)
        events_file = "0.events.json"
    else:
        (root / "Attempts" / attempt_id).mkdir(parents=True, exist_ok=True)
        replay = sha = events_file = None
    hit300 = 1 if judgment == "HIT300" else 0
    misses = 1 if judgment == "HIT0" else 0
    raw_ex = 2 if judgment == "HIT300" else -1
    components = [
        _component(
            original=0,
            played=0,
            map_hash=HASH_A,
            hit300=hit300,
            misses=misses,
            score=1000,
            raw_ex=raw_ex,
            starting_hp=None,
            ending_hp=None,
            active_nanos=1_000_000_000,
            completed=True,
            replay=replay,
            replay_sha=sha,
            events_file=events_file,
        )
    ]
    return {
        "clientAttemptId": attempt_id,
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": outcome,
        "reason": reason,
        "rulesetVersion": 1,
        "order": [0],
        "components": components,
        "rawExTotal": raw_ex,
        "activeNanos": 1_000_000_000,
        "replayDirectory": str(root / "Attempts" / attempt_id),
        "additionalModsJson": "[]",
        "speeds": {},
    }


def _miss_all_attempt(
    root: Path,
    name: str,
    *,
    life_gauge: str,
    starting_hp: int,
    attempt_id: str,
) -> dict:
    definition = _definition(
        name,
        maps=[
            _map(HASH_A, [4, 0, 0], "A Circles"),
            _map(HASH_B, [1, 1, 1], "B Slider Spinner"),
        ],
        life_gauge=life_gauge,
        starting_hp=starting_hp,
    )
    events_a = [
        _event(index, index, 1500 + index * 1000, "HIT0") for index in range(4)
    ]
    events_b = [
        _event(0, 0, 1500, "HIT0"),
        _event(1, 1, 2500, "HIT0"),
        _event(2, 1, 2500, "SLIDERBREAK"),
        _event(3, 1, 3500, "SLIDERBREAK"),
        _event(4, 2, 4500, "HIT0"),
        _event(5, 2, 4500, "SLIDEREND"),
    ]
    replay_a, sha_a = _write_evidence(root, attempt_id, 0, events_a)
    replay_b, sha_b = _write_evidence(root, attempt_id, 1, events_b)
    global_hp = life_gauge == "global"
    components = [
        _component(
            original=0,
            played=0,
            map_hash=HASH_A,
            hit300=0,
            misses=4,
            score=0,
            raw_ex=-4,
            starting_hp=starting_hp if global_hp else None,
            ending_hp=68 if global_hp else None,
            active_nanos=4_000_000_000,
            completed=True,
            replay=replay_a,
            replay_sha=sha_a,
            events_file="0.events.json",
        ),
        _component(
            original=1,
            played=1,
            map_hash=HASH_B,
            hit300=0,
            misses=3,
            score=0,
            raw_ex=-3,
            starting_hp=68 if global_hp else None,
            ending_hp=57 if global_hp else None,
            active_nanos=6_000_000_000,
            completed=True,
            replay=replay_b,
            replay_sha=sha_b,
            events_file="1.events.json",
        ),
    ]
    return {
        "clientAttemptId": attempt_id,
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": "completed",
        "reason": None,
        "rulesetVersion": 1,
        "order": [0, 1],
        "components": components,
        "rawExTotal": -7,
        "activeNanos": 10_000_000_000,
        "replayDirectory": str(root / "Attempts" / attempt_id),
        "additionalModsJson": "[]",
        "speeds": {},
    }


def _global_fail_attempt(root: Path) -> dict:
    definition = _definition(
        "03 Global Fail",
        maps=[
            _map(HASH_A, [4, 0, 0], "A Circles"),
            _map(HASH_B, [1, 1, 1], "B Slider Spinner"),
        ],
        life_gauge="global",
        starting_hp=3,
    )
    events = [_event(0, 0, 1500, "HIT0")]
    replay, sha = _write_evidence(root, "global-fail", 0, events)
    component = _component(
        original=0,
        played=0,
        map_hash=HASH_A,
        hit300=0,
        misses=1,
        score=0,
        raw_ex=-1,
        starting_hp=3,
        ending_hp=0,
        active_nanos=1_000_000_000,
        completed=False,
        replay=replay,
        replay_sha=sha,
        events_file="0.events.json",
    )
    return {
        "clientAttemptId": "global-fail",
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": "failed",
        "reason": "The global life gauge reached zero.",
        "rulesetVersion": 1,
        "order": [0, 1],
        "components": [component],
        "rawExTotal": -1,
        "activeNanos": 1_000_000_000,
        "replayDirectory": str(root / "Attempts" / "global-fail"),
        "additionalModsJson": "[]",
        "speeds": {},
    }


def _standard_fail_attempt(root: Path) -> dict:
    definition = _definition(
        "04 Standard Fail",
        maps=[
            _map(HASH_A, [2, 0, 0], "D Standard Failure"),
            _map(HASH_B, [4, 0, 0], "A Circles"),
        ],
        life_gauge="standard",
        scoring="scorev1",
    )
    events = [_event(0, 0, 1000, "HIT0")]
    replay, sha = _write_evidence(root, "standard-fail", 0, events)
    component = _component(
        original=0,
        played=0,
        map_hash=HASH_A,
        hit300=0,
        misses=1,
        score=0,
        raw_ex=-1,
        starting_hp=None,
        ending_hp=None,
        active_nanos=1_000_000_000,
        completed=False,
        replay=replay,
        replay_sha=sha,
        events_file="0.events.json",
    )
    return {
        "clientAttemptId": "standard-fail",
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": "failed",
        "reason": "The player failed the map.",
        "rulesetVersion": 1,
        "order": [0, 1],
        "components": [component],
        "rawExTotal": -1,
        "activeNanos": 1_000_000_000,
        "replayDirectory": str(root / "Attempts" / "standard-fail"),
        "additionalModsJson": "[]",
        "speeds": {},
    }


def _autoplay_attempt(root: Path) -> dict:
    map_a = _map(HASH_A, [4, 0, 0], "A Circles")
    map_b = _map(HASH_B, [1, 1, 1], "B Slider Spinner")
    map_a["mods"] = [{"acronym": "AT"}]
    map_b["mods"] = [{"acronym": "AT"}]
    definition = _definition(
        "13 Autoplay exscore",
        maps=[map_a, map_b],
        life_gauge="none",
        scoring="exscore",
    )
    events_a = _events(["HIT300"] * 4)
    events_b = _events(["HIT300"] * 3)
    replay_a, sha_a = _write_evidence(root, "autoplay", 0, events_a)
    replay_b, sha_b = _write_evidence(root, "autoplay", 1, events_b)
    components = [
        _component(
            original=0,
            played=0,
            map_hash=HASH_A,
            hit300=4,
            misses=0,
            score=4000,
            raw_ex=8,
            starting_hp=None,
            ending_hp=None,
            active_nanos=2_000_000_000,
            completed=True,
            replay=replay_a,
            replay_sha=sha_a,
            events_file="0.events.json",
            mods=[{"acronym": "AT"}],
        ),
        _component(
            original=1,
            played=1,
            map_hash=HASH_B,
            hit300=3,
            misses=0,
            score=3000,
            raw_ex=6,
            starting_hp=None,
            ending_hp=None,
            active_nanos=3_000_000_000,
            completed=True,
            replay=replay_b,
            replay_sha=sha_b,
            events_file="1.events.json",
            mods=[{"acronym": "AT"}],
        ),
    ]
    return {
        "clientAttemptId": "autoplay",
        "courseId": "course",
        "courseHash": "f" * 64,
        "definition": definition,
        "playerName": "SECRET_PLAYER_NAME",
        "createdAt": 123,
        "outcome": "completed",
        "reason": None,
        "rulesetVersion": 1,
        "order": [0, 1],
        "components": components,
        "rawExTotal": 14,
        "activeNanos": 5_000_000_000,
        "replayDirectory": str(root / "Attempts" / "autoplay"),
        "additionalModsJson": "[]",
        "speeds": {},
    }


class VerifyAvdResultsTest(unittest.TestCase):
    def test_valid_random_speedrun_evidence_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = verifier.verify_attempt(_speedrun_attempt(root), root)
            self.assertEqual([], report.errors)
            self.assertEqual([], report.warnings)
            self.assertTrue(report.ok)
            self.assertIsNone(report.scenario)
            self.assertEqual(4, report.metrics["rawExTotal"])
            self.assertEqual({str(0): 1.1, str(1): 0.9}, report.metrics["rates"])
            self.assertNotIn(
                "SECRET_PLAYER_NAME", json.dumps(report.as_dict(), ensure_ascii=False)
            )

    def test_speedrun_random_scenario_verifies_permutation_and_rates(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _speedrun_attempt(root)
            attempt["definition"]["course_name"] = "06 Speedrun Random"
            report = verifier.verify_attempt(attempt, root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.verified)
            self.assertEqual([1, 0], attempt["order"])

    def test_revised_speed_ceiling_accepts_three_and_rejects_above_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _speedrun_attempt(root)
            attempt["speeds"]["0"] = 3.0
            for component in attempt["components"]:
                if component["originalIndex"] == 0:
                    component["modsJson"] = json.dumps([
                        {"acronym": "CS", "settings": {"rateMultiplier": 3.0}}
                    ])
            report = verifier.verify_attempt(attempt, root)
            self.assertEqual([], report.errors)
            attempt["speeds"]["0"] = 3.05
            invalid = verifier.verify_attempt(attempt, root)
            self.assertTrue(any("speed" in finding.code for finding in invalid.errors))

    def test_rate_is_bound_to_original_index_after_randomization(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _speedrun_attempt(root)
            # Keep the played permutation but give B's component A's frozen rate.
            attempt = copy.deepcopy(attempt)
            attempt["components"][0]["modsJson"] = json.dumps(
                [{"acronym": "CS", "settings": {"rateMultiplier": 1.1}}]
            )
            report = verifier.verify_attempt(attempt, root)
            self.assertIn(
                "speedrun-rate",
                {finding.code for finding in report.errors},
            )

    def test_negative_component_ex_is_allowed_and_only_published_is_floored(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _single_map_attempt(root, judgment="HIT0", attempt_id="negative")
            report = verifier.verify_attempt(attempt, root)
            self.assertEqual([], report.errors)
            self.assertEqual(-1, report.metrics["rawExTotal"])
            self.assertEqual(0, report.metrics["publishedEx"])
            self.assertEqual(
                "HIT0", report.components[0].events[0]["judgment"]
            )

    def test_offline_ex_all_miss_scenario_verifies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _miss_all_attempt(
                root,
                "01 Offline EX",
                life_gauge="none",
                starting_hp=80,
                attempt_id="offline-ex",
            )
            report = verifier.verify_attempt(attempt, root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.applicable)
            self.assertTrue(report.scenario.verified)
            self.assertEqual(-7, report.metrics["rawExTotal"])
            self.assertEqual(0, report.metrics["publishedEx"])
            self.assertEqual(
                True,
                verifier.evaluate_requirements(["01 Offline EX"], [report])[0].satisfied,
            )

    def test_global_carry_all_miss_scenario_verifies_hp(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _miss_all_attempt(
                root,
                "02 Global Carry",
                life_gauge="global",
                starting_hp=80,
                attempt_id="global-carry",
            )
            report = verifier.verify_attempt(attempt, root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.verified)
            self.assertEqual([80, 68], report.metrics["hpStarts"])
            self.assertEqual([68, 57], report.metrics["hpEnds"])

    def test_global_fail_prefix_scenario_verifies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = verifier.verify_attempt(_global_fail_attempt(root), root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.verified)
            self.assertEqual([3], report.metrics["hpStarts"])
            self.assertEqual([0], report.metrics["hpEnds"])
            self.assertEqual(0, report.metrics["firstBatchHp"])

    def test_standard_fail_prefix_scenario_verifies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = verifier.verify_attempt(_standard_fail_attempt(root), root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.verified)
            self.assertEqual([], report.metrics["hpStarts"])

    def test_autoplay_exscore_scenario_verifies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = verifier.verify_attempt(_autoplay_attempt(root), root)
            self.assertEqual([], report.errors)
            self.assertIsNotNone(report.scenario)
            self.assertTrue(report.scenario.verified)
            self.assertEqual(14, report.metrics["rawExTotal"])
            self.assertEqual(1.0, report.metrics["accuracy"])

    def test_duplicate_final_judgment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _single_map_attempt(root, judgment="HIT300", attempt_id="duplicate")
            attempt = copy.deepcopy(attempt)
            events = _events(["HIT300", "HIT300"], object_ids=[0, 0])
            replay, sha = _write_evidence(root, "duplicate", 0, events, payload=b"new")
            attempt["components"][0]["replayFilename"] = replay
            attempt["components"][0]["replaySha256"] = sha
            report = verifier.verify_attempt(attempt, root)
            self.assertIn(
                "events-final-duplicate",
                {finding.code for finding in report.errors},
            )

    def test_missing_incomplete_evidence_with_explicit_error_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            attempt = _single_map_attempt(
                root,
                judgment="HIT300",
                attempt_id="interrupted",
                outcome="interrupted",
                reason="The component replay could not be saved.",
                include_evidence=False,
            )
            report = verifier.verify_attempt(attempt, root)
            self.assertNotIn(
                "missing-evidence",
                {finding.code for finding in report.errors},
            )
            self.assertIn(
                "missing-partial-evidence",
                {finding.code for finding in report.warnings},
            )

    def test_cli_reads_wal_database_and_writes_json_summary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            attempt = _miss_all_attempt(
                evidence,
                "01 Offline EX",
                life_gauge="none",
                starting_hp=80,
                attempt_id="offline-ex",
            )
            database = root / "pulled.db"
            connection = sqlite3.connect(str(database))
            try:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA wal_autocheckpoint=0")
                connection.execute(
                    "CREATE TABLE SkillCourseAttempt (id TEXT, resultJson TEXT)"
                )
                connection.execute(
                    "INSERT INTO SkillCourseAttempt VALUES (?, ?)",
                    (attempt["clientAttemptId"], json.dumps(attempt)),
                )
                connection.commit()
                wal_present_while_open = Path(str(database) + "-wal").is_file()

                json_output = root / "summary.json"
                output = io.StringIO()
                with redirect_stdout(output):
                    status = verifier.main(
                        [
                            "--database",
                            str(database),
                            "--evidence",
                            str(evidence),
                            "--require",
                            "01 Offline EX",
                            "--json-output",
                            str(json_output),
                        ]
                    )
            finally:
                connection.close()

            self.assertEqual(0, status)
            self.assertTrue(wal_present_while_open)
            summary = json.loads(json_output.read_text(encoding="utf-8"))
            self.assertTrue(summary["ok"])
            self.assertEqual(1, summary["summary"]["fixtureAttempts"])
            self.assertNotIn(
                "SECRET_PLAYER_NAME",
                json.dumps(summary, ensure_ascii=False),
            )

    def test_cli_fails_when_required_fixture_is_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "pulled.db"
            evidence = root / "evidence"
            evidence.mkdir()
            connection = sqlite3.connect(str(database))
            try:
                connection.execute(
                    "CREATE TABLE SkillCourseAttempt (id TEXT, resultJson TEXT)"
                )
                connection.commit()
            finally:
                connection.close()

            output = io.StringIO()
            with redirect_stdout(output):
                status = verifier.main(
                    [
                        "--database",
                        str(database),
                        "--evidence",
                        str(evidence),
                        "--require",
                        "01 Offline EX",
                    ]
                )
            self.assertEqual(1, status)
            self.assertIn("FAIL require 01 Offline EX:", output.getvalue())


if __name__ == "__main__":
    unittest.main()
