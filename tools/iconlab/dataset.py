"""Dataset scanning, mask storage, and export for IconLab."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import threading
from pathlib import Path
from typing import Any

from PIL import Image

ROOT = Path(__file__).resolve().parent
DUMP = ROOT / "data" / "dump"
WORK = ROOT / "work"
MASKS = WORK / "masks"
TEACHER = WORK / "teacher"
EXPORT = WORK / "export"
STATUS_FILE = WORK / "status.json"

_LOCK = threading.Lock()
_THRESHOLD = 0.5

VALID_STATUS = {"todo", "accepted", "skipped", "edited"}


def ensure_dirs() -> None:
    for path in (WORK, MASKS, TEACHER, EXPORT):
        path.mkdir(parents=True, exist_ok=True)


def safe_id(package: str) -> str:
    return package.replace("/", "_").replace("\\", "_")


def _read_status() -> dict[str, str]:
    if not STATUS_FILE.exists():
        return {}
    try:
        data = json.loads(STATUS_FILE.read_text(encoding="utf-8"))
        return {str(k): str(v) for k, v in data.items() if v in VALID_STATUS}
    except (OSError, ValueError):
        return {}


def _write_status(status: dict[str, str]) -> None:
    ensure_dirs()
    tmp = STATUS_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(status, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, STATUS_FILE)


def get_status(item_id: str) -> str:
    with _LOCK:
        return _read_status().get(item_id, "todo")


def set_status(item_id: str, value: str) -> None:
    if value not in VALID_STATUS:
        raise ValueError(f"invalid status: {value}")
    with _LOCK:
        status = _read_status()
        status[item_id] = value
        _write_status(status)


def _has_alpha(image: Image.Image) -> bool:
    if image.mode not in ("RGBA", "LA"):
        return False
    alpha = image.getchannel("A")
    low, high = alpha.getextrema()
    return low < 250


def mask_to_l(image: Image.Image) -> Image.Image:
    """Normalise a mask image to an 8-bit grayscale image (white = subject).

    Handles the two common forms:
    * white-on-transparent masks (alpha is the mask)
    * white-on-black or grayscale masks (luminance is the mask)
    """
    image = image.convert("RGBA")
    if _has_alpha(image):
        return image.getchannel("A")
    return image.convert("L")


def load_mask(path: Path, size: tuple[int, int]) -> Image.Image | None:
    if not path or not path.is_file():
        return None
    try:
        with Image.open(path) as image:
            mask = mask_to_l(image)
            if mask.size != size:
                mask = mask.resize(size, Image.Resampling.LANCZOS)
            return mask
    except OSError:
        return None


def threshold_mask(mask: Image.Image, threshold: float) -> Image.Image:
    cutoff = max(0, min(255, round(threshold * 255)))
    return mask.point(lambda value: 255 if value >= cutoff else 0, "L")


def load_items() -> list[dict[str, Any]]:
    ensure_dirs()
    status = _read_status()
    items: list[dict[str, Any]] = []
    metadata = DUMP / "metadata.csv"
    if not metadata.is_file():
        return items
    with metadata.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            package = (row.get("package") or "").strip()
            if not package:
                continue
            item_id = safe_id(package)
            flat = DUMP / f"{package}.flat.png"
            icon = DUMP / f"{package}.icon.png"
            icon_path = flat if flat.is_file() else icon
            if not icon_path.is_file():
                continue
            fgmask = DUMP / f"{package}.fgmask.png"
            monomask = DUMP / f"{package}.monomask.png"
            fallback = fgmask if fgmask.is_file() else monomask if monomask.is_file() else None
            reviewed = MASKS / f"{item_id}.png"
            teacher = TEACHER / f"{item_id}.png"
            items.append(
                {
                    "id": item_id,
                    "package": package,
                    "label": row.get("label") or package,
                    "kind": "adaptive" if row.get("adaptive") == "true" else "legacy",
                    "monochrome": row.get("monochrome") == "true",
                    "icon": str(icon_path),
                    "fallback": str(fallback) if fallback else None,
                    "reviewed": str(reviewed) if reviewed.is_file() else None,
                    "teacher": str(teacher) if teacher.is_file() else None,
                    "status": status.get(item_id, "todo"),
                }
            )
    return items


def find_item(item_id: str) -> dict[str, Any] | None:
    for item in load_items():
        if item["id"] == item_id:
            return item
    return None


def current_mask(item: dict[str, Any], threshold: float = _THRESHOLD) -> tuple[Image.Image, str] | None:
    """Return (mask, source) for the item, preferring reviewed > teacher > fallback."""
    with Image.open(item["icon"]) as icon:
        size = icon.size
    if item.get("reviewed"):
        mask = load_mask(Path(item["reviewed"]), size)
        if mask is not None:
            return mask, "reviewed"
    if item.get("teacher"):
        mask = load_mask(Path(item["teacher"]), size)
        if mask is not None:
            return threshold_mask(mask, threshold), "teacher"
    if item.get("fallback"):
        mask = load_mask(Path(item["fallback"]), size)
        if mask is not None:
            return threshold_mask(mask, threshold), "fallback"
    return None


def save_mask(item: dict[str, Any], payload: bytes) -> None:
    ensure_dirs()
    with Image.open(io.BytesIO(payload)) as image:
        mask = mask_to_l(image)
    with Image.open(item["icon"]) as icon:
        size = icon.size
    if mask.size != size:
        mask = mask.resize(size, Image.Resampling.LANCZOS)
    target = MASKS / f"{item['id']}.png"
    tmp = target.with_suffix(".png.tmp")
    mask.save(tmp, format="PNG")
    os.replace(tmp, target)


def save_teacher(item: dict[str, Any], mask: Image.Image) -> None:
    ensure_dirs()
    with Image.open(item["icon"]) as icon:
        size = icon.size
    if mask.size != size:
        mask = mask.resize(size, Image.Resampling.LANCZOS)
    target = TEACHER / f"{item['id']}.png"
    tmp = target.with_suffix(".png.tmp")
    mask.convert("L").save(tmp, format="PNG")
    os.replace(tmp, target)


def reset_mask(item: dict[str, Any]) -> None:
    target = MASKS / f"{item['id']}.png"
    if target.exists():
        target.unlink()


def counts() -> dict[str, int]:
    items = load_items()
    result = {"total": len(items), "accepted": 0, "skipped": 0, "todo": 0, "reviewed": 0}
    for item in items:
        if item["status"] in result:
            result[item["status"]] += 1
        if item["reviewed"]:
            result["reviewed"] += 1
    return result


def export_dataset(accepted_only: bool = True) -> dict[str, Any]:
    ensure_dirs()
    images = EXPORT / "images"
    masks = EXPORT / "masks"
    images.mkdir(parents=True, exist_ok=True)
    masks.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, Any]] = []
    for item in load_items():
        if accepted_only and item["status"] != "accepted":
            continue
        mask = current_mask(item)
        if mask is None:
            continue
        mask_image, source = mask
        if source == "fallback" and item["status"] != "accepted":
            continue
        item_id = item["id"]
        with Image.open(item["icon"]) as icon:
            icon.convert("RGBA").save(images / f"{item_id}.png")
        mask_image.save(masks / f"{item_id}.png")
        digest = hashlib.sha1(item["package"].encode("utf-8")).digest()
        split = "val" if digest[0] < 38 else "train"
        records.append(
            {
                "id": item_id,
                "package": item["package"],
                "label": item["label"],
                "kind": item["kind"],
                "image": f"images/{item_id}.png",
                "mask": f"masks/{item_id}.png",
                "split": split,
                "source": source,
            }
        )
    manifest = {
        "version": 1,
        "size": 512,
        "count": len(records),
        "records": records,
    }
    (EXPORT / "dataset.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest
