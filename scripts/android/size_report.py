#!/usr/bin/env python3
"""What the two native libraries are made of, and what the stripping options would save. Changes nothing.

    python scripts/android/size_report.py --ndk "$ANDROID_HOME/ndk/29.0.14206865" [--out build/android]

Run it after a build. It reads OUT's libraries (the game module and libmain.so, as built and as packaged) and
prints Markdown for the CI run's summary: per section sizes (llvm-size -A, with llvm-readelf -S -W's table),
the dynamic relocations, and what `llvm-strip --strip-unneeded` would save against the `--strip-debug` the
package uses today, tried on copies in a temporary directory. It also checks with llvm-nm -D that every symbol
the host looks up in the module with dlsym, and every symbol the module takes from libmain.so, is still exported
after the stripping. The libraries and the APK are not touched, and the report holds sizes and names only: no game data.
"""
import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MODULE = "libgGZLE01_recomp.so"


def sections(size_tool, lib):
    """[(section, bytes)] from llvm-size -A (the loaded and unloaded sections alike), largest first."""
    out = subprocess.run([size_tool, "-A", str(lib)], capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1].isdigit() and parts[0] != "Total":
            rows.append((parts[0], int(parts[1])))
    return sorted(rows, key=lambda row: -row[1])


def dynamic_symbols(nm, lib, which):
    out = subprocess.run([nm, "-D", f"--{which}-only", str(lib)], capture_output=True, text=True).stdout
    return {line.split()[-1] for line in out.splitlines() if line.split()}


def relocations(readelf, lib):
    out = subprocess.run([readelf, "-r", "-W", str(lib)], capture_output=True, text=True).stdout
    return Counter(re.findall(r"\bR_AARCH64_\w+", out))


def dlsym_names():
    """The names the host looks up in the module (a string literal in a dlsym call under runtime/ and android/)."""
    names = set()
    for tree in ("runtime", "android"):
        for path in (ROOT / tree).rglob("*"):
            if path.suffix in (".c", ".cpp", ".h", ".hpp") and path.is_file():
                names.update(re.findall(r'dlsym\(\s*\w+\s*,\s*"(\w+)"', path.read_text(encoding="utf-8", errors="replace")))
    return sorted(names)


def kib(n):
    return f"{n:,} B ({n / 1048576:.2f} MiB)"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--ndk", type=Path, required=True, help="the NDK directory (the one holding toolchains/)")
    parser.add_argument("--out", type=Path, default=ROOT / "build/android")
    args = parser.parse_args()
    bins = sorted((args.ndk / "toolchains/llvm/prebuilt").glob("*/bin"))
    if not bins:
        print(f"no NDK toolchain under {args.ndk}")
        return
    readelf, size, strip, nm = (str(bins[0] / name) for name in ("llvm-readelf", "llvm-size", "llvm-strip", "llvm-nm"))
    libs = [  # (name, as built, as packaged)
        (MODULE, args.out / "composite/gGZLE01_recomp.so", args.out / "apk/lib" / MODULE),
        ("libmain.so", args.out / "app/libmain.so", args.out / "apk/lib/libmain.so")]
    if not all(built.exists() and shipped.exists() for _, built, shipped in libs):
        print("The libraries are not both there (the build stopped before packaging): nothing to measure.")
        return
    apk = args.out / "WindWakerRecomp.apk"
    print("## Native library sizes, arm64-v8a\n")
    if apk.exists():
        print(f"APK: {kib(apk.stat().st_size)}\n")
    print("| library | built (with debug info) | packaged (`--strip-debug`) | `--strip-unneeded` | saves |")
    print("|---|---:|---:|---:|---:|")
    tmp = Path(tempfile.mkdtemp(prefix="size-report-"))
    detail = []
    for name, built, shipped in libs:
        debug_copy, unneeded = tmp / f"{name}.strip-debug", tmp / f"{name}.strip-unneeded"
        subprocess.run([strip, "--strip-debug", "-o", str(debug_copy), str(built)], check=True)
        subprocess.run([strip, "--strip-unneeded", "-o", str(unneeded), str(built)], check=True)
        now, then, alt = shipped.stat().st_size, debug_copy.stat().st_size, unneeded.stat().st_size
        note = "" if now == then else f" (the packaged file differs from a fresh `--strip-debug`: {now:,} B)"
        print(f"| {name} | {built.stat().st_size:,} | {now:,}{note} | {alt:,} | {now - alt:,} ({(now - alt) / 1048576:.2f} MiB) |")
        detail.append((name, shipped, unneeded))
    for name, shipped, unneeded in detail:
        total = shipped.stat().st_size
        print(f"\n### {name}: sections of the packaged file (llvm-size -A)\n")
        print("| section | bytes | share of the file |\n|---|---:|---:|")
        for section, n in sections(size, shipped)[:18]:
            print(f"| `{section}` | {n:,} | {100 * n / total:.1f}% |")
        reloc = relocations(readelf, shipped)
        print("\nDynamic relocations: " + (", ".join(f"{kind} {n:,}" for kind, n in reloc.most_common()) or "none"))
        table = subprocess.run([readelf, "-S", "-W", str(shipped)], capture_output=True, text=True).stdout
        print(f"\n<details><summary>llvm-readelf -S -W {name}</summary>\n\n```\n{table.rstrip()}\n```\n</details>")
    print("\n### Exports after `--strip-unneeded` (llvm-nm -D)\n")
    module_shipped, module_unneeded = next((s, u) for n, s, u in detail if n == MODULE)
    main_shipped, main_unneeded = next((s, u) for n, s, u in detail if n == "libmain.so")
    looked_up = dlsym_names()
    exported = [n for n in looked_up if n in dynamic_symbols(nm, module_shipped, "defined")]
    lost = [n for n in exported if n not in dynamic_symbols(nm, module_unneeded, "defined")]
    needed = dynamic_symbols(nm, module_shipped, "undefined") & dynamic_symbols(nm, main_shipped, "defined")
    needed_lost = sorted(needed - dynamic_symbols(nm, main_unneeded, "defined"))
    same = {name: dynamic_symbols(nm, s, w) == dynamic_symbols(nm, u, w)
            for name, s, u in detail for w in ("defined", "undefined")}
    print(f"- dlsym names found in the sources under runtime/ and android/: {len(looked_up)}; exported by the packaged module: "
          f"{len(exported)} (the rest are optional, or looked up elsewhere); lost by `--strip-unneeded`: {lost or 'none'}")
    print(f"- symbols the module takes from libmain.so: {len(needed)}; lost by `--strip-unneeded`: {needed_lost or 'none'}")
    print(f"- dynamic symbol tables identical after `--strip-unneeded`: {'yes' if all(same.values()) else 'NO ' + str(same)}")


if __name__ == "__main__":
    sys.exit(main())
