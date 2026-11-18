#!/usr/bin/env python3
"""The key of the CI cache that holds the compiled game module (libgGZLE01_recomp.so and its module.json).

    python scripts/android/module_cache_key.py --ndk 29.0.14206865 >> "$GITHUB_OUTPUT"

Prints `key=bluewake-android-module-v1-<hash>`; the inputs it hashed go to stderr (the CI log).

The key covers what shapes the module and nothing else, so a change to the app (the Java, the manifest,
android_entry.c, libmain.so's sources, packaging) or to a RecompCore patch that only reaches the app (patch 0206)
leaves the cached module valid. What it covers:
  - the pins in scripts/builder/profiles/bluewake.sh: the RecompCore URL and commit, the DolRecomp commit and the
    composite digest (not the profile's comments, which change with every release note);
  - the module's own sources and the steps that prepare them: cmake/composite, mods, scripts/mods,
    config/stage_select, scripts/windows (the translator driver, preparation and mod steps this builder reuses),
    scripts/generate_composite.py and scripts/ios/composite_manifest.py;
  - scripts/android/build.py with the app-only functions (APP_ONLY below) left out. The list names what is
    NOT the module's: a function nobody listed counts as the module's, so a new one rebuilds the module
    (2.5 hours) instead of reusing a stale one;
  - a listed patches/recompcore patch that touches what the module compiles from RecompCore (GXRuntime/src/core,
    GXRuntime/include, the StaticRecomp ABI, DolRecomp), by its content;
  - the NDK version, which compiles it, and KEY_VERSION below.
The disc is not in the key: only a GZLE01 rev 0 disc passes the builder's checks, and every such disc gives the
composite that the profile's COMPOSITE_DIGEST names.
"""
import argparse
import ast
import hashlib
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
KEY_VERSION = "1"  # bump when this script's rules change, to start a new cache
PROFILE = ROOT / "scripts/builder/profiles/bluewake.sh"
PINS = ("RECOMPCORE_URL", "RECOMPCORE_SHA", "DOLRECOMP_SHA", "COMPOSITE_DIGEST")
TREES = ("cmake/composite", "mods", "scripts/mods", "config/stage_select", "scripts/windows")
FILES = ("scripts/generate_composite.py", "scripts/ios/composite_manifest.py")
# scripts/android/build.py: what makes the app, the APK or the on-device training, or only prepares the pinned source:
# functions and methods by name, and the module-level constants only they read.
APP_ONLY = {"configure_app", "build_app", "app_profile", "sdl_java_dir", "package", "apk_provenance", "DEVICE_FILES",
            "adb", "train_on_device", "app_training_fingerprint", "train_app_on_device", "device_training_run",
            "runtime_patches", "patch_runtime", "explain_patch_failure", "dependencies", "memory_jobs",
            "ACTIVITY", "DEFAULT_LABEL", "TARGET_SDK", "RUNTIME_PATCH_LIST", "PACKAGE_STRIP"}
# What the module compiles from the pinned RecompCore (cmake/composite/CMakeLists.txt): a patch touching these is an input.
MODULE_SOURCES = ("GXRuntime/src/core/", "GXRuntime/include/", "Source/Core/Core/PowerPC/StaticRecomp/", "DolRecomp/")
PATCH_LIST = ROOT / "patches/recompcore/build-applies.txt"


def names(node):
    """What a definition or an assignment is called."""
    if isinstance(node, ast.Assign):
        return {target.id for target in node.targets if isinstance(target, ast.Name)}
    return {getattr(node, "name", None)}


def build_py_parts():
    """The source of scripts/android/build.py's module-level code and AndroidBuilder methods, minus APP_ONLY."""
    text = (ROOT / "scripts/android/build.py").read_text(encoding="utf-8")
    parts = []
    for node in ast.parse(text).body:
        if names(node) & APP_ONLY:
            continue
        if isinstance(node, ast.ClassDef):
            parts.append(f"class {node.name}")
            parts += [ast.get_source_segment(text, item) for item in node.body if not names(item) & APP_ONLY]
        else:
            parts.append(ast.get_source_segment(text, node))
    return "\n".join(parts).encode("utf-8")


def module_patches():
    """The listed RecompCore patches that touch what the module compiles (their content is an input)."""
    if not PATCH_LIST.is_file():
        return []
    names = [line.split("#")[0].strip() for line in PATCH_LIST.read_text(encoding="utf-8").splitlines()]
    found = []
    for name in filter(None, names):
        patch = PATCH_LIST.parent / name
        text = patch.read_text(encoding="utf-8", errors="replace") if patch.is_file() else ""
        paths = re.findall(r"^diff --git a/(\S+) b/", text, re.M)
        if not patch.is_file() or any(p.startswith(MODULE_SOURCES) for p in paths):
            found.append((f"patch {name}", patch.read_bytes() if patch.is_file() else b"(missing)"))
    return found


def inputs(ndk):
    items = [("key version", KEY_VERSION.encode()), ("ndk", ndk.encode())]
    profile = PROFILE.read_text(encoding="utf-8")
    for name in PINS:
        match = re.search(rf"^{name}=(\S+)$", profile, re.M)
        items.append((f"pin {name}", match.group(1).encode() if match else b"(missing)"))
    for tree in TREES:
        for path in sorted(p for p in (ROOT / tree).rglob("*")
                           if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"):
            items.append((path.relative_to(ROOT).as_posix(), path.read_bytes()))
    for name in FILES:
        items.append((name, (ROOT / name).read_bytes()))
    items.append(("scripts/android/build.py (module part)", build_py_parts()))
    items += module_patches()
    return items


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--ndk", required=True, help="the NDK version the module is compiled with")
    args = parser.parse_args()
    digest = hashlib.sha256()
    count = 0
    for label, data in inputs(args.ndk):
        part = hashlib.sha256(data).hexdigest()
        digest.update(f"{label}\0{part}\n".encode("utf-8"))
        count += 1
        print(f"  {part[:12]}  {label}", file=sys.stderr)
    print(f"{count} inputs", file=sys.stderr)
    print(f"key=bluewake-android-module-v{KEY_VERSION}-{digest.hexdigest()[:20]}")


if __name__ == "__main__":
    main()
