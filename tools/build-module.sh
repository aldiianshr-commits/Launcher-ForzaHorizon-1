#!/usr/bin/env bash
# Builds the FHorizon Mobile backend module on a Linux x86-64 PC from the
# player's own Forza Horizon disc (USA retail, MS-2505).
#
#   tools/build-module.sh --iso ~/forza-horizon.iso
#   tools/build-module.sh --extracted ~/fh1-extracted      # folder with default.xex
#
# Steps, all done by Pinyon Shift's own tools at a pinned commit:
#   1. clone Pinyon Shift (and its ShiftGlue SDK submodule);
#   2. verify the disc image against the supported dump and extract it;
#   3. run ReXGlue/ShiftGlue codegen: PowerPC from default.xex -> C++;
#   4. cross-compile the generated code and the runtime for Android arm64
#      with the pinned NDK (downloaded after you accept the SDK license);
#   5. repack the result as fhorizon-module-*.zip for the launcher.
#
# Needs about 45 GB free and 30-90 minutes. Nothing is uploaded anywhere.
set -euo pipefail

PINYON_REPO="${PINYON_REPO:-https://github.com/arcanite24/pinyon-shift}"
PINYON_COMMIT="${PINYON_COMMIT:-9331e453e9850d0fcc1c1606dee95db834a9d317}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${FHORIZON_WORK:-$HERE/.work}"
SOURCE_ARGS=()
ACCEPT_LICENSES=""
JOBS=""

usage() {
  sed -n '2,20p' "$0"
  echo
  echo "options: --iso FILE | --extracted DIR, [--accept-licenses] [--jobs N] [--work DIR]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --iso) SOURCE_ARGS=(--iso "$(realpath "$2")"); shift 2 ;;
    --extracted) SOURCE_ARGS=(--extracted "$(realpath "$2")"); shift 2 ;;
    --accept-licenses) ACCEPT_LICENSES="--accept-licenses"; shift ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --work) WORK="$(realpath -m "$2")"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option $1"; usage; exit 2 ;;
  esac
done
[[ ${#SOURCE_ARGS[@]} -eq 2 ]] || { usage; exit 2; }

for tool in git python3 cmake; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required"; exit 1; }
done

mkdir -p "$WORK"
PINYON="$WORK/pinyon-shift"
if [[ ! -d "$PINYON/.git" ]]; then
  echo "== cloning Pinyon Shift"
  git clone --filter=blob:none "$PINYON_REPO" "$PINYON"
fi
echo "== checking out Pinyon Shift $PINYON_COMMIT"
git -C "$PINYON" fetch --depth 1 origin "$PINYON_COMMIT" 2>/dev/null || git -C "$PINYON" fetch origin
git -C "$PINYON" checkout --quiet "$PINYON_COMMIT"
git -C "$PINYON" submodule update --init --recursive --depth 1

cd "$PINYON"
echo "== verifying, extracting and recompiling the game (pinyon.py setup)"
python3 tools/pinyon.py setup "${SOURCE_ARGS[@]}" ${JOBS:+--jobs "$JOBS"}

echo "== Android toolchain (pinyon.py android doctor)"
if ! python3 tools/pinyon.py android doctor; then
  python3 tools/pinyon.py android doctor --install $ACCEPT_LICENSES
fi

echo "== cross-compiling for Android arm64 (pinyon.py android build)"
python3 tools/pinyon.py android build ${JOBS:+--jobs "$JOBS"}

echo "== packing the FHorizon module"
python3 -I "$HERE/tools/fhorizon_module.py" from-apk "$PINYON/.local/android/pinyon-shift.apk" -o "$HERE/module-out"

GAME_ROOT="$PINYON/.local/game/base"
cat <<EOF

Done.
  Module:     $HERE/module-out/ (copy the newest fhorizon-module-*.zip to
              /storage/emulated/0/FHorizon/Module/ on the tablet)
  Game files: $GAME_ROOT (copy this folder to /storage/emulated/0/FHorizon/Game/)
Then open FHorizon Mobile > Backend > "Impor dari FHorizon/Module".
Keep both private: they come from your own disc.
EOF
