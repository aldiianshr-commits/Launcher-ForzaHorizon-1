#!/usr/bin/env python3
"""Packs an FHorizon Mobile backend module from a Pinyon Shift Android build.

The backend module is the recompiled Forza Horizon code (ReXGlue/ShiftGlue
static recompilation of the player's own default.xex, PowerPC -> C++ ->
AArch64) plus its runtime, as Android shared libraries. It is built on the
player's PC by Pinyon Shift's own tools and never published. This script
only repackages the result so the FHorizon Mobile launcher can import it:

    fhorizon-module-<version>-<date>.zip
      module.json                      provenance, file list, SHA-256s
      lib/<name>.so                    libmain.so, librexruntime.so, ...
      meta/pinyon_shift_build.json     Pinyon Shift's build manifest
      drivers/<name>/...               GPU drivers the build bundled, if any

Usage:
    fhorizon_module.py from-apk <pinyon-shift.apk> [-o OUT_DIR]
    fhorizon_module.py from-build <pinyon-shift checkout> [--preset android-arm64-release] [-o OUT_DIR]
    fhorizon_module.py inspect <module.zip>

Nothing here reads or copies game data; the libraries are taken as they are.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 1
# What the launcher needs to run the game: Pinyon Shift's NATIVE_LIBRARIES
# and ADRENOTOOLS_HOOKS (tools/pinyon_android.py) and the C++ runtime.
REQUIRED = ["libc++_shared.so", "librexruntime.so", "librexgpu-fh1.so", "libmain.so"]
OPTIONAL_PREFIXES = ("libpinyon_shift_", "libhook_impl", "libmain_hook", "libfile_redirect_hook",
                     "libgsl_alloc_hook", "libamd_fidelityfx_vk")
SDL_REVISION = re.compile(rb"SDL-(\d+\.\d+\.\d+)-")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def elf_machine(data: bytes) -> str:
    if data[:4] != b"\x7fELF":
        return "not-elf"
    if data[4] != 2:
        return "elf32"
    machine = int.from_bytes(data[18:20], "little")
    return {183: "aarch64", 62: "x86_64"}.get(machine, f"machine-{machine}")


def sdl_version(data: bytes) -> str | None:
    match = SDL_REVISION.search(data)
    return match.group(1).decode() if match else None


def write_module(libraries: dict[str, bytes], build_manifest: dict | None, drivers: dict[str, bytes],
                 out_dir: Path, source: str, title_update_v4: bool = False) -> Path:
    missing = [name for name in REQUIRED if name not in libraries]
    if missing:
        raise SystemExit(f"error: the build lacks {', '.join(missing)}")
    wrong = {name: elf_machine(data) for name, data in libraries.items() if elf_machine(data) != "aarch64"}
    if wrong:
        raise SystemExit(f"error: not AArch64 libraries: {wrong}")
    versions = {v for v in (sdl_version(data) for data in libraries.values()) if v}
    files = [{"name": name, "size": len(data), "sha256": sha256_bytes(data)}
             for name, data in sorted(libraries.items())]
    build = build_manifest or {}
    module = {
        "schema_version": SCHEMA_VERSION,
        "kind": "fhorizon-module",
        "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": source,
        "entry": "libmain.so",
        "abi": "arm64-v8a",
        "sdl_version": sorted(versions)[0] if len(versions) == 1 else None,
        "pinyon_commit": build.get("pinyon_shift_commit"),
        "rexglue_commit": build.get("rexglue_commit"),
        "build_version": build.get("version"),
        "title_update_v4": title_update_v4,
        "libraries": files,
        "notice": "Built on the player's PC from their own disc. Do not share: it contains "
                  "code derived from the game's executable.",
    }
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    version = str(build.get("version", "local")).replace("/", "-")
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"fhorizon-module-{version}{'-v4' if title_update_v4 else ''}-{stamp}.zip"
    # Libraries are stored uncompressed-friendly with deflate; the launcher
    # streams them out once at import.
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        zf.writestr("module.json", json.dumps(module, indent=2) + "\n")
        if build_manifest is not None:
            zf.writestr("meta/pinyon_shift_build.json", json.dumps(build_manifest, indent=2) + "\n")
        for name, data in sorted(libraries.items()):
            zf.writestr(f"lib/{name}", data)
        for name, data in sorted(drivers.items()):
            zf.writestr(f"drivers/{name}", data)
    print(json.dumps({"module": str(out), "bytes": out.stat().st_size, "sdl_version": module["sdl_version"],
                      "libraries": [f["name"] for f in files]}, indent=2))
    if len(versions) > 1:
        print(f"warning: several SDL versions found: {sorted(versions)}", file=sys.stderr)
    return out


def from_apk(apk: Path, out_dir: Path, title_update_v4: bool) -> Path:
    libraries: dict[str, bytes] = {}
    drivers: dict[str, bytes] = {}
    build = None
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            name = info.filename
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                libraries[name.rsplit("/", 1)[1]] = zf.read(info)
            elif name == "assets/pinyon_shift_build.json":
                build = json.loads(zf.read(info).decode("utf-8"))
            elif name.startswith("assets/drivers/") and not info.is_dir():
                drivers[name[len("assets/drivers/"):]] = zf.read(info)
    if not libraries:
        raise SystemExit(f"error: {apk} holds no arm64-v8a libraries")
    return write_module(libraries, build, drivers, out_dir, f"apk:{apk.name}", title_update_v4)


def from_build(checkout: Path, preset: str, out_dir: Path, title_update_v4: bool) -> Path:
    # Pinyon Shift's packaged APK holds the stripped libraries, libc++ and the
    # build manifest; use it when the build produced one.
    apk = checkout / ".local" / "android" / "pinyon-shift.apk"
    if apk.is_file():
        print(f"note: packing from {apk} (stripped libraries and build manifest)")
        return from_apk(apk, out_dir, title_update_v4)
    build_dir = checkout / "out" / "build" / preset
    if not build_dir.is_dir():
        raise SystemExit(f"error: {build_dir} does not exist; run 'python tools/pinyon.py android build' first")
    newest: dict[str, Path] = {}
    for path in build_dir.rglob("*.so"):
        if "CMakeFiles" in path.parts:
            continue
        if path.name in REQUIRED or path.name.startswith(OPTIONAL_PREFIXES):
            if path.name not in newest or path.stat().st_mtime > newest[path.name].stat().st_mtime:
                newest[path.name] = path
    libraries = {name: path.read_bytes() for name, path in newest.items()}
    if "libc++_shared.so" not in libraries:
        import os
        ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
        if ndk:
            for candidate in Path(ndk).glob("toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"):
                libraries["libc++_shared.so"] = candidate.read_bytes()
                break
    return write_module(libraries, None, {}, out_dir, f"build:{preset}", title_update_v4)


def inspect(module: Path) -> int:
    with zipfile.ZipFile(module) as zf:
        meta = json.loads(zf.read("module.json"))
        print(json.dumps({k: v for k, v in meta.items() if k != "libraries"}, indent=2))
        for entry in meta["libraries"]:
            data = zf.read(f"lib/{entry['name']}")
            ok = sha256_bytes(data) == entry["sha256"]
            print(f"  {entry['name']:45s} {entry['size']:>12d}  {'ok' if ok else 'SHA MISMATCH'}")
            if not ok:
                return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    a = sub.add_parser("from-apk")
    a.add_argument("apk", type=Path)
    a.add_argument("-o", "--out", type=Path, default=Path("module-out"))
    a.add_argument("--title-update-v4", action="store_true")
    b = sub.add_parser("from-build")
    b.add_argument("checkout", type=Path)
    b.add_argument("--preset", default="android-arm64-release")
    b.add_argument("-o", "--out", type=Path, default=Path("module-out"))
    b.add_argument("--title-update-v4", action="store_true")
    i = sub.add_parser("inspect")
    i.add_argument("module", type=Path)
    args = parser.parse_args()
    if args.command == "from-apk":
        from_apk(args.apk, args.out, args.title_update_v4)
    elif args.command == "from-build":
        from_build(args.checkout, args.preset, args.out, args.title_update_v4)
    else:
        return inspect(args.module)
    return 0


if __name__ == "__main__":
    sys.exit(main())
