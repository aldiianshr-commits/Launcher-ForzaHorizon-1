#!/usr/bin/env python3
"""End-to-end test of the module pipeline with a synthetic backend.

Builds tiny AArch64 shared libraries that stand in for Pinyon Shift's
(libc++_shared, librexruntime with an SDL revision string, librexgpu-fh1,
a facade, libmain exporting SDL_main), packs them as a Pinyon Shift APK
would hold them, converts that APK with tools/fhorizon_module.py, then runs
fhinspect on the extracted libraries and checks the load order the launcher
would use. No game code or data is involved.

    python3 tools/tests/test_module_pipeline.py <fhinspect binary>
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools" / "fhorizon_module.py"

LIBS = {
    # name: (source, needed, extra exported symbol)
    "libc++_shared.so": ("int cxx_marker(void){return 1;}", ["libc.so"]),
    "librexruntime.so": ('const char* rex_sdl_revision(void){return "SDL-3.5.0-test-runtime";}\n'
                         "int cxx_marker(void); int rex_runtime(void){return cxx_marker();}",
                         ["libc++_shared.so", "liblog.so", "libvulkan.so"]),
    "librexgpu-fh1.so": ("unsigned rex_gpu_abi_version(void){return 2;}\nvoid* rex_gpu_create(void){return 0;}",
                         ["librexruntime.so"]),
    "libpinyon_shift_SpeechFacade_default.so": ("int speech(void){return 3;}", ["librexruntime.so"]),
    "libmain.so": ("int rex_runtime(void); int speech(void);\n"
                   "int SDL_main(int c, char** v){(void)c;(void)v;return rex_runtime()+speech();}",
                   ["librexruntime.so", "libpinyon_shift_SpeechFacade_default.so", "libc++_shared.so"]),
    "libhook_impl.so": ("int hook(void){return 0;}", ["libc.so"]),
}
SYSTEM_STUBS = ["libc.so", "liblog.so", "libvulkan.so"]


def build(work: Path) -> Path:
    out = work / "lib"
    out.mkdir()
    stubs = work / "stubs"
    stubs.mkdir()
    common = ["clang", "--target=aarch64-linux-android33", "-fPIC", "-shared", "-nostdlib",
              "-fuse-ld=lld", "-Wl,-z,max-page-size=16384"]
    for stub in SYSTEM_STUBS:
        src = stubs / (stub + ".c")
        src.write_text("int stub_symbol_%s(void){return 0;}\n" % stub.replace(".", "_").replace("+", "p"))
        subprocess.run(common + [f"-Wl,-soname,{stub}", "-o", str(stubs / stub), str(src)], check=True)
    for name, (source, needed) in LIBS.items():
        src = work / (name + ".c")
        src.write_text(source + "\n")
        inputs = []
        for dep in needed:
            inputs.append(str((stubs if dep in SYSTEM_STUBS else out) / dep))
        subprocess.run(common + [f"-Wl,-soname,{name}", "-o", str(out / name), str(src)] + inputs, check=True)
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    fhinspect = Path(sys.argv[1]).resolve()
    if shutil.which("clang") is None or shutil.which("ld.lld") is None:
        print("skip: clang and ld.lld are needed")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        libs = build(work)
        apk = work / "pinyon-shift.apk"
        with zipfile.ZipFile(apk, "w") as zf:
            zf.writestr("classes.dex", b"dex\n")
            zf.writestr("assets/pinyon_shift_build.json", json.dumps({
                "platform": "android-arm64", "version": "0.6.0-test",
                "pinyon_shift_commit": "9331e453e9850d0fcc1c1606dee95db834a9d317",
                "rexglue_commit": "79d3e6fae4b79e00def48a595c80f0eaa47fd2b6"}))
            for lib in libs.iterdir():
                zf.write(lib, f"lib/arm64-v8a/{lib.name}")
            zf.writestr("assets/drivers/turnip-test/meta.json", "{}")
        result = subprocess.run([sys.executable, "-I", str(TOOL), "from-apk", str(apk), "-o", str(work / "out")],
                                check=True, capture_output=True, text=True)
        info = json.loads(result.stdout)
        assert info["sdl_version"] == "3.5.0", info
        module = Path(info["module"])
        subprocess.run([sys.executable, "-I", str(TOOL), "inspect", str(module)], check=True)
        extracted = work / "extracted"
        with zipfile.ZipFile(module) as zf:
            names = zf.namelist()
            assert "meta/pinyon_shift_build.json" in names, names
            assert "drivers/turnip-test/meta.json" in names, names
            for name in names:
                if name.startswith("lib/"):
                    target = extracted / name.split("/", 1)[1]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(zf.read(name))
        report = json.loads(subprocess.run([str(fhinspect), "module", str(extracted)], check=True,
                                           capture_output=True, text=True).stdout)
        plan = report["plan"]
        assert plan["ok"], plan
        order = plan["load_order"]
        assert order[-1] == "libmain.so", order
        assert order.index("libc++_shared.so") < order.index("librexruntime.so") < order.index("libmain.so"), order
        assert order.index("libpinyon_shift_SpeechFacade_default.so") < order.index("libmain.so"), order
        assert "librexgpu-fh1.so" in plan["side_libraries"], plan
        assert "libhook_impl.so" in plan["side_libraries"], plan
        runtime = next(lib for lib in report["libraries"] if lib["file"] == "librexruntime.so")
        assert runtime["sdl_version"] == "3.5.0", runtime
        gpu = next(lib for lib in report["libraries"] if lib["file"] == "librexgpu-fh1.so")
        assert "rex_gpu_abi_version" in gpu["exports"], gpu
        print("module pipeline ok:", " -> ".join(order))
    return 0


if __name__ == "__main__":
    sys.exit(main())
