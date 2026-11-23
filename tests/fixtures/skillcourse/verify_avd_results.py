"""Verify pulled Skill Course AVD evidence without ADB, Gradle, or network access.

Usage:
    python tests/fixtures/skillcourse/verify_avd_results.py \
        --database pulled/droid.db \
        --evidence pulled/SkillCourses \
        --require "01 Offline EX" \
        --json-output summary.json

The verifier is deliberately narrow:

* it only reads the ``resultJson`` column of the Room ``SkillCourseAttempt``
  table (a temporary copy of the database and its ``-wal``/``-shm`` sidecars is
  used so the pulled evidence is never modified);
* it only considers generated fixture attempts, identified by
  ``definition.author == "Codex Fixture"`` or by one of the course names emitted
  by ``generate_avd_fixtures.py``;
* it reads only the attempt's ``Attempts/<clientAttemptId>/`` evidence files;
* it never prints the stored player/account name.

This is an offline *evidence* verifier.  It does not drive the UI, does not
decide whether the product is correct, and does not turn a passing run into an
official score.  The parent agent reviews the human-readable findings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable, Optional


FIXTURE_AUTHOR = "Codex Fixture"

# Exact names written by generate_avd_fixtures.py.
FIXTURE_COURSES = (
    "01 Offline EX",
    "02 Global Carry",
    "03 Global Fail",
    "04 Standard Fail",
    "05 Outdated And Missing",
    "06 Speedrun Random",
    "07 No Pause",
    "08 Accuracy",
    "09 ScoreV2",
    "10 Autoplay scorev1",
    "11 Autoplay scorev2",
    "12 Autoplay accuracy",
    "13 Autoplay exscore",
    "14 Perfect Fail",
)

TERMINAL_OUTCOMES = frozenset({"completed", "failed", "aborted", "interrupted"})
KNOWN_OUTCOMES = TERMINAL_OUTCOMES | {"in_progress"}

FINAL_JUDGMENTS = {
    "HIT300": "hit300",
    "HIT100": "hit100",
    "HIT50": "hit50",
    "HIT0": "misses",
}
ALL_JUDGMENTS = frozenset(
    {"HIT300", "HIT100", "HIT50", "HIT0", "SLIDERBREAK", "SLIDEREND"}
)

DEFAULT_GAUGE = {
    "total_hp": 100,
    "starting_hp": 80,
    "hit300": 1,
    "hit100": 0,
    "hit50": -1,
    "hit0": -3,
    "sliderbreak": -1,
    "sliderend": 0,
}
DEFAULT_EX = {
    "hit300": 2,
    "hit100": 1,
    "hit50": 0,
    "hit0": -1,
    "sliderbreak": 0,
    "sliderend": 0,
}
JUDGMENT_TO_EX_KEY = {
    "HIT300": "hit300",
    "HIT100": "hit100",
    "HIT50": "hit50",
    "HIT0": "hit0",
    "SLIDERBREAK": "sliderbreak",
    "SLIDEREND": "sliderend",
}
JUDGMENT_TO_GAUGE_KEY = dict(JUDGMENT_TO_EX_KEY)

# Expected terminal outcomes for the named acceptance claims.  Courses absent
# from this map are still structurally verified but have no prescribed outcome.
EXPECTED_OUTCOMES = {
    "01 Offline EX": "completed",
    "02 Global Carry": "completed",
    "03 Global Fail": "failed",
    "04 Standard Fail": "failed",
    "06 Speedrun Random": "completed",
    "10 Autoplay scorev1": "completed",
    "11 Autoplay scorev2": "completed",
    "12 Autoplay accuracy": "completed",
    "13 Autoplay exscore": "completed",
}

SCENARIO_DESCRIPTIONS = {
    "01 Offline EX": "completed all-miss run: raw EX -7, published EX 0, 0% accuracy",
    "02 Global Carry": "completed all-miss run: HP 80 -> 68 -> 57, raw EX -7",
    "03 Global Fail": "failed after one map with global HP 3 -> 0 on the first event batch",
    "04 Standard Fail": "failed standard-gauge prefix with one incomplete first map",
    "06 Speedrun Random": "completed random-order speedrun with rates bound to original indices",
    "10 Autoplay scorev1": "completed authored-AT run with positive native scores and 100% accuracy",
    "11 Autoplay scorev2": "completed authored-AT run with positive native scores and 100% accuracy",
    "12 Autoplay accuracy": "completed authored-AT run with positive native scores and 100% accuracy",
    "13 Autoplay exscore": "completed authored-AT run with positive native scores, 100% accuracy and EX 14",
}


class VerificationFailure(Exception):
    """Fatal input failure before per-attempt verification can start."""


@dataclass
class Finding:
    code: str
    message: str

    def as_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


@dataclass
class ComponentAudit:
    index: int
    played_index: Optional[int] = None
    original_index: Optional[int] = None
    completed: Optional[bool] = None
    events: Optional[list[dict[str, Any]]] = None
    starting_hp: Optional[int] = None
    ending_hp: Optional[int] = None
    rate: Optional[float] = None
    mod_acronyms: list[str] = field(default_factory=list)
    replay_ok: bool = False
    events_ok: bool = False


@dataclass
class HpAudit:
    starts: list[Optional[int]] = field(default_factory=list)
    ends: list[Optional[int]] = field(default_factory=list)
    first_batch_hp: Optional[int] = None


@dataclass
class ScenarioResult:
    name: str
    applicable: bool
    verified: bool
    reason: str
    details: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "applicable": self.applicable,
            "verified": self.verified,
            "reason": self.reason,
            "details": self.details,
        }


@dataclass
class AttemptReport:
    attempt_id: str
    course_name: str
    outcome: str
    errors: list[Finding] = field(default_factory=list)
    warnings: list[Finding] = field(default_factory=list)
    components: list[ComponentAudit] = field(default_factory=list)
    scenario: Optional[ScenarioResult] = None
    metrics: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors

    def error(self, code: str, message: str) -> None:
        self.errors.append(Finding(code, message))

    def warn(self, code: str, message: str) -> None:
        self.warnings.append(Finding(code, message))

    def as_dict(self) -> dict[str, Any]:
        return {
            "attemptId": self.attempt_id,
            "courseName": self.course_name,
            "outcome": self.outcome,
            "ok": self.ok,
            "metrics": self.metrics,
            "components": [
                {
                    "playedIndex": component.played_index,
                    "originalIndex": component.original_index,
                    "completed": component.completed,
                    "startingHp": component.starting_hp,
                    "endingHp": component.ending_hp,
                    "rate": component.rate,
                    "replayVerified": component.replay_ok,
                    "eventsVerified": component.events_ok,
                    "mods": component.mod_acronyms,
                }
                for component in self.components
            ],
            "scenario": None if self.scenario is None else self.scenario.as_dict(),
            "errors": [finding.as_dict() for finding in self.errors],
            "warnings": [finding.as_dict() for finding in self.warnings],
        }


@dataclass
class RequirementResult:
    course_name: str
    satisfied: bool
    reason: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "courseName": self.course_name,
            "satisfied": self.satisfied,
            "reason": self.reason,
        }


@dataclass
class RunReport:
    database: str
    evidence: str
    attempts: list[AttemptReport] = field(default_factory=list)
    requirements: list[RequirementResult] = field(default_factory=list)
    errors: list[Finding] = field(default_factory=list)
    warnings: list[Finding] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors and all(item.satisfied for item in self.requirements)

    def as_dict(self) -> dict[str, Any]:
        error_count = len(self.errors) + sum(len(item.errors) for item in self.attempts)
        warning_count = len(self.warnings) + sum(len(item.warnings) for item in self.attempts)
        return {
            "verifier": "skillcourse-avd-evidence-v1",
            "ok": self.ok,
            "database": self.database,
            "evidence": self.evidence,
            "summary": {
                "fixtureAttempts": len(self.attempts),
                "errors": error_count,
                "warnings": warning_count,
                "requirementsSatisfied": sum(1 for item in self.requirements if item.satisfied),
                "requirementsFailed": sum(1 for item in self.requirements if not item.satisfied),
            },
            "attempts": [attempt.as_dict() for attempt in self.attempts],
            "requirements": [item.as_dict() for item in self.requirements],
            "errors": [finding.as_dict() for finding in self.errors],
            "warnings": [finding.as_dict() for finding in self.warnings],
        }


# ---------------------------------------------------------------------------
# Small typed helpers
# ---------------------------------------------------------------------------


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _int_or_none(value: Any) -> Optional[int]:
    return value if _is_int(value) else None


def _float_or_none(value: Any) -> Optional[float]:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _course_name(result: dict[str, Any]) -> str:
    name = _mapping(result.get("definition")).get("course_name")
    return name if isinstance(name, str) and name else "<unknown>"


def _attempt_id(result: dict[str, Any], fallback: str) -> str:
    value = result.get("clientAttemptId")
    return value if isinstance(value, str) and value else fallback


def _is_fixture_attempt(result: dict[str, Any]) -> bool:
    definition = _mapping(result.get("definition"))
    return (
        definition.get("author") == FIXTURE_AUTHOR
        or definition.get("course_name") in FIXTURE_COURSES
    )


def _manifest_total(course_map: dict[str, Any]) -> Optional[int]:
    counts = course_map.get("object_counts")
    if not isinstance(counts, list) or len(counts) != 3:
        return None
    if not all(_is_int(value) and value >= 0 for value in counts):
        return None
    return counts[0] + counts[1] + counts[2]


def _safe_child(directory: Path, filename: Any) -> Optional[Path]:
    """Return a direct child of ``directory`` or None for unsafe names."""
    if not isinstance(filename, str) or not filename:
        return None
    path = Path(filename)
    if path.is_absolute() or path.name != filename or filename in {".", ".."}:
        return None
    try:
        resolved = (directory / filename).resolve()
        resolved.relative_to(directory.resolve())
    except (OSError, ValueError):
        return None
    return resolved


def _find_attempt_directory(evidence_root: Path, attempt_id: str) -> Optional[Path]:
    if (
        not attempt_id
        or attempt_id in {".", ".."}
        or "/" in attempt_id
        or "\\" in attempt_id
    ):
        return None

    direct = [
        evidence_root / "Attempts" / attempt_id,
        evidence_root / "SkillCourses" / "Attempts" / attempt_id,
    ]
    if evidence_root.name.lower() == "attempts":
        direct.append(evidence_root / attempt_id)
    for candidate in direct:
        if candidate.is_dir():
            return candidate

    # Tolerate one extra copied app-data directory, e.g.
    # ``files/SkillCourses/Attempts/<id>`` below the selected evidence root.
    for pattern in ("*/Attempts", "*/SkillCourses/Attempts"):
        try:
            parents = evidence_root.glob(pattern)
        except OSError:
            return None
        for parent in parents:
            candidate = parent / attempt_id
            if candidate.is_dir():
                return candidate
    return None


def _explicit_evidence_error(result: dict[str, Any]) -> Optional[str]:
    """Return a recorded evidence-save error, not an ordinary gameplay reason."""
    for key in ("reason", "storageError"):
        text = result.get(key)
        if not isinstance(text, str) or not text.strip():
            continue
        lowered = text.lower()
        if any(
            token in lowered
            for token in (
                "evidence",
                "replay",
                "could not be saved",
                "could not be written",
                "cannot commit",
                "result storage",
                "result could not be saved",
                "snapshot",
            )
        ):
            shortened = " ".join(text.split())
            return shortened if len(shortened) <= 180 else shortened[:177] + "..."
    return None


# ---------------------------------------------------------------------------
# Database loading
# ---------------------------------------------------------------------------


def load_fixture_attempts(
    database: Path, warnings: list[Finding]
) -> list[dict[str, Any]]:
    """Read only SkillCourseAttempt.resultJson and keep generated fixture rows.

    SQLite needs its ``-wal`` and ``-shm`` companions to see a pulled WAL-mode
    database.  They are copied together into a temporary directory so the
    verifier never creates, checkpoints, or locks files in the evidence set.
    """
    if not database.is_file():
        raise VerificationFailure("Database does not exist: %s" % database)

    with tempfile.TemporaryDirectory(prefix="skillcourse-avd-") as temporary:
        copied_database = Path(temporary) / "database.sqlite"
        try:
            shutil.copy2(database, copied_database)
            for suffix in ("-wal", "-shm"):
                source = Path(str(database) + suffix)
                if source.is_file():
                    shutil.copy2(source, Path(str(copied_database) + suffix))
        except OSError as error:
            raise VerificationFailure("Unable to copy the pulled database: %s" % error)

        connection: Optional[sqlite3.Connection] = None
        try:
            connection = sqlite3.connect(str(copied_database))
            connection.execute("PRAGMA query_only = ON")
            rows = connection.execute(
                "SELECT resultJson FROM SkillCourseAttempt"
            ).fetchall()
        except sqlite3.Error as error:
            raise VerificationFailure(
                "Unable to read only SkillCourseAttempt.resultJson: %s" % error
            )
        finally:
            if connection is not None:
                connection.close()

    attempts: list[dict[str, Any]] = []
    for index, row in enumerate(rows, start=1):
        text = row[0] if row else None
        if not isinstance(text, str):
            warnings.append(
                Finding(
                    "attempt-json",
                    "Skipped SkillCourseAttempt row %d: resultJson is not text." % index,
                )
            )
            continue
        try:
            result = json.loads(text)
        except (TypeError, ValueError):
            warnings.append(
                Finding(
                    "attempt-json",
                    "Skipped SkillCourseAttempt row %d: resultJson is not valid JSON."
                    % index,
                )
            )
            continue
        if not isinstance(result, dict):
            warnings.append(
                Finding(
                    "attempt-json",
                    "Skipped SkillCourseAttempt row %d: resultJson is not an object."
                    % index,
                )
            )
            continue
        if _is_fixture_attempt(result):
            attempts.append(result)

    return attempts


# ---------------------------------------------------------------------------
# Mods / speedrun rates
# ---------------------------------------------------------------------------


def _parse_mods(
    report: AttemptReport, component: dict[str, Any], component_number: int
) -> list[dict[str, Any]]:
    text = component.get("modsJson")
    if not isinstance(text, str):
        report.error(
            "component-mods",
            "Map %d: modsJson is not a string." % component_number,
        )
        return []
    try:
        value = json.loads(text)
    except (TypeError, ValueError):
        report.error(
            "component-mods",
            "Map %d: modsJson is not valid JSON." % component_number,
        )
        return []
    if not isinstance(value, list):
        report.error(
            "component-mods",
            "Map %d: modsJson is not an array." % component_number,
        )
        return []
    mods: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict) or not isinstance(item.get("acronym"), str):
            report.error(
                "component-mods",
                "Map %d: modsJson contains a malformed APIMod." % component_number,
            )
            continue
        mods.append(item)
    return mods


def _mods_rate(
    report: AttemptReport, mods: list[dict[str, Any]], component_number: int
) -> Optional[float]:
    rate = 1.0
    static_defaults = {"DT": 1.5, "NC": 1.5, "HT": 0.75}
    variable_rate = {"WU", "WD"}

    for mod in mods:
        acronym = mod.get("acronym")
        if acronym in variable_rate:
            report.error(
                "component-rate",
                "Map %d: variable-rate mod %s cannot be verified as a frozen "
                "speedrun rate." % (component_number, acronym),
            )
            return None
        default = static_defaults.get(acronym)
        if acronym == "CS":
            default = 1.0
        if default is None:
            continue
        settings = _mapping(mod.get("settings"))
        configured = _float_or_none(settings.get("rateMultiplier"))
        rate *= configured if configured is not None else default
    return rate


def _validated_speeds(
    report: AttemptReport, result: dict[str, Any], map_count: int
) -> dict[int, float]:
    definition = _mapping(result.get("definition"))
    raw = result.get("speeds", {})
    if raw is None:
        raw = {}
    if not isinstance(raw, dict):
        report.error("speeds", "speeds is not a JSON object.")
        return {}

    speeds: dict[int, float] = {}
    for key, value in raw.items():
        try:
            index = int(key)
        except (TypeError, ValueError):
            report.error("speeds", "speeds has a non-integer map key: %r." % (key,))
            continue
        number = _float_or_none(value)
        if index < 0 or index >= map_count:
            report.error(
                "speeds", "speeds references out-of-range original index %d." % index
            )
            continue
        if number is None:
            report.error(
                "speeds", "Speed for original index %d is not numeric." % index
            )
            continue
        if index in speeds:
            report.error(
                "speeds", "Speed for original index %d is duplicated." % index
            )
            continue
        speeds[index] = number

    is_speedrun = definition.get("is_speedrun") is True
    if is_speedrun:
        expected = set(range(map_count))
        if set(speeds) != expected:
            report.error(
                "speeds",
                "Speedrun must freeze exactly one rate per original index "
                "(expected %s, found %s)."
                % (sorted(expected), sorted(speeds)),
            )
        for index, speed in sorted(speeds.items()):
            try:
                decimal = Decimal(str(speed))
            except InvalidOperation:
                report.error("speeds", "Speed for original index %d is invalid." % index)
                continue
            if not decimal.is_finite() or decimal < Decimal("0.50") or decimal > Decimal("3.00"):
                report.error(
                    "speeds",
                    "Speed for original index %d is outside 0.50..3.00." % index,
                )
            elif (decimal - Decimal("0.50")) % Decimal("0.05") != 0:
                report.error(
                    "speeds",
                    "Speed for original index %d is not in 0.05 increments." % index,
                )
    elif speeds:
        report.error("speeds", "A non-speedrun attempt must not freeze per-map speeds.")

    return speeds


def _rates_close(left: float, right: float) -> bool:
    return abs(left - right) <= 1e-4


# ---------------------------------------------------------------------------
# Evidence files
# ---------------------------------------------------------------------------


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _validate_replay(
    report: AttemptReport, path: Path, expected_sha: Any, component_number: int
) -> bool:
    before = len(report.errors)
    if not path.is_file():
        report.error(
            "replay-missing",
            "Map %d: replay file is missing: %s." % (component_number, path.name),
        )
        return False
    try:
        size = path.stat().st_size
    except OSError as error:
        report.error(
            "replay-unreadable",
            "Map %d: replay file cannot be inspected: %s." % (component_number, error),
        )
        return False
    if size <= 0:
        report.error(
            "replay-empty",
            "Map %d: replay file is empty: %s." % (component_number, path.name),
        )
        return False

    try:
        actual_sha = _sha256_file(path)
    except OSError as error:
        report.error(
            "replay-unreadable",
            "Map %d: replay file cannot be read: %s." % (component_number, error),
        )
        return False

    if not isinstance(expected_sha, str) or not expected_sha:
        report.error(
            "replay-sha",
            "Map %d: replaySha256 is missing." % component_number,
        )
    elif actual_sha.lower() != expected_sha.lower():
        report.error(
            "replay-sha",
            "Map %d: replay SHA-256 does not match the recorded digest."
            % component_number,
        )

    try:
        with zipfile.ZipFile(path, "r") as archive:
            names = archive.namelist()
            if "data" not in names:
                report.error(
                    "replay-zip",
                    "Map %d: replay archive has no 'data' member." % component_number,
                )
            bad_member = archive.testzip()
            if bad_member is not None:
                report.error(
                    "replay-crc",
                    "Map %d: replay ZIP CRC failed for %s."
                    % (component_number, bad_member),
                )
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        report.error(
            "replay-zip",
            "Map %d: replay is not a valid ZIP archive: %s."
            % (component_number, error),
        )
        return False

    return not any(
        finding.code in {"replay-sha", "replay-zip", "replay-crc"}
        for finding in report.errors[before:]
    )


def _validate_events(
    report: AttemptReport,
    events: list[dict[str, Any]],
    component: dict[str, Any],
    total_objects: Optional[int],
    ex_settings: dict[str, int],
    component_number: int,
) -> bool:
    valid = True
    final_by_object: dict[int, str] = {}

    for sequence, event in enumerate(events):
        if not isinstance(event, dict):
            report.error(
                "events-shape",
                "Map %d: event %d is not an object." % (component_number, sequence),
            )
            valid = False
            continue
        recorded_sequence = _int_or_none(event.get("sequence"))
        object_id = _int_or_none(event.get("objectId"))
        map_time = _int_or_none(event.get("mapTimeMs"))
        judgment = event.get("judgment")

        if recorded_sequence != sequence:
            report.error(
                "events-sequence",
                "Map %d: event sequence is not continuous at position %d."
                % (component_number, sequence),
            )
            valid = False
        if object_id is None or object_id < 0:
            report.error(
                "events-object",
                "Map %d: event %d has an invalid objectId."
                % (component_number, sequence),
            )
            valid = False
        elif total_objects is not None and object_id >= total_objects:
            report.error(
                "events-object",
                "Map %d: event %d references object %d outside the map manifest "
                "(%d objects)."
                % (component_number, sequence, object_id, total_objects),
            )
            valid = False
        if map_time is None:
            report.error(
                "events-time",
                "Map %d: event %d has a non-integer mapTimeMs."
                % (component_number, sequence),
            )
            valid = False
        if judgment not in ALL_JUDGMENTS:
            report.error(
                "events-judgment",
                "Map %d: event %d has unknown judgment %r."
                % (component_number, sequence, judgment),
            )
            valid = False
            continue

        if judgment in FINAL_JUDGMENTS and object_id is not None and object_id >= 0:
            previous = final_by_object.get(object_id)
            if previous is not None:
                report.error(
                    "events-final-duplicate",
                    "Map %d: object %d has more than one final judgment (%s, %s)."
                    % (component_number, object_id, previous, judgment),
                )
                valid = False
            else:
                final_by_object[object_id] = judgment

    final_counts = Counter(final_by_object.values())
    expected_counts = {
        "HIT300": _int_or_none(component.get("hit300")),
        "HIT100": _int_or_none(component.get("hit100")),
        "HIT50": _int_or_none(component.get("hit50")),
        "HIT0": _int_or_none(component.get("misses")),
    }
    for judgment, expected in expected_counts.items():
        actual = final_counts.get(judgment, 0)
        if expected is None:
            continue
        if actual != expected:
            report.error(
                "events-native-counts",
                "Map %d: %s final-event count %d does not match native count %d."
                % (component_number, judgment, actual, expected),
            )
            valid = False

    completed = component.get("completed") is True
    if completed and total_objects is not None:
        expected_objects = set(range(total_objects))
        if set(final_by_object) != expected_objects:
            missing = sorted(expected_objects - set(final_by_object))
            extra = sorted(set(final_by_object) - expected_objects)
            report.error(
                "events-final-coverage",
                "Map %d: completed map final judgments do not cover objects "
                "exactly (missing %s, extra %s)."
                % (component_number, missing[:8], extra[:8]),
            )
            valid = False

    contribution = _int_or_none(component.get("rawExContribution"))
    if contribution is None:
        report.error(
            "component-ex",
            "Map %d: rawExContribution is not an integer." % component_number,
        )
        valid = False
    elif valid:
        event_ex = sum(
            ex_settings[JUDGMENT_TO_EX_KEY[event["judgment"]]] for event in events
        )
        if contribution != event_ex:
            report.error(
                "component-ex",
                "Map %d: rawExContribution %d is not the signed sum of event "
                "deltas %d." % (component_number, contribution, event_ex),
            )
            valid = False

    return valid


def _verify_component_evidence(
    report: AttemptReport,
    attempt_directory: Optional[Path],
    component: dict[str, Any],
    component_number: int,
    total_objects: Optional[int],
    ex_settings: dict[str, int],
    explicit_error: Optional[str],
    attempt_outcome: str,
) -> tuple[Optional[list[dict[str, Any]]], bool, bool]:
    replay_name = component.get("replayFilename")
    events_name = component.get("eventsFilename")
    replay_present = isinstance(replay_name, str) and bool(replay_name)
    events_present = isinstance(events_name, str) and bool(events_name)

    if not replay_present and not events_present:
        # A completed terminal attempt must carry replay evidence.  An
        # interrupted/failed prefix may legitimately have lost its evidence when
        # the runtime explicitly recorded the save error.
        if explicit_error and attempt_outcome != "completed":
            report.warn(
                "missing-partial-evidence",
                "Map %d: replay and event evidence are absent; explicit evidence "
                "error recorded: %s" % (component_number, explicit_error),
            )
        else:
            report.error(
                "missing-evidence",
                "Map %d: replay and event evidence are absent and no qualifying "
                "evidence-save error was recorded." % component_number,
            )
        return None, False, False

    if replay_present != events_present:
        report.error(
            "partial-evidence",
            "Map %d: only one of replayFilename/eventsFilename was recorded."
            % component_number,
        )

    if attempt_directory is None:
        # The caller already reports a missing attempt directory once.
        return None, False, False

    replay_ok = False
    if replay_present:
        replay_path = _safe_child(attempt_directory, replay_name)
        if replay_path is None:
            report.error(
                "replay-path",
                "Map %d: replayFilename is not a safe child of the attempt "
                "directory." % component_number,
            )
        else:
            replay_ok = _validate_replay(
                report,
                replay_path,
                component.get("replaySha256"),
                component_number,
            )

    events: Optional[list[dict[str, Any]]] = None
    events_ok = False
    if events_present:
        events_path = _safe_child(attempt_directory, events_name)
        if events_path is None:
            report.error(
                "events-path",
                "Map %d: eventsFilename is not a safe child of the attempt "
                "directory." % component_number,
            )
        elif not events_path.is_file():
            report.error(
                "events-missing",
                "Map %d: event evidence file is missing: %s."
                % (component_number, events_path.name),
            )
        else:
            try:
                size = events_path.stat().st_size
            except OSError as error:
                report.error(
                    "events-unreadable",
                    "Map %d: event evidence cannot be inspected: %s."
                    % (component_number, error),
                )
                size = 0
            if size <= 0:
                report.error(
                    "events-empty",
                    "Map %d: event evidence file is empty." % component_number,
                )
            else:
                try:
                    parsed = json.loads(events_path.read_text(encoding="utf-8"))
                except (OSError, UnicodeError, ValueError) as error:
                    report.error(
                        "events-json",
                        "Map %d: event evidence is not valid UTF-8 JSON: %s."
                        % (component_number, error),
                    )
                    parsed = None
                if isinstance(parsed, list):
                    events = [item for item in parsed if isinstance(item, dict)]
                    if len(events) != len(parsed):
                        report.error(
                            "events-shape",
                            "Map %d: event evidence contains non-object entries."
                            % component_number,
                        )
                    events_ok = _validate_events(
                        report,
                        events,
                        component,
                        total_objects,
                        ex_settings,
                        component_number,
                    )
                elif parsed is not None:
                    report.error(
                        "events-shape",
                        "Map %d: event evidence must be a JSON array."
                        % component_number,
                    )

    return events, replay_ok, events_ok


# ---------------------------------------------------------------------------
# HP audit
# ---------------------------------------------------------------------------


def _setting_table(
    definition: dict[str, Any], key: str, defaults: dict[str, int]
) -> dict[str, int]:
    raw = _mapping(definition.get(key))
    result = dict(defaults)
    for name, default in defaults.items():
        value = raw.get(name)
        if _is_int(value):
            result[name] = value
        elif value is not None:
            result[name] = default
    return result


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def _audit_hp(
    report: AttemptReport,
    definition: dict[str, Any],
    components: list[dict[str, Any]],
    audits: list[ComponentAudit],
    allow_missing_evidence: bool,
) -> HpAudit:
    gauge = _setting_table(definition, "gauge_settings", DEFAULT_GAUGE)
    life_gauge = definition.get("life_gauge")
    result = HpAudit()

    if life_gauge != "global":
        for number, component in enumerate(components, start=1):
            if component.get("startingHp") is not None:
                report.error(
                    "hp-non-global",
                    "Map %d: startingHp must be null outside a global gauge."
                    % number,
                )
            if component.get("endingHp") is not None:
                report.error(
                    "hp-non-global",
                    "Map %d: endingHp must be null outside a global gauge."
                    % number,
                )
        return result

    duration = gauge["total_hp"]
    if duration <= 0:
        report.error("hp-settings", "Global gauge total_hp is not positive.")
        return result
    hp = _clamp(gauge["starting_hp"], 0, duration)
    failed = False

    for number, (component, audit) in enumerate(zip(components, audits), start=1):
        if not isinstance(component, dict):
            report.error(
                "hp-component", "Map %d: component data is malformed." % number
            )
            result.starts.append(None)
            result.ends.append(None)
            continue
        start = _int_or_none(component.get("startingHp"))
        end = _int_or_none(component.get("endingHp"))
        result.starts.append(start)
        result.ends.append(end)

        if start is None:
            report.error(
                "hp-start", "Map %d: global startingHp is missing." % number
            )
        elif start != hp:
            report.error(
                "hp-carry",
                "Map %d: startingHp %d does not carry the previous ending HP %d."
                % (number, start, hp),
            )

        if audit.events is None:
            if component.get("completed") is True and not allow_missing_evidence:
                # Missing evidence is already an error; the carry still cannot be
                # recomputed, so keep the review honest.
                report.error(
                    "hp-events",
                    "Map %d: completed global map has no event evidence to "
                    "recompute HP." % number,
                )
            else:
                report.warn(
                    "hp-unverified",
                    "Map %d: HP for this partial component cannot be recomputed "
                    "without event evidence." % number,
                )
            if end is not None:
                hp = _clamp(end, 0, duration)
            continue

        batches: dict[int, list[str]] = {}
        for event in audit.events:
            timestamp = _int_or_none(event.get("mapTimeMs"))
            judgment = event.get("judgment")
            if timestamp is None or judgment not in ALL_JUDGMENTS:
                continue
            batches.setdefault(timestamp, []).append(judgment)

        for timestamp in sorted(batches):
            if failed:
                continue
            delta = 0
            for judgment in batches[timestamp]:
                delta += gauge[JUDGMENT_TO_GAUGE_KEY[judgment]]
            hp = _clamp(hp + delta, 0, duration)
            if result.first_batch_hp is None:
                result.first_batch_hp = hp
            if hp == 0:
                failed = True

        if end is None:
            report.error(
                "hp-end", "Map %d: global endingHp is missing." % number
            )
        elif end != hp:
            report.error(
                "hp-recompute",
                "Map %d: endingHp %d does not match the event-only batch HP %d."
                % (number, end, hp),
            )

        if failed and number < len(components):
            report.error(
                "hp-after-failure",
                "Map %d follows a terminal global-gauge failure." % (number + 1),
            )

    return result


# ---------------------------------------------------------------------------
# Scenario checks
# ---------------------------------------------------------------------------


def _all_miss_scenario(
    components: list[dict[str, Any]],
    definition: dict[str, Any],
) -> tuple[bool, int]:
    maps = _list(definition.get("maps"))
    total_objects = 0
    if not components:
        return False, 0
    for component in components:
        if component.get("completed") is not True:
            return False, 0
        original = _int_or_none(component.get("originalIndex"))
        if original is None or original < 0 or original >= len(maps):
            return False, 0
        total = _manifest_total(_mapping(maps[original]))
        if total is None:
            return False, 0
        if (
            _int_or_none(component.get("hit300")) != 0
            or _int_or_none(component.get("hit100")) != 0
            or _int_or_none(component.get("hit50")) != 0
            or _int_or_none(component.get("misses")) != total
        ):
            return False, 0
        total_objects += total
    return total_objects > 0, total_objects


def _accuracy(counts: Iterable[tuple[int, int, int, int]]) -> float:
    numerator = 0
    denominator = 0
    for hit300, hit100, hit50, misses in counts:
        numerator += 300 * hit300 + 100 * hit100 + 50 * hit50
        denominator += 300 * (hit300 + hit100 + hit50 + misses)
    return 0.0 if denominator == 0 else numerator / denominator


def _counts_for(component: dict[str, Any]) -> Optional[tuple[int, int, int, int]]:
    values = (
        _int_or_none(component.get("hit300")),
        _int_or_none(component.get("hit100")),
        _int_or_none(component.get("hit50")),
        _int_or_none(component.get("misses")),
    )
    if any(value is None or value < 0 for value in values):
        return None
    return values  # type: ignore[return-value]


def _scenario_check(
    report: AttemptReport,
    result: dict[str, Any],
    definition: dict[str, Any],
    components: list[dict[str, Any]],
    audits: list[ComponentAudit],
    hp_audit: HpAudit,
    allow_missing_evidence: bool,
) -> Optional[ScenarioResult]:
    name = _course_name(result)
    if name not in SCENARIO_DESCRIPTIONS:
        return None
    if not all(isinstance(component, dict) for component in components):
        return ScenarioResult(
            name,
            False,
            False,
            "component evidence is malformed; fixture scenario not evaluated",
            {},
        )

    before = len(report.errors)
    outcome = result.get("outcome")
    details: dict[str, Any] = {}

    def not_applicable(reason: str) -> ScenarioResult:
        return ScenarioResult(name, False, False, reason, details)

    def expect(condition: bool, message: str) -> None:
        if not condition:
            report.error("scenario-" + name[:2], message)

    if name in {"01 Offline EX", "02 Global Carry"}:
        if outcome != "completed":
            return not_applicable("fixture scenario requires a completed attempt")
        all_miss, object_total = _all_miss_scenario(components, definition)
        details["allMiss"] = all_miss
        details["objectTotal"] = object_total
        if not all_miss:
            return not_applicable(
                "completed attempt is not the generated all-miss scenario"
            )
        expect(object_total == 7, "expected 7 fixture objects, found %d." % object_total)
        expect(
            definition.get("life_gauge")
            == ("none" if name == "01 Offline EX" else "global"),
            "unexpected life gauge for this fixture scenario.",
        )
        expect(
            _int_or_none(result.get("rawExTotal")) == -7,
            "expected raw EX -7, found %r." % (result.get("rawExTotal"),),
        )
        raw = _int_or_none(result.get("rawExTotal"))
        published = max(0, raw) if raw is not None else None
        expect(published == 0, "expected published EX 0, found %r." % (published,))
        counts = [_counts_for(component) for component in components]
        accuracy = (
            _accuracy(item for item in counts if item is not None)
            if all(item is not None for item in counts)
            else None
        )
        details["publishedEx"] = published
        details["accuracy"] = accuracy
        expect(accuracy == 0.0, "expected 0.0 accuracy, found %r." % (accuracy,))

        if name == "02 Global Carry":
            starts = hp_audit.starts
            ends = hp_audit.ends
            details["hpStarts"] = starts
            details["hpEnds"] = ends
            expect(
                starts == [80, 68] and ends == [68, 57],
                "expected global HP 80 -> 68 -> 57, found starts %r and ends %r."
                % (starts, ends),
            )
            b_component = next(
                (
                    audit
                    for audit in audits
                    if audit.original_index == 1 and audit.events is not None
                ),
                None,
            )
            if b_component is None:
                report.error(
                    "scenario-02",
                    "Map B event evidence is unavailable for the slider-break "
                    "expectation.",
                )
            else:
                b_events = b_component.events or []
                slider_breaks = sum(
                    1 for event in b_events if event.get("judgment") == "SLIDERBREAK"
                )
                details["sliderBreaksInB"] = slider_breaks
                expect(
                    slider_breaks == 2,
                    "expected 2 slider breaks in map B, found %d." % slider_breaks,
                )

    elif name == "03 Global Fail":
        if outcome != "failed":
            return not_applicable("fixture scenario requires a failed attempt")
        details["hpStarts"] = hp_audit.starts
        details["hpEnds"] = hp_audit.ends
        details["firstBatchHp"] = hp_audit.first_batch_hp
        expect(
            definition.get("life_gauge") == "global",
            "expected the global life gauge.",
        )
        if hp_audit.first_batch_hp is None and allow_missing_evidence:
            report.warn(
                "scenario-03",
                "Expected first-batch HP 0 could not be verified because the "
                "component evidence is missing; the explicit evidence-save error "
                "was recorded.",
            )
            return ScenarioResult(
                name,
                True,
                False,
                "event evidence is unavailable; expected first-batch HP was not "
                "verified",
                details,
            )
        expect(
            len(components) == 1,
            "expected one played map before the global failure, found %d."
            % len(components),
        )
        if components:
            first = components[0]
            expect(
                _int_or_none(first.get("originalIndex")) == 0,
                "expected the failure on original map 1 (index 0).",
            )
            expect(
                first.get("completed") is False,
                "the failed first map must be marked incomplete.",
            )
            expect(
                _int_or_none(first.get("startingHp")) == 3,
                "expected starting HP 3, found %r." % (first.get("startingHp"),),
            )
            expect(
                _int_or_none(first.get("endingHp")) == 0,
                "expected ending HP 0, found %r." % (first.get("endingHp"),),
            )
        expect(
            hp_audit.first_batch_hp == 0,
            "expected the first HP event batch to end at 0, found %r."
            % (hp_audit.first_batch_hp,),
        )

    elif name == "04 Standard Fail":
        if outcome != "failed":
            return not_applicable("fixture scenario requires a failed attempt")
        if not components:
            report.error("scenario-04", "failed attempt has no component evidence.")
        elif components[0].get("completed") is True:
            return not_applicable(
                "first map completed before the standard-gauge failure"
            )
        details["lifeGauge"] = definition.get("life_gauge")
        details["scoring"] = definition.get("scoring")
        expect(
            definition.get("life_gauge") == "standard",
            "expected the standard life gauge.",
        )
        expect(
            len(components) == 1,
            "expected a one-map failed prefix, found %d components."
            % len(components),
        )
        if components:
            first = components[0]
            expect(
                _int_or_none(first.get("originalIndex")) == 0,
                "expected the failure on original map 1 (index 0).",
            )
            expect(
                first.get("completed") is False,
                "the failed first map must be marked incomplete.",
            )
            expect(
                first.get("startingHp") is None and first.get("endingHp") is None,
                "standard-gauge components must not record global HP values.",
            )

    elif name == "06 Speedrun Random":
        if outcome != "completed":
            return not_applicable("fixture scenario requires a completed attempt")
        details["randomOrder"] = definition.get("random_order")
        details["isSpeedrun"] = definition.get("is_speedrun")
        expect(
            definition.get("random_order") is True,
            "expected random_order to be true.",
        )
        expect(
            definition.get("is_speedrun") is True,
            "expected is_speedrun to be true.",
        )
        order = result.get("order")
        maps = _list(definition.get("maps"))
        order_valid = (
            isinstance(order, list)
            and len(order) == len(maps)
            and all(_is_int(value) and 0 <= value < len(maps) for value in order)
            and set(order) == set(range(len(maps)))
        )
        expect(order_valid, "expected a full order permutation.")
        raw_speeds = result.get("speeds", {})
        speeds: dict[int, float] = {}
        if isinstance(raw_speeds, dict):
            for key, value in raw_speeds.items():
                try:
                    index = int(key)
                except (TypeError, ValueError):
                    continue
                number = _float_or_none(value)
                if number is not None:
                    speeds[index] = number
        details["speeds"] = {str(key): speeds[key] for key in sorted(speeds)}
        for audit in audits:
            if audit.original_index is None or audit.rate is None:
                report.error(
                    "scenario-06",
                    "Map %d has no verifiable frozen rate."
                    % (audit.index + 1),
                )
                continue
            frozen = speeds.get(audit.original_index)
            if frozen is None:
                report.error(
                    "scenario-06",
                    "Map %d has no frozen rate for original index %d."
                    % (audit.index + 1, audit.original_index),
                )
            elif not _rates_close(audit.rate, frozen):
                report.error(
                    "scenario-06",
                    "Map %d effective rate %.2fx does not match the frozen rate "
                    "%.2fx for original index %d (played index %d)."
                    % (
                        audit.index + 1,
                        audit.rate,
                        frozen,
                        audit.original_index,
                        audit.index,
                    ),
                )

    elif name in {
        "10 Autoplay scorev1",
        "11 Autoplay scorev2",
        "12 Autoplay accuracy",
        "13 Autoplay exscore",
    }:
        if outcome != "completed":
            return not_applicable("fixture scenario requires a completed attempt")
        maps = _list(definition.get("maps"))
        details["maps"] = len(components)
        expect(
            len(components) == len(maps) and len(components) > 0,
            "expected every fixture map to be played.",
        )
        expect(
            all(component.get("completed") is True for component in components),
            "expected every map to be completed.",
        )
        for number, (component, audit) in enumerate(
            zip(components, audits), start=1
        ):
            original = _int_or_none(component.get("originalIndex"))
            total = (
                _manifest_total(_mapping(maps[original]))
                if original is not None and 0 <= original < len(maps)
                else None
            )
            if total is None:
                report.error(
                    "scenario-autoplay",
                    "Map %d has no valid object manifest." % number,
                )
                continue
            expect(
                _int_or_none(component.get("hit300")) == total
                and _int_or_none(component.get("hit100")) == 0
                and _int_or_none(component.get("hit50")) == 0
                and _int_or_none(component.get("misses")) == 0,
                "Map %d authored-AT run must judge every object as HIT300."
                % number,
            )
            expect(
                component.get("score") is not None
                and _int_or_none(component.get("score")) is not None
                and _int_or_none(component.get("score")) > 0,
                "Map %d native score must be positive." % number,
            )
            expect(
                "AT" in audit.mod_acronyms,
                "Map %d effective mods must include the authored AT mod." % number,
            )
        counts = [_counts_for(component) for component in components]
        accuracy = (
            _accuracy(item for item in counts if item is not None)
            if all(item is not None for item in counts)
            else None
        )
        details["accuracy"] = accuracy
        expect(accuracy == 1.0, "expected 100%% accuracy, found %r." % (accuracy,))
        raw = _int_or_none(result.get("rawExTotal"))
        published = max(0, raw) if raw is not None else None
        details["rawExTotal"] = raw
        details["publishedEx"] = published
        expect(raw == 14, "expected raw EX 14, found %r." % (raw,))
        expect(published == 14, "expected published EX 14, found %r." % (published,))

    verified = not report.errors[before:]
    reason = (
        "expected fixture scenario verified"
        if verified
        else "expected fixture scenario has errors"
    )
    return ScenarioResult(name, True, verified, reason, details)


# ---------------------------------------------------------------------------
# Attempt verification
# ---------------------------------------------------------------------------


def verify_attempt(
    result: dict[str, Any], evidence_root: Path, ordinal: int = 1
) -> AttemptReport:
    definition = _mapping(result.get("definition"))
    report = AttemptReport(
        attempt_id=_attempt_id(result, "attempt-%d" % ordinal),
        course_name=_course_name(result),
        outcome=result.get("outcome")
        if isinstance(result.get("outcome"), str)
        else "<unknown>",
    )

    if not definition:
        report.error("definition", "Course definition is missing or not an object.")
        return report
    if definition.get("author") != FIXTURE_AUTHOR and definition.get(
        "course_name"
    ) not in FIXTURE_COURSES:
        report.warn(
            "not-fixture",
            "Attempt is not a generated fixture attempt.",
        )

    maps = _list(definition.get("maps"))
    map_count = len(maps)
    if not maps:
        report.error("manifest", "Course manifest has no maps.")
    for index, course_map in enumerate(maps, start=1):
        if not isinstance(course_map, dict):
            report.error(
                "manifest", "Map %d manifest entry is not an object." % index
            )
            continue
        counts = course_map.get("object_counts")
        if (
            not isinstance(counts, list)
            or len(counts) != 3
            or not all(_is_int(value) and value >= 0 for value in counts)
        ):
            report.error(
                "manifest",
                "Map %d object_counts must be three nonnegative integers." % index,
            )
        map_hash = course_map.get("map_hash")
        if not isinstance(map_hash, str) or len(map_hash) != 32:
            report.error(
                "manifest", "Map %d map_hash is not a 32-character MD5." % index
            )

    order = result.get("order")
    order_values: list[int] = []
    order_valid = False
    if not isinstance(order, list):
        report.error("order", "order is not an array.")
    elif not all(_is_int(value) for value in order):
        report.error("order", "order contains a non-integer entry.")
    else:
        order_values = list(order)
        if len(order_values) != map_count:
            report.error(
                "order",
                "order length %d does not match the %d-map manifest."
                % (len(order_values), map_count),
            )
        elif set(order_values) != set(range(map_count)):
            report.error(
                "order", "order is not a permutation of every original index."
            )
        else:
            order_valid = True

    components = _list(result.get("components"))
    if not isinstance(result.get("components"), list):
        report.error("components", "components is not an array.")
    if len(components) > map_count:
        report.error(
            "components",
            "Attempt records %d components for a %d-map course."
            % (len(components), map_count),
        )

    outcome = report.outcome
    if outcome not in KNOWN_OUTCOMES:
        report.error("outcome", "Unknown attempt outcome %r." % outcome)
    elif outcome == "in_progress":
        report.warn(
            "in-progress",
            "Attempt is still in_progress; it is not terminal acceptance evidence.",
        )

    ex_settings = _setting_table(definition, "exscore_settings", DEFAULT_EX)
    explicit_error = _explicit_evidence_error(result)
    attempt_directory = _find_attempt_directory(evidence_root, report.attempt_id)
    if attempt_directory is None:
        if components:
            report.error(
                "evidence-directory",
                "Attempt evidence directory Attempts/%s is missing."
                % report.attempt_id,
            )
        else:
            report.warn(
                "evidence-directory",
                "Attempt has no components and no evidence directory.",
            )

    speeds = _validated_speeds(report, result, map_count)
    allow_missing_evidence = bool(explicit_error) and outcome != "completed"

    audits: list[ComponentAudit] = []
    active_values: list[int] = []
    contribution_values: list[int] = []
    all_active_valid = True
    all_contribution_valid = True

    for component_index, component in enumerate(components):
        number = component_index + 1
        if not isinstance(component, dict):
            report.error(
                "component", "Component %d is not an object." % number
            )
            audit = ComponentAudit(index=component_index)
            audits.append(audit)
            all_active_valid = False
            all_contribution_valid = False
            continue

        played = _int_or_none(component.get("playedIndex"))
        original = _int_or_none(component.get("originalIndex"))
        audit = ComponentAudit(
            index=component_index,
            played_index=played,
            original_index=original,
            completed=component.get("completed")
            if isinstance(component.get("completed"), bool)
            else None,
            starting_hp=_int_or_none(component.get("startingHp")),
            ending_hp=_int_or_none(component.get("endingHp")),
        )

        if played is None:
            report.error(
                "component-index",
                "Map %d: playedIndex is not an integer." % number,
            )
        elif played != component_index:
            report.error(
                "component-prefix",
                "Map %d: playedIndex %d is not the expected sequential index %d."
                % (number, played, component_index),
            )
        if played is not None and not (0 <= played < map_count):
            report.error(
                "component-index",
                "Map %d: playedIndex %d is outside the course." % (number, played),
            )

        course_map: Optional[dict[str, Any]] = None
        total_objects: Optional[int] = None
        if original is None:
            report.error(
                "component-index",
                "Map %d: originalIndex is not an integer." % number,
            )
        elif not (0 <= original < map_count):
            report.error(
                "component-index",
                "Map %d: originalIndex %d is outside the course."
                % (number, original),
            )
        else:
            course_map = _mapping(maps[original])
            total_objects = _manifest_total(course_map)
            if order_valid and order_values[component_index] != original:
                report.error(
                    "component-order",
                    "Map %d: originalIndex %d does not match frozen order[%d] = %d."
                    % (number, original, component_index, order_values[component_index]),
                )
            expected_hash = course_map.get("map_hash")
            actual_hash = component.get("mapHash")
            if not isinstance(actual_hash, str) or not isinstance(expected_hash, str):
                report.error(
                    "component-map",
                    "Map %d: mapHash is missing." % number,
                )
            elif actual_hash.lower() != expected_hash.lower():
                report.error(
                    "component-map",
                    "Map %d: mapHash does not match the frozen manifest." % number,
                )

        counts: list[Optional[int]] = [
            _int_or_none(component.get("hit300")),
            _int_or_none(component.get("hit100")),
            _int_or_none(component.get("hit50")),
            _int_or_none(component.get("misses")),
        ]
        if any(value is None for value in counts):
            report.error(
                "native-counts",
                "Map %d: native judgment counts must be integers." % number,
            )
        elif any(value < 0 for value in counts):
            report.error(
                "native-counts",
                "Map %d: native judgment counts must not be negative." % number,
            )
        elif total_objects is not None:
            judged = sum(value for value in counts if value is not None)
            if component.get("completed") is True:
                if judged != total_objects:
                    report.error(
                        "native-completed-counts",
                        "Map %d: completed map has %d judgments for %d manifest "
                        "objects." % (number, judged, total_objects),
                    )
            elif judged > total_objects:
                report.error(
                    "native-counts",
                    "Map %d: incomplete map has %d judgments for only %d "
                    "manifest objects." % (number, judged, total_objects),
                )

        for key in (
            "score",
            "maxCombo",
            "sliderHeadHits",
            "sliderTickHits",
            "sliderRepeatHits",
            "sliderEndHits",
        ):
            value = _int_or_none(component.get(key))
            if value is None:
                report.error(
                    "native-counts",
                    "Map %d: %s must be an integer." % (number, key),
                )
            elif value < 0:
                report.error(
                    "native-counts",
                    "Map %d: %s must not be negative." % (number, key),
                )

        active = _int_or_none(component.get("activeNanos"))
        if active is None:
            report.error(
                "active-time",
                "Map %d: activeNanos must be an integer." % number,
            )
            all_active_valid = False
        elif active < 0:
            report.error(
                "active-time",
                "Map %d: activeNanos must not be negative." % number,
            )
            all_active_valid = False
        else:
            active_values.append(active)

        contribution = _int_or_none(component.get("rawExContribution"))
        if contribution is None:
            report.error(
                "component-ex",
                "Map %d: rawExContribution must be an integer." % number,
            )
            all_contribution_valid = False
        else:
            contribution_values.append(contribution)

        mods = _parse_mods(report, component, number)
        audit.mod_acronyms = [
            mod["acronym"] for mod in mods if isinstance(mod.get("acronym"), str)
        ]
        audit.rate = _mods_rate(report, mods, number)
        if definition.get("is_speedrun") is True and original is not None:
            frozen = speeds.get(original)
            if frozen is not None and audit.rate is not None:
                if not _rates_close(audit.rate, frozen):
                    report.error(
                        "speedrun-rate",
                        "Map %d: effective %.2fx does not match the frozen rate "
                        "%.2fx for original index %d, even if the played index is "
                        "%d." % (number, audit.rate, frozen, original, component_index),
                    )

        events, replay_ok, events_ok = _verify_component_evidence(
            report,
            attempt_directory,
            component,
            number,
            total_objects,
            ex_settings,
            explicit_error,
            outcome,
        )
        audit.events = events
        audit.replay_ok = replay_ok
        audit.events_ok = events_ok
        audits.append(audit)

    if outcome == "completed":
        if len(components) != map_count:
            report.error(
                "completed-prefix",
                "Completed attempt has %d components for %d maps."
                % (len(components), map_count),
            )
        for number, component in enumerate(components, start=1):
            if not isinstance(component, dict):
                continue
            if component.get("completed") is not True:
                report.error(
                    "completed-component",
                    "Map %d is incomplete in a completed attempt." % number,
                )
    else:
        if len(components) > map_count:
            report.error(
                "component-prefix",
                "Attempt has more components than manifest maps.",
            )
        for number, component in enumerate(components, start=1):
            if not isinstance(component, dict):
                continue
            if component.get("completed") is False and number != len(components):
                report.error(
                    "component-prefix",
                    "Map %d is incomplete but is followed by another component."
                    % number,
                )

    active_total = _int_or_none(result.get("activeNanos"))
    if active_total is None:
        report.error("active-time", "Result activeNanos must be an integer.")
    elif active_total < 0:
        report.error("active-time", "Result activeNanos must not be negative.")
    elif all_active_valid:
        expected_active = sum(active_values)
        if active_total != expected_active:
            report.error(
                "active-time",
                "Result activeNanos %d does not equal the sum of component "
                "activeNanos %d." % (active_total, expected_active),
            )

    raw_total = _int_or_none(result.get("rawExTotal"))
    published_ex: Optional[int] = None
    if raw_total is None:
        report.error("raw-ex", "rawExTotal must be an integer.")
    else:
        published_ex = max(0, raw_total)
        if all_contribution_valid:
            expected_raw = sum(contribution_values)
            if raw_total != expected_raw:
                report.error(
                    "raw-ex",
                    "rawExTotal %d does not equal the signed sum of component "
                    "contributions %d." % (raw_total, expected_raw),
                )

    hp_audit = _audit_hp(
        report,
        definition,
        components,
        audits,
        allow_missing_evidence,
    )

    component_counts: list[tuple[int, int, int, int]] = []
    for component in components:
        if not isinstance(component, dict):
            continue
        counts = _counts_for(component)
        if counts is not None:
            component_counts.append(counts)
    accuracy = _accuracy(component_counts)

    native_score = 0
    native_score_valid = True
    for component in components:
        if not isinstance(component, dict):
            continue
        score = _int_or_none(component.get("score"))
        if score is None:
            native_score_valid = False
            continue
        native_score += score

    report.components = audits
    report.metrics = {
        "mapsPlayed": len(components),
        "mapsTotal": map_count,
        "scoring": definition.get("scoring"),
        "rawExTotal": raw_total,
        "publishedEx": published_ex,
        "nativeScore": native_score if native_score_valid else None,
        "accuracy": accuracy,
        "activeNanos": active_total,
        "activeSeconds": None
        if active_total is None
        else active_total / 1_000_000_000.0,
        "rates": {str(key): speeds[key] for key in sorted(speeds)},
        "hpStarts": hp_audit.starts,
        "hpEnds": hp_audit.ends,
        "firstBatchHp": hp_audit.first_batch_hp,
    }

    report.scenario = _scenario_check(
        report,
        result,
        definition,
        components,
        audits,
        hp_audit,
        allow_missing_evidence,
    )
    return report


# ---------------------------------------------------------------------------
# Requirements
# ---------------------------------------------------------------------------


def evaluate_requirements(
    required_courses: Iterable[str], attempts: list[AttemptReport]
) -> list[RequirementResult]:
    results: list[RequirementResult] = []
    seen: set[str] = set()
    for course_name in required_courses:
        if course_name in seen:
            continue
        seen.add(course_name)
        matching = [attempt for attempt in attempts if attempt.course_name == course_name]
        if not matching:
            results.append(
                RequirementResult(
                    course_name,
                    False,
                    "no generated-fixture attempt for this exact course name",
                )
            )
            continue

        terminal = [attempt for attempt in matching if attempt.outcome in TERMINAL_OUTCOMES]
        if not terminal:
            results.append(
                RequirementResult(
                    course_name,
                    False,
                    "only non-terminal outcomes found: %s"
                    % ", ".join(sorted({attempt.outcome for attempt in matching})),
                )
            )
            continue

        expected_outcome = EXPECTED_OUTCOMES.get(course_name)
        if expected_outcome is not None:
            expected = [
                attempt for attempt in matching if attempt.outcome == expected_outcome
            ]
            if not expected:
                results.append(
                    RequirementResult(
                        course_name,
                        False,
                        "expected terminal outcome %r, found %s"
                        % (
                            expected_outcome,
                            ", ".join(sorted({attempt.outcome for attempt in matching})),
                        ),
                    )
                )
                continue
        else:
            expected = terminal

        if course_name in SCENARIO_DESCRIPTIONS:
            verified = [
                attempt
                for attempt in expected
                if attempt.scenario is not None
                and attempt.scenario.applicable
                and attempt.scenario.verified
            ]
            if not verified:
                reasons = "; ".join(
                    sorted(
                        {
                            attempt.scenario.reason
                            for attempt in expected
                            if attempt.scenario is not None
                        }
                    )
                )
                results.append(
                    RequirementResult(
                        course_name,
                        False,
                        "expected fixture scenario not observed (%s)"
                        % (reasons or "scenario was not checked"),
                    )
                )
                continue

        results.append(
            RequirementResult(
                course_name,
                True,
                "expected terminal result observed"
                if course_name not in SCENARIO_DESCRIPTIONS
                else "expected terminal result and fixture scenario verified",
            )
        )
    return results


# ---------------------------------------------------------------------------
# CLI presentation
# ---------------------------------------------------------------------------


def _format_seconds(nanos: Any) -> str:
    if not _is_int(nanos):
        return "?"
    return "%.3fs" % (nanos / 1_000_000_000.0)


def _format_metric(report: AttemptReport) -> str:
    metrics = report.metrics
    scoring = metrics.get("scoring")
    if scoring == "exscore":
        return "EX=%r(raw=%r)" % (
            metrics.get("publishedEx"),
            metrics.get("rawExTotal"),
        )
    if scoring == "accuracy":
        accuracy = metrics.get("accuracy")
        return "Accuracy=%.2f%%" % (100.0 * accuracy) if accuracy is not None else "Accuracy=?"
    native = metrics.get("nativeScore")
    return "Score=%s" % ("?" if native is None else native)


def _format_rates(report: AttemptReport) -> str:
    rates = report.metrics.get("rates")
    if not isinstance(rates, dict) or not rates:
        return "-"
    return ",".join(
        "#%s:%sx" % (key, ("%.2f" % value) if value is not None else "?")
        for key, value in rates.items()
    )


def _format_hp(report: AttemptReport) -> str:
    starts = report.metrics.get("hpStarts")
    ends = report.metrics.get("hpEnds")
    if not isinstance(starts, list) or not starts or not isinstance(ends, list):
        return "-"
    parts: list[str] = []
    for index, (start, end) in enumerate(zip(starts, ends)):
        if index == 0:
            parts.append(str(start))
        parts.append(str(end))
    return "->".join(parts)


def print_report(report: RunReport) -> None:
    print("Skill Course AVD evidence verification")
    print("  database: %s" % report.database)
    print("  evidence: %s" % report.evidence)
    for warning in report.warnings:
        print("  warning [%s] %s" % (warning.code, warning.message))
    for error in report.errors:
        print("  error   [%s] %s" % (error.code, error.message))
    print("")

    for attempt in report.attempts:
        status = "PASS" if attempt.ok else "FAIL"
        scenario = ""
        if attempt.scenario is not None:
            if attempt.scenario.applicable:
                scenario = " scenario=%s" % (
                    "verified" if attempt.scenario.verified else "unverified"
                )
            else:
                scenario = " scenario=not-observed"
        print(
            "%s %s | attempt %s | outcome=%s | maps=%s/%s | %s | "
            "accuracy=%.2f%% | active=%s | rates=%s | HP=%s%s"
            % (
                status,
                attempt.course_name,
                attempt.attempt_id,
                attempt.outcome,
                attempt.metrics.get("mapsPlayed", "?"),
                attempt.metrics.get("mapsTotal", "?"),
                _format_metric(attempt),
                100.0 * attempt.metrics.get("accuracy", 0.0),
                _format_seconds(attempt.metrics.get("activeNanos")),
                _format_rates(attempt),
                _format_hp(attempt),
                scenario,
            )
        )
        for warning in attempt.warnings:
            print("  warning [%s] %s" % (warning.code, warning.message))
        for error in attempt.errors:
            print("  error   [%s] %s" % (error.code, error.message))

    if report.requirements:
        print("")
        for requirement in report.requirements:
            print(
                "%s require %s: %s"
                % (
                    "PASS" if requirement.satisfied else "FAIL",
                    requirement.course_name,
                    requirement.reason,
                )
            )

    total_errors = len(report.errors) + sum(
        len(attempt.errors) for attempt in report.attempts
    )
    total_warnings = len(report.warnings) + sum(
        len(attempt.warnings) for attempt in report.attempts
    )
    print("")
    print(
        "Summary: %d fixture attempt(s), %d error(s), %d warning(s), %d/%d requirement(s) satisfied"
        % (
            len(report.attempts),
            total_errors,
            total_warnings,
            sum(1 for item in report.requirements if item.satisfied),
            len(report.requirements),
        )
    )


def write_json_report(report: RunReport, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(report.as_dict(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Verify pulled offline Skill Course AVD evidence. Reads only "
            "SkillCourseAttempt.resultJson and Attempts/<id>/ evidence files."
        )
    )
    parser.add_argument(
        "--database",
        required=True,
        type=Path,
        help="pulled Room database (its matching -wal/-shm are copied too)",
    )
    parser.add_argument(
        "--evidence",
        required=True,
        type=Path,
        help="pulled directory containing Attempts/<clientAttemptId>/",
    )
    parser.add_argument(
        "--require",
        action="append",
        default=[],
        metavar="COURSE_NAME",
        help="exact fixture course name that must have a completed/expected terminal result",
    )
    parser.add_argument(
        "--json-output",
        type=Path,
        help="optional path for a machine-readable summary",
    )
    return parser


def run(args: argparse.Namespace) -> RunReport:
    report = RunReport(
        database=str(args.database),
        evidence=str(args.evidence),
    )
    if not args.evidence.is_dir():
        raise VerificationFailure("Evidence directory does not exist: %s" % args.evidence)

    attempts = load_fixture_attempts(args.database, report.warnings)
    if not attempts:
        report.errors.append(
            Finding(
                "no-fixture-attempts",
                "No SkillCourseAttempt.resultJson row matched the generated "
                "fixture author or course names.",
            )
        )
    for ordinal, result in enumerate(attempts, start=1):
        report.attempts.append(verify_attempt(result, args.evidence, ordinal))

    report.requirements = evaluate_requirements(args.require, report.attempts)
    return report


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    report: RunReport
    try:
        report = run(args)
    except VerificationFailure as error:
        report = RunReport(
            database=str(args.database),
            evidence=str(args.evidence),
            errors=[Finding("fatal", str(error))],
        )

    print_report(report)
    if args.json_output is not None:
        try:
            write_json_report(report, args.json_output)
        except OSError as error:
            print("Unable to write --json-output: %s" % error, file=sys.stderr)
            report.errors.append(Finding("json-output", str(error)))
    return 0 if report.ok else 1


if __name__ == "__main__":
    sys.exit(main())
