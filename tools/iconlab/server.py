"""IconLab local review server.

Run:  python server.py --teacher existing
Then: http://127.0.0.1:8765
"""

from __future__ import annotations

import argparse
import io
import json
import mimetypes
import os
import sys
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from PIL import Image

import dataset
import teacher

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"

RUNNER = teacher.TeacherRunner()
OPTIONS = argparse.Namespace(teacher="existing", model="birefnet-general", cuda=True)


def _item_json(item: dict) -> dict:
    item_id = item["id"]
    return {
        "id": item_id,
        "package": item["package"],
        "label": item["label"],
        "kind": item["kind"],
        "monochrome": item["monochrome"],
        "status": dataset.get_status(item_id),
        "has_reviewed": bool(item.get("reviewed")),
        "has_teacher": bool(item.get("teacher")),
        "has_fallback": bool(item.get("fallback")),
        "icon_url": f"/api/icon/{item_id}",
        "mask_url": f"/api/mask/{item_id}",
        "soft_url": f"/api/mask/{item_id}?source=soft",
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "IconLab/0.1"

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - stdlib signature
        if args and str(args[0]).startswith(("GET /api/teacher", "GET /api/items")):
            return
        sys.stderr.write("[iconlab] " + fmt % args + "\n")

    # ------------------------------------------------------------------ helpers
    def _json(self, payload: dict | list, status: int = 200) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _error(self, message: str, status: int = 400) -> None:
        self._json({"error": message}, status)

    def _bytes(self, data: bytes, content_type: str, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self, limit: int = 16 * 1024 * 1024) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > limit:
            raise ValueError("invalid body length")
        return self.rfile.read(length)

    def _json_body(self) -> dict:
        raw = self._body(1024 * 1024)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _item(self) -> dict | None:
        parts = urlparse(self.path).path.strip("/").split("/")
        if len(parts) < 3 or parts[0] != "api":
            return None
        return dataset.find_item(unquote(parts[2]))

    # ------------------------------------------------------------------- routes
    def do_GET(self) -> None:  # noqa: N802 - stdlib signature
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            query = parse_qs(parsed.query)
            if path == "/":
                return self._serve_file(STATIC / "index.html")
            if path.startswith("/static/"):
                return self._serve_file(STATIC / path[len("/static/") :])
            if path == "/api/items":
                return self._json([_item_json(item) for item in dataset.load_items()])
            if path == "/api/config":
                return self._json(
                    {
                        "teacher": OPTIONS.teacher,
                        "model": OPTIONS.model,
                        "cuda": OPTIONS.cuda,
                        "counts": dataset.counts(),
                        "dump": str(dataset.DUMP),
                    }
                )
            if path == "/api/teacher":
                return self._json(RUNNER.snapshot())
            if path.startswith("/api/icon/"):
                item = self._item()
                if not item:
                    return self._error("not found", 404)
                return self._bytes(Path(item["icon"]).read_bytes(), "image/png")
            if path.startswith("/api/mask/"):
                item = self._item()
                if not item:
                    return self._error("not found", 404)
                threshold = float(query.get("threshold", ["0.5"])[0])
                source = query.get("source", ["current"])[0]
                if source == "soft":
                    item = dict(item)
                    if item.get("reviewed"):
                        item["reviewed"] = None
                mask = dataset.current_mask(item, threshold=max(0.0, min(1.0, threshold)))
                if mask is None:
                    return self._error("no mask", 404)
                image, _source = mask
                buffer = io.BytesIO()
                image.save(buffer, format="PNG")
                return self._bytes(buffer.getvalue(), "image/png")
            return self._error("not found", 404)
        except Exception as error:  # noqa: BLE001
            traceback.print_exc()
            return self._error(str(error), 500)

    def do_POST(self) -> None:  # noqa: N802 - stdlib signature
        try:
            path = urlparse(self.path).path
            if path.startswith("/api/mask/"):
                item = self._item()
                if not item:
                    return self._error("not found", 404)
                dataset.save_mask(item, self._body(16 * 1024 * 1024))
                dataset.set_status(item["id"], "edited")
                return self._json({"ok": True, "status": "edited"})
            if path.startswith("/api/status/"):
                item = self._item()
                if not item:
                    return self._error("not found", 404)
                value = str(self._json_body().get("status", "todo"))
                dataset.set_status(item["id"], value)
                return self._json({"ok": True, "status": value})
            if path.startswith("/api/reset/"):
                item = self._item()
                if not item:
                    return self._error("not found", 404)
                dataset.reset_mask(item)
                dataset.set_status(item["id"], "todo")
                return self._json({"ok": True})
            if path == "/api/teacher/start":
                body = self._json_body()
                item_id = body.get("id")
                if item_id:
                    item = dataset.find_item(str(item_id))
                    items = [item] if item else []
                else:
                    items = dataset.load_items()
                started = RUNNER.start(
                    items,
                    teacher_name=OPTIONS.teacher,
                    model=OPTIONS.model,
                    only_missing=bool(body.get("only_missing", True)),
                    prefer_cuda=OPTIONS.cuda,
                )
                return self._json({"started": started, "state": RUNNER.snapshot()})
            if path == "/api/export":
                manifest = dataset.export_dataset(accepted_only=True)
                return self._json(
                    {
                        "ok": True,
                        "count": manifest["count"],
                        "path": str(dataset.EXPORT),
                    }
                )
            return self._error("not found", 404)
        except Exception as error:  # noqa: BLE001
            traceback.print_exc()
            return self._error(str(error), 500)

    # ------------------------------------------------------------------- static
    def _serve_file(self, path: Path) -> None:
        try:
            resolved = path.resolve()
            if STATIC.resolve() not in resolved.parents and resolved != STATIC.resolve():
                return self._error("forbidden", 403)
            if not resolved.is_file():
                return self._error("not found", 404)
            content_type = mimetypes.guess_type(str(resolved))[0] or "application/octet-stream"
            if resolved.suffix == ".js":
                content_type = "application/javascript"
            return self._bytes(resolved.read_bytes(), content_type)
        except OSError:
            return self._error("not found", 404)


def main() -> None:
    parser = argparse.ArgumentParser(description="IconLab mask review server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--teacher", choices=["existing", "birefnet"], default="existing")
    parser.add_argument("--model", default="birefnet-general")
    parser.add_argument("--no-cuda", action="store_true")
    args = parser.parse_args()
    OPTIONS.teacher = args.teacher
    OPTIONS.model = args.model
    OPTIONS.cuda = not args.no_cuda
    dataset.ensure_dirs()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"IconLab: http://{args.host}:{args.port}")
    print(f"Teacher: {args.teacher} ({args.model}) cuda={OPTIONS.cuda}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
