#!/usr/bin/env python3
"""Set versionName from a release tag and increment versionCode.

Kept out of the workflow YAML so it can be run and checked locally, and so a
mistake shows up as a failed assertion here rather than as a released APK
whose version does not match its tag.

Usage: bump_version.py <tag> [--dry-run]
"""

import re
import sys
from pathlib import Path

GRADLE = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"


def bump(tag: str, dry_run: bool = False) -> tuple[str, int]:
    # Tags are conventionally v-prefixed; versionName is not.
    name = tag[1:] if tag.startswith(("v", "V")) else tag
    if not name:
        raise SystemExit(f"refusing to derive a versionName from tag {tag!r}")

    source = GRADLE.read_text(encoding="utf-8")

    code_match = re.search(r"versionCode\s*=\s*(\d+)", source)
    name_match = re.search(r'versionName\s*=\s*"([^"]*)"', source)
    if not code_match or not name_match:
        raise SystemExit(f"could not find versionCode/versionName in {GRADLE}")

    code = int(code_match.group(1)) + 1

    updated = source[:code_match.start()] + f"versionCode = {code}" + source[code_match.end():]
    name_match = re.search(r'versionName\s*=\s*"([^"]*)"', updated)
    updated = updated[:name_match.start()] + f'versionName = "{name}"' + updated[name_match.end():]

    if not dry_run:
        GRADLE.write_text(updated, encoding="utf-8", newline="\n")
    return name, code


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__)
        raise SystemExit(2)
    version_name, version_code = bump(args[0], dry_run="--dry-run" in sys.argv)
    print(f"versionName={version_name}")
    print(f"versionCode={version_code}")
