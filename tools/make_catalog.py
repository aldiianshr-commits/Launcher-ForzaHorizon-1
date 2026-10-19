#!/usr/bin/env python3
"""Builds app/src/main/assets/catalog/fh1-catalog.json from Pinyon Shift's
dump manifests (BSD-3-Clause; see THIRD_PARTY_NOTICES.md).

The catalog lists file names, sizes and SHA-256 digests of the supported
retail disc's extracted files - metadata only, no game data. The launcher
uses it to check the player's own extracted folder before starting the game,
exactly as Pinyon Shift's PC launcher does.

    python3 tools/make_catalog.py <pinyon-shift checkout>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "catalog" / "fh1-catalog.json"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    pinyon = Path(sys.argv[1])
    config = pinyon / "config"
    dumps = json.loads((config / "supported-dumps.json").read_text(encoding="utf-8"))
    updates = json.loads((config / "supported-title-updates.json").read_text(encoding="utf-8"))
    catalog = {
        "schema_version": 1,
        "source": "Pinyon Shift config/supported-dumps.json, supported-extracted-*.json, "
                  "supported-title-updates.json",
        "dumps": [],
        "title_updates": [],
    }
    for dump in dumps["dumps"]:
        extraction = dump.get("extraction", {})
        files_name = extraction.get("file_catalog")
        if not files_name:
            continue
        files = json.loads((config / files_name).read_text(encoding="utf-8"))["files"]
        catalog["dumps"].append({
            "id": dump["id"],
            "serial": dump.get("serial", ""),
            "region": dump.get("region", ""),
            "title_id": dump.get("title_id", ""),
            "file_count": len(files),
            "size_bytes": sum(f["size_bytes"] for f in files),
            # [path, size, sha256] keeps the asset compact.
            "files": [[f["guest_path"], f["size_bytes"], f["sha256"].lower()] for f in files],
        })
    for update in updates["updates"]:
        catalog["title_updates"].append({
            "id": update["id"],
            "title_id": update.get("title_id", ""),
            "media_id": update.get("media_id", ""),
            "source_version": update.get("source_version", ""),
            "target_version": update.get("target_version", ""),
            "files": [[f["guest_path"], f["size_bytes"], f["sha256"].lower()]
                      for f in update.get("files", [])],
        })
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(catalog, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, "
          f"{sum(len(d['files']) for d in catalog['dumps'])} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
