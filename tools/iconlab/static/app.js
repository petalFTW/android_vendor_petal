"use strict";

const SIZE = 512;
const canvas = document.getElementById("canvas");
const ctx = canvas.getContext("2d");
const maskCanvas = document.createElement("canvas");
maskCanvas.width = SIZE;
maskCanvas.height = SIZE;
const maskCtx = maskCanvas.getContext("2d", { willReadFrequently: true });
const overlayCanvas = document.createElement("canvas");
overlayCanvas.width = SIZE;
overlayCanvas.height = SIZE;
const overlayCtx = overlayCanvas.getContext("2d");

const el = (id) => document.getElementById(id);

const S = {
  items: [],
  index: -1,
  icon: null,
  threshold: 0.5,
  brush: 28,
  mode: "add",
  showOverlay: true,
  dirty: false,
  undo: [],
  drawing: false,
  last: null,
  pointer: null,
  teacherTimer: null,
  teacher: "existing",
  model: "",
};

async function api(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(text || `${response.status} ${response.statusText}`);
  }
  const type = response.headers.get("content-type") || "";
  return type.includes("application/json") ? response.json() : response;
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`image failed: ${url}`));
    image.src = url;
  });
}

function currentItem() {
  return S.items[S.index] || null;
}

function setDirty(value) {
  S.dirty = value;
  el("dirty").hidden = !value;
  updateControls();
}

function updateControls() {
  const item = currentItem();
  const locked = !item || item.has_reviewed || S.dirty;
  el("threshold").disabled = locked;
  el("reset-btn").disabled = !item || (!item.has_teacher && !item.has_fallback);
  el("save-btn").disabled = !item;
  el("accept-btn").disabled = !item;
  el("skip-btn").disabled = !item;
  el("add-btn").classList.toggle("active", S.mode === "add");
  el("erase-btn").classList.toggle("active", S.mode === "erase");
  el("overlay-btn").classList.toggle("active", S.showOverlay);
}

function renderList() {
  const query = el("search").value.trim().toLowerCase();
  const list = el("list");
  const scroll = list.scrollTop;
  list.replaceChildren();
  S.items.forEach((item, index) => {
    if (query && !`${item.package} ${item.label}`.toLowerCase().includes(query)) return;
    const row = document.createElement("div");
    row.className = "row" + (index === S.index ? " active" : "");
    row.dataset.index = String(index);
    row.innerHTML = `
      <span class="dot ${item.status}"></span>
      <span class="row-text">
        <span class="row-label"></span>
        <span class="row-pkg"></span>
      </span>`;
    row.querySelector(".row-label").textContent = item.label;
    row.querySelector(".row-pkg").textContent = item.package;
    row.addEventListener("click", () => select(index));
    list.appendChild(row);
  });
  list.scrollTop = scroll;
}

function updateProgress() {
  const total = S.items.length;
  const done = S.items.filter((item) => item.status === "accepted" || item.status === "skipped").length;
  const pct = total ? Math.round((done / total) * 100) : 0;
  el("progress-text").textContent = `${done} / ${total}`;
  el("progress-pct").textContent = `${pct}%`;
  el("progress-bar").style.width = `${pct}%`;
}

function updateHeader() {
  const item = currentItem();
  el("item-label").textContent = item ? item.label : "No icon selected";
  el("item-package").textContent = item ? item.package : "";
  el("item-kind").textContent = item ? item.kind : "-";
  el("item-status").textContent = item ? item.status : "todo";
  el("empty").hidden = !!item;
  el("canvas").style.visibility = item ? "visible" : "hidden";
}

async function loadItems() {
  S.items = await api("/api/items");
  renderList();
  updateProgress();
  updateHeader();
  updateControls();
}

async function select(index, options = {}) {
  if (index < 0 || index >= S.items.length) return;
  if (S.dirty && !options.force) {
    await saveMask("edited", { silent: true });
  }
  S.index = index;
  const item = currentItem();
  S.icon = await loadImage(`${item.icon_url}?t=${Date.now()}`);
  S.undo = [];
  setDirty(false);
  renderList();
  updateHeader();
  await loadMaskForItem(item);
  updateControls();
  render();
}

async function loadMaskForItem(item) {
  maskCtx.clearRect(0, 0, SIZE, SIZE);
  let url = null;
  if (item.has_reviewed) {
    url = `${item.mask_url}?t=${Date.now()}`;
  } else if (item.has_teacher || item.has_fallback) {
    url = `${item.soft_url}&threshold=${S.threshold}&t=${Date.now()}`;
  }
  if (!url) return;
  try {
    const image = await loadImage(url);
    applyLuminanceMask(image);
  } catch (_) {
    // No mask yet.
  }
}

function applyLuminanceMask(image) {
  const tmp = document.createElement("canvas");
  tmp.width = SIZE;
  tmp.height = SIZE;
  const tctx = tmp.getContext("2d", { willReadFrequently: true });
  tctx.drawImage(image, 0, 0, SIZE, SIZE);
  const source = tctx.getImageData(0, 0, SIZE, SIZE);
  const out = maskCtx.createImageData(SIZE, SIZE);
  for (let i = 0; i < source.data.length; i += 4) {
    const value = Math.max(source.data[i], source.data[i + 1], source.data[i + 2]);
    out.data[i] = 255;
    out.data[i + 1] = 255;
    out.data[i + 2] = 255;
    out.data[i + 3] = value;
  }
  maskCtx.putImageData(out, 0, 0);
}

function pushUndo() {
  try {
    S.undo.push(maskCtx.getImageData(0, 0, SIZE, SIZE));
    if (S.undo.length > 30) S.undo.shift();
  } catch (_) {
    // Ignore.
  }
}

function undo() {
  const state = S.undo.pop();
  if (!state) return;
  maskCtx.putImageData(state, 0, 0);
  setDirty(true);
  render();
}

function eventPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: ((event.clientX - rect.left) / rect.width) * SIZE,
    y: ((event.clientY - rect.top) / rect.height) * SIZE,
  };
}

function paintDot(point) {
  maskCtx.save();
  maskCtx.globalCompositeOperation = "source-over";
  maskCtx.fillStyle = "#ffffff";
  if (S.mode === "erase") {
    maskCtx.globalCompositeOperation = "destination-out";
    maskCtx.fillStyle = "#000000";
  }
  maskCtx.beginPath();
  maskCtx.arc(point.x, point.y, S.brush / 2, 0, Math.PI * 2);
  maskCtx.fill();
  maskCtx.restore();
}

function paintLine(from, to) {
  maskCtx.save();
  maskCtx.lineCap = "round";
  maskCtx.lineJoin = "round";
  maskCtx.lineWidth = S.brush;
  if (S.mode === "erase") {
    maskCtx.globalCompositeOperation = "destination-out";
    maskCtx.strokeStyle = "#000000";
  } else {
    maskCtx.globalCompositeOperation = "source-over";
    maskCtx.strokeStyle = "#ffffff";
  }
  maskCtx.beginPath();
  maskCtx.moveTo(from.x, from.y);
  maskCtx.lineTo(to.x, to.y);
  maskCtx.stroke();
  maskCtx.restore();
}

function drawCheckerboard() {
  const unit = 16;
  for (let y = 0; y < SIZE; y += unit) {
    for (let x = 0; x < SIZE; x += unit) {
      ctx.fillStyle = ((x / unit + y / unit) & 1) ? "#23252a" : "#1b1d21";
      ctx.fillRect(x, y, unit, unit);
    }
  }
}

function renderOverlay() {
  overlayCtx.clearRect(0, 0, SIZE, SIZE);
  overlayCtx.globalCompositeOperation = "source-over";
  overlayCtx.drawImage(maskCanvas, 0, 0);
  overlayCtx.globalCompositeOperation = "source-in";
  overlayCtx.fillStyle = "rgba(255, 68, 84, 0.55)";
  overlayCtx.fillRect(0, 0, SIZE, SIZE);
  overlayCtx.globalCompositeOperation = "source-over";
}

function render() {
  ctx.clearRect(0, 0, SIZE, SIZE);
  drawCheckerboard();
  if (S.icon) ctx.drawImage(S.icon, 0, 0, SIZE, SIZE);
  if (S.showOverlay) {
    renderOverlay();
    ctx.drawImage(overlayCanvas, 0, 0);
  }
  if (S.pointer) {
    ctx.save();
    ctx.lineWidth = 2;
    ctx.strokeStyle = S.mode === "erase" ? "#ff8a8a" : "#ffffff";
    ctx.beginPath();
    ctx.arc(S.pointer.x, S.pointer.y, S.brush / 2, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }
}

async function saveMask(status = "edited", options = {}) {
  const item = currentItem();
  if (!item) return false;
  const blob = await new Promise((resolve) => maskCanvas.toBlob(resolve, "image/png"));
  if (!blob) return false;
  try {
    await api(`/api/mask/${item.id}`, { method: "POST", body: blob });
    await api(`/api/status/${item.id}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    });
    item.has_reviewed = true;
    item.status = status;
    setDirty(false);
    renderList();
    updateProgress();
    updateHeader();
    updateControls();
    if (!options.silent) {
      el("teacher-status").textContent = status === "accepted" ? "Accepted" : "Saved";
    }
    return true;
  } catch (error) {
    el("teacher-status").textContent = String(error.message || error);
    return false;
  }
}

async function markStatus(status) {
  const item = currentItem();
  if (!item) return;
  try {
    await api(`/api/status/${item.id}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    });
    item.status = status;
    renderList();
    updateProgress();
    updateHeader();
  } catch (error) {
    el("teacher-status").textContent = String(error.message || error);
  }
}

function nextTodo(from) {
  const total = S.items.length;
  for (let step = 1; step <= total; step += 1) {
    const index = (from + step) % total;
    const status = S.items[index].status;
    if (status !== "accepted" && status !== "skipped") return index;
  }
  return -1;
}

async function accept() {
  if (!currentItem()) return;
  if (!(await saveMask("accepted"))) return;
  const next = nextTodo(S.index);
  if (next >= 0) await select(next);
}

async function skip() {
  if (!currentItem()) return;
  if (!(await saveMask("skipped"))) return;
  const next = nextTodo(S.index);
  if (next >= 0) await select(next);
}

async function resetMask() {
  const item = currentItem();
  if (!item) return;
  if (!confirm("Discard edits and reload the teacher mask?")) return;
  await api(`/api/reset/${item.id}`, { method: "POST" });
  item.has_reviewed = false;
  item.status = "todo";
  setDirty(false);
  S.undo = [];
  await loadMaskForItem(item);
  renderList();
  updateHeader();
  updateControls();
  render();
}

async function reloadSoft() {
  const item = currentItem();
  if (!item || item.has_reviewed || S.dirty) return;
  S.undo = [];
  await loadMaskForItem(item);
  render();
}

async function runTeacher() {
  try {
    const result = await api("/api/teacher/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ only_missing: true }),
    });
    if (!result.started) {
      el("teacher-status").textContent = "Teacher already running";
    }
    pollTeacher();
  } catch (error) {
    el("teacher-status").textContent = String(error.message || error);
  }
}

async function pollTeacher() {
  if (S.teacherTimer) clearTimeout(S.teacherTimer);
  try {
    const state = await api("/api/teacher");
    if (state.running) {
      el("teacher-btn").disabled = true;
      el("teacher-status").textContent =
        `${state.done}/${state.total} ${state.current ? "· " + state.current : ""}`;
      S.teacherTimer = setTimeout(pollTeacher, 900);
      return;
    }
    el("teacher-btn").disabled = false;
    el("teacher-status").textContent = state.error ? `Error: ${state.error}` : "Teacher idle";
    if (state.total > 0) {
      await loadItems();
      if (currentItem()) await loadMaskForItem(currentItem());
      render();
    }
  } catch (error) {
    el("teacher-btn").disabled = false;
    el("teacher-status").textContent = String(error.message || error);
    S.teacherTimer = setTimeout(pollTeacher, 2500);
  }
}

async function exportDataset() {
  try {
    const result = await api("/api/export", { method: "POST" });
    el("teacher-status").textContent = `Exported ${result.count} items`;
    alert(`Exported ${result.count} accepted icons to:\n${result.path}`);
  } catch (error) {
    el("teacher-status").textContent = String(error.message || error);
  }
}

// ------------------------------------------------------------------ events
canvas.addEventListener("pointerdown", (event) => {
  if (!currentItem()) return;
  event.preventDefault();
  canvas.setPointerCapture(event.pointerId);
  pushUndo();
  S.drawing = true;
  S.last = eventPoint(event);
  S.pointer = S.last;
  paintDot(S.last);
  setDirty(true);
  render();
});

canvas.addEventListener("pointermove", (event) => {
  S.pointer = eventPoint(event);
  if (S.drawing && currentItem()) {
    paintLine(S.last, S.pointer);
    S.last = S.pointer;
    setDirty(true);
  }
  render();
});

canvas.addEventListener("pointerup", (event) => {
  S.drawing = false;
  try { canvas.releasePointerCapture(event.pointerId); } catch (_) {}
  render();
});

canvas.addEventListener("pointerleave", () => {
  S.pointer = null;
  render();
});

el("search").addEventListener("input", renderList);

el("add-btn").addEventListener("click", () => { S.mode = "add"; updateControls(); });
el("erase-btn").addEventListener("click", () => { S.mode = "erase"; updateControls(); });
el("undo-btn").addEventListener("click", undo);
el("overlay-btn").addEventListener("click", () => { S.showOverlay = !S.showOverlay; updateControls(); render(); });
el("reset-btn").addEventListener("click", resetMask);
el("save-btn").addEventListener("click", () => saveMask("edited"));
el("accept-btn").addEventListener("click", accept);
el("skip-btn").addEventListener("click", skip);
el("teacher-btn").addEventListener("click", runTeacher);
el("export-btn").addEventListener("click", exportDataset);

el("brush").addEventListener("input", (event) => {
  S.brush = Number(event.target.value);
  el("brush-value").textContent = String(S.brush);
  render();
});

let thresholdTimer = null;
el("threshold").addEventListener("input", (event) => {
  S.threshold = Number(event.target.value) / 100;
  el("threshold-value").textContent = String(event.target.value);
  if (thresholdTimer) clearTimeout(thresholdTimer);
  thresholdTimer = setTimeout(reloadSoft, 160);
});

window.addEventListener("keydown", (event) => {
  const target = event.target;
  if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
  const key = event.key.toLowerCase();
  if ((event.ctrlKey || event.metaKey) && key === "z") {
    event.preventDefault();
    undo();
  } else if (key === "a") {
    event.preventDefault();
    accept();
  } else if (key === "s") {
    event.preventDefault();
    skip();
  } else if (key === "e") {
    S.showOverlay = !S.showOverlay;
    updateControls();
    render();
  } else if (key === "r") {
    resetMask();
  } else if (key === "[") {
    S.brush = Math.max(4, S.brush - 4);
    el("brush").value = String(S.brush);
    el("brush-value").textContent = String(S.brush);
    render();
  } else if (key === "]") {
    S.brush = Math.min(96, S.brush + 4);
    el("brush").value = String(S.brush);
    el("brush-value").textContent = String(S.brush);
    render();
  } else if (key === "1") {
    S.mode = "add"; updateControls();
  } else if (key === "2") {
    S.mode = "erase"; updateControls();
  } else if (key === "arrowright") {
    select(Math.min(S.items.length - 1, S.index + 1));
  } else if (key === "arrowleft") {
    select(Math.max(0, S.index - 1));
  }
});

window.addEventListener("beforeunload", (event) => {
  if (S.dirty) {
    event.preventDefault();
    event.returnValue = "";
  }
});

// ------------------------------------------------------------------- boot
(async () => {
  try {
    const config = await api("/api/config");
    S.teacher = config.teacher;
    S.model = config.model;
    el("teacher-status").textContent = `Teacher: ${config.teacher}${config.model ? " · " + config.model : ""}`;
    await loadItems();
    const first = S.items.findIndex((item) => item.status !== "accepted" && item.status !== "skipped");
    if (first >= 0) await select(first);
    await pollTeacher();
  } catch (error) {
    el("teacher-status").textContent = String(error.message || error);
  }
})();
