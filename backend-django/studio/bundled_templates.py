"""Reviewed public artwork shared by the API and Android's initial catalogue."""
import json
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "template-assets" / "image_templates"


@lru_cache(maxsize=1)
def catalogue():
    return json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))


def poster(key):
    if not key.startswith("builtin:"):
        return None
    name = key.removeprefix("builtin:")
    row = next((row for row in catalogue() if row["file"] == name), None)
    return {"file": row["file"], "sha256": row["sha256"]} if row else None
