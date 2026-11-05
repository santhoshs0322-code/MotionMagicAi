"""Reviewed public artwork shared by the API and Android's initial catalogue."""
import json
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "template-assets" / "image_templates"
BANNER_ROOT = ROOT.parent / "home_banners"


@lru_cache(maxsize=1)
def catalogue():
    return json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def banner_catalogue():
    return json.loads((BANNER_ROOT / "catalog.json").read_text(encoding="utf-8"))


def poster(key):
    if not key.startswith("builtin:"):
        return None
    name = key.removeprefix("builtin:")
    row = next((row for row in catalogue() + banner_catalogue() if row["file"] == name), None)
    return {"file": row["file"], "sha256": row["sha256"]} if row else None


def artwork_path(filename):
    if any(row["file"] == filename for row in catalogue()):
        return ROOT / filename
    if any(row["file"] == filename for row in banner_catalogue()):
        return BANNER_ROOT / filename
    raise ValueError("Unknown catalogue artwork")
