"""Generate original, redistributable beatmaps/courses for the offline AVD acceptance run.

Usage: python tests/fixtures/skillcourse/generate_avd_fixtures.py OUTPUT_DIRECTORY
No game installation, credentials, network service, or external media are needed.
"""

import hashlib
import copy
import io
import json
import math
from pathlib import Path
import struct
import sys
import wave
import zipfile


def audio():
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b"".join(struct.pack("<h", int(200 * math.sin(i * 2 * math.pi * 220 / 16000))) for i in range(7 * 16000)))
    return output.getvalue()


def beatmap(difficulty, objects, approach_rate=8):
    return f"""osu file format v14

[General]
AudioFilename: audio.wav
AudioLeadIn: 0
PreviewTime: 1000
Countdown: 0
SampleSet: Normal
Mode: 0

[Metadata]
Title:Skill Course Fixture
TitleUnicode:Skill Course Fixture
Artist:Codex Fixture
ArtistUnicode:Codex Fixture
Creator:Codex Fixture
Version:{difficulty}
Source:
Tags:skillcourse offline fixture
BeatmapID:0
BeatmapSetID:0

[Difficulty]
HPDrainRate:8
CircleSize:4
OverallDifficulty:5
ApproachRate:{approach_rate}
SliderMultiplier:1.4
SliderTickRate:1

[Events]

[TimingPoints]
0,500,4,2,0,100,1,0

[HitObjects]
{objects}
""".encode("utf-8")


def entry(difficulty, data, counts):
    return {
        "map_hash": hashlib.md5(data).hexdigest(),
        "object_counts": counts,
        "artist": "Codex Fixture", "artist_unicode": "Codex Fixture",
        "title": "Skill Course Fixture", "title_unicode": "Skill Course Fixture",
        "mapper": "Codex Fixture", "difficulty": difficulty,
        "beatmapid": None, "mods": [],
    }


def course(name, maps, **settings):
    result = {
        "format": "osu-droid-skill-course", "course_name": name,
        "course_version": 1, "author": "Codex Fixture",
        "life_gauge": "none",
        "gauge_settings": {
            "total_hp": 100, "starting_hp": 80,
            "hit300": 1, "hit100": 0, "hit50": -1, "hit0": -3,
            "sliderbreak": -1, "sliderend": 0,
        },
        "scoring": "exscore",
        "exscore_settings": {
            "hit300": 2, "hit100": 1, "hit50": 0,
            "hit0": -1, "sliderbreak": 0, "sliderend": 0,
        },
        "allow_pause": True, "map_transition_ms": 0,
        "auto_skip": True, "is_speedrun": False,
        "random_order": False, "allowed_mods": [], "maps": maps,
    }
    result.update(settings)
    return result


def main(output):
    output.mkdir(parents=True, exist_ok=True)
    circles = "\n".join(f"{x},{y},{time},1,0,0:0:0:0:" for x, y, time in [
        (128, 128, 1500), (384, 128, 2500), (384, 256, 3500), (128, 256, 4500),
    ])
    a = beatmap("A Circles", circles)
    a_new = beatmap("A Circles", circles, approach_rate=9)
    b = beatmap("B Slider Spinner", "128,128,1500,1,0,0:0:0:0:\n128,192,2500,2,0,L|408:192,2,140\n256,192,4500,8,0,5500,0:0:0:0:")
    sibling = beatmap("Sibling Not In Course", "256,192,1000,1,0,0:0:0:0:\n256,192,3000,1,0,0:0:0:0:")
    dense = beatmap("D Standard Failure", "\n".join(f"256,192,{1000 + i * 300},1,0,0:0:0:0:" for i in range(12)))
    for archive_name, maps in [
        ("Skill Course Fixture.osz", {"A.osu": a, "B.osu": b, "Sibling.osu": sibling, "D.osu": dense}),
        ("Skill Course Fixture Update.osz", {"A Renamed.osu": a_new}),
        ("Skill Course Fixture Wrong Version.osz", {"A.osu": a}),
    ]:
        with zipfile.ZipFile(output / archive_name, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("audio.wav", audio())
            for filename, data in maps.items():
                archive.writestr(filename, data)

    ma, mb, md = entry("A Circles", a, [4, 0, 0]), entry("B Slider Spinner", b, [1, 1, 1]), entry("D Standard Failure", dense, [12, 0, 0])
    missing = entry("Uninstalled", b"this file is deliberately absent", [1, 0, 0])
    fixtures = {
        "01 Offline EX": course("01 Offline EX", [ma, mb]),
        "02 Global Carry": course("02 Global Carry", [ma, mb], life_gauge="global"),
        "03 Global Fail": course("03 Global Fail", [ma, mb], life_gauge="global"),
        "04 Standard Fail": course("04 Standard Fail", [md, ma], life_gauge="standard", scoring="scorev1"),
        "05 Outdated And Missing": course("05 Outdated And Missing", [entry("A Circles", a_new, [4, 0, 0]), missing]),
        "06 Speedrun Random": course("06 Speedrun Random", [ma, mb], is_speedrun=True, random_order=True, map_transition_ms=500),
        "07 No Pause": course("07 No Pause", [ma, mb], allow_pause=False),
        "08 Accuracy": course("08 Accuracy", [ma, mb], scoring="accuracy"),
        "09 ScoreV2": course("09 ScoreV2", [ma, mb], scoring="scorev2"),
    }
    fixtures["03 Global Fail"]["gauge_settings"]["starting_hp"] = 3
    auto_maps = copy.deepcopy([ma, mb])
    for item in auto_maps:
        item['mods'] = [{'acronym': 'AT'}]
    for number, scoring in [(10, 'scorev1'), (11, 'scorev2'), (12, 'accuracy'), (13, 'exscore')]:
        name = f'{number} Autoplay {scoring}'
        fixtures[name] = course(name, auto_maps, scoring=scoring)
    perfect = copy.deepcopy(ma)
    perfect['mods'] = [{'acronym': 'PF'}]
    fixtures['14 Perfect Fail'] = course('14 Perfect Fail', [perfect, mb], life_gauge='standard')
    fixtures['15 Lifecycle'] = course('15 Lifecycle', [ma, mb], map_transition_ms=5000)
    long_entries = []
    with zipfile.ZipFile(output / 'Skill Course Fixture Long.osz', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('audio.wav', audio())
        for index in range(20):
            difficulty = f'Long {index + 1:02}'
            data = beatmap(difficulty, circles)
            archive.writestr(f'Long {index + 1:02}.osu', data)
            item = entry(difficulty, data, [4, 0, 0])
            item['mods'] = [{'acronym': 'AT'}]
            long_entries.append(item)
    fixtures['16 Long Autoplay'] = course('16 Long Autoplay', long_entries, scoring='exscore')
    # Deliberately invalid: a no-fail gauge rejects authored fail-on-miss mods.
    fixtures['17 No Fail Perfect'] = course('17 No Fail Perfect', [perfect, mb])
    fixtures['18 Global Perfect'] = course('18 Global Perfect', [perfect, mb], life_gauge='global')
    preference_maps = copy.deepcopy(auto_maps)
    preference_maps[0]['mods'].append({'acronym': 'HR'})
    for name in ['00A Preferences A', '00B Preferences B']:
        fixtures[name] = course(name, preference_maps, is_speedrun=True, allowed_mods=['HD', 'HR', 'EZ', 'DA'])
    authored_rates = copy.deepcopy(auto_maps)
    authored_rates[0]['mods'].append({'acronym': 'NC'})
    authored_rates[1]['mods'].append({'acronym': 'HT'})
    fixtures['00C Authored Rate Preferences'] = course('00C Authored Rate Preferences', authored_rates, is_speedrun=True, allowed_mods=['HD'])
    for name, definition in fixtures.items():
        (output / f"{name}.odc").write_text(json.dumps(definition, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "expected.json").write_text(json.dumps({
        "map_a_md5": ma["map_hash"], "map_a_updated_md5": hashlib.md5(a_new).hexdigest(),
        "map_b_md5": mb["map_hash"],
        "all_miss_offline_ex": {"raw_ex_total": -7, "published_ex": 0, "accuracy": 0},
        "all_miss_global": {"after_map_a": 68, "after_map_b": 57, "slider_breaks_in_b": 2, "note": "B has 3 final misses, one missed slider head, one missed repeat, and a tail delta of zero."},
        "global_fail": {"first_event_hp": 0, "played_maps": 1, "outcome": "failed"},
        "recovery_before_update": {"updated_a": "OUTDATED", "uninstalled": "MISSING"},
        "copyright": "Original generated fixture maps and audio; may be redistributed with the project.",
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(fixtures)} courses (17 intentionally invalid) and 4 original map archives in {output.resolve()}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
