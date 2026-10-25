"""Teacher models for first-pass icon masks.

The default teacher is "existing", which reuses the adaptive foreground/monochrome
masks produced by the on-device dump. On Windows, use --teacher birefnet to run
BiRefNet on the GPU through rembg.
"""

from __future__ import annotations

import threading
import traceback
from pathlib import Path
from typing import Any

from PIL import Image

import dataset


class TeacherError(RuntimeError):
    pass


class ExistingTeacher:
    name = "existing"

    def predict(self, item: dict[str, Any]) -> Image.Image | None:
        fallback = item.get("fallback")
        if not fallback:
            return None
        with Image.open(fallback) as image:
            return dataset.mask_to_l(image)


class BiRefNetTeacher:
    name = "birefnet"

    def __init__(self, model: str = "birefnet-general", prefer_cuda: bool = True):
        self.model = model
        self.prefer_cuda = prefer_cuda
        self._session = None
        self._remove = None
        self.providers: list[str] = []

    def _load(self) -> None:
        if self._session is not None:
            return
        try:
            import onnxruntime as ort
            from rembg import new_session, remove
        except ImportError as error:
            raise TeacherError(
                "rembg/onnxruntime is not installed. Run: "
                "pip install -r requirements-teacher.txt"
            ) from error

        available = ort.get_available_providers()
        if self.prefer_cuda and "CUDAExecutionProvider" in available:
            self.providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
        else:
            self.providers = ["CPUExecutionProvider"]
        try:
            self._session = new_session(self.model, providers=self.providers)
        except Exception as error:  # noqa: BLE001 - surface the download/runtime error
            raise TeacherError(
                f"Could not load rembg model '{self.model}' on {self.providers}: {error}"
            ) from error
        self._remove = remove

    def predict(self, item: dict[str, Any]) -> Image.Image | None:
        self._load()
        assert self._remove is not None
        with Image.open(item["icon"]) as image:
            source = image.convert("RGBA")
        try:
            result = self._remove(
                source,
                session=self._session,
                only_mask=True,
                post_process_mask=True,
            )
        except TypeError:
            result = self._remove(source, session=self._session, only_mask=True)
        if result.mode == "RGBA":
            return result.getchannel("A")
        return result.convert("L")


class TeacherRunner:
    """Runs a teacher over items in a single background thread."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._state: dict[str, Any] = {
            "running": False,
            "done": 0,
            "total": 0,
            "current": "",
            "error": "",
            "teacher": "",
        }

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._state)

    def start(
        self,
        items: list[dict[str, Any]],
        teacher_name: str,
        model: str,
        only_missing: bool = True,
        prefer_cuda: bool = True,
    ) -> bool:
        with self._lock:
            if self._state["running"]:
                return False
            selected = [
                item for item in items if not only_missing or not item.get("teacher")
            ]
            self._state = {
                "running": True,
                "done": 0,
                "total": len(selected),
                "current": "",
                "error": "",
                "teacher": teacher_name,
            }
        self._thread = threading.Thread(
            target=self._run,
            args=(selected, teacher_name, model, prefer_cuda),
            daemon=True,
            name="iconlab-teacher",
        )
        self._thread.start()
        return True

    def _run(
        self,
        items: list[dict[str, Any]],
        teacher_name: str,
        model: str,
        prefer_cuda: bool,
    ) -> None:
        try:
            if teacher_name == "birefnet":
                teacher: Any = BiRefNetTeacher(model=model, prefer_cuda=prefer_cuda)
            elif teacher_name == "existing":
                teacher = ExistingTeacher()
            else:
                raise TeacherError(f"unknown teacher: {teacher_name}")
            for index, item in enumerate(items, 1):
                with self._lock:
                    self._state["current"] = item["package"]
                try:
                    mask = teacher.predict(item)
                    if mask is not None:
                        dataset.save_teacher(item, mask)
                except TeacherError:
                    raise
                except Exception:  # noqa: BLE001 - one bad icon must not stop the batch
                    traceback.print_exc()
                with self._lock:
                    self._state["done"] = index
        except Exception as error:  # noqa: BLE001 - report to the UI
            traceback.print_exc()
            with self._lock:
                self._state["error"] = str(error)
        finally:
            with self._lock:
                self._state["running"] = False
                self._state["current"] = ""
