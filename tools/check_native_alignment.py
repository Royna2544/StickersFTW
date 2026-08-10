#!/usr/bin/env python3
"""Fail if any bundled native library is not 16KB-page aligned.

Android 15+ devices may use 16KB memory pages, and a .so whose LOAD segments
are aligned to 4KB simply will not load there -- the app dies the moment it
touches that library. Alignment is fixed at link time, so a dependency that
ships prebuilt .so files can reintroduce this without a single line of our
code changing, and nothing else in the build would notice.

Usage: check_native_alignment.py <apk> [...]
"""

import struct
import sys
import zipfile

REQUIRED_ALIGN = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes) -> list[int]:
    if elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = elf[4] == 2
    endian = "<" if elf[5] == 1 else ">"
    if is64:
        ph_off = struct.unpack_from(endian + "Q", elf, 0x20)[0]
        ph_size = struct.unpack_from(endian + "H", elf, 0x36)[0]
        ph_count = struct.unpack_from(endian + "H", elf, 0x38)[0]
        align_at = 0x30
    else:
        ph_off = struct.unpack_from(endian + "I", elf, 0x1C)[0]
        ph_size = struct.unpack_from(endian + "H", elf, 0x2A)[0]
        ph_count = struct.unpack_from(endian + "H", elf, 0x2C)[0]
        align_at = 0x1C

    alignments = []
    for i in range(ph_count):
        entry = ph_off + i * ph_size
        if struct.unpack_from(endian + "I", elf, entry)[0] != PT_LOAD:
            continue
        fmt = endian + ("Q" if is64 else "I")
        alignments.append(struct.unpack_from(fmt, elf, entry + align_at)[0])
    return alignments


def check(path: str) -> int:
    failures = 0
    with zipfile.ZipFile(path) as archive:
        libs = sorted(n for n in archive.namelist() if n.endswith(".so"))
        if not libs:
            print(f"{path}: no native libraries")
            return 0
        for name in libs:
            alignments = load_alignments(archive.read(name))
            ok = bool(alignments) and all(a >= REQUIRED_ALIGN for a in alignments)
            if not ok:
                failures += 1
                print(f"  FAIL {name} p_align={sorted(set(alignments))}")
        print(f"{path}: {len(libs)} native libraries, {failures} not 16KB-aligned")
    return failures


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    total = sum(check(p) for p in sys.argv[1:])
    if total:
        print(f"\n{total} library(ies) would fail to load on a 16KB-page device.")
    sys.exit(1 if total else 0)
