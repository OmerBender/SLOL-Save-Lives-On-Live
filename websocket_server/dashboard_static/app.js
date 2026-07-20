/* =========================================================
   Disaster-Response Command Center — operational front-end
   ---------------------------------------------------------
   - One card per camera (only cameras that have produced a
     detection in the current event are shown).
   - Top bar shows the active event and Start/Close controls.
   - Click a card to open its detection list with validation
     (Correct / Incorrect / Unsure) per detection.
   - WebSocket pushes: detection, validation, event_started,
     event_closed, hello.
   ========================================================= */

const CLASS_NAMES = ["hand", "arm", "head", "leg", "foot", "person"];

const els = {
  wsStatus:   document.getElementById("ws-status"),
  modelPill:  document.getElementById("model-pill"),
  clock:      document.getElementById("clock"),
  eventName:  document.getElementById("event-name"),
  eventMeta:  document.getElementById("event-meta"),
  btnStart:   document.getElementById("btn-start-event"),
  btnClose:   document.getElementById("btn-close-event"),
  grid:       document.getElementById("camera-grid"),
  cameraCount:        document.getElementById("camera-count"),
  eventDetCount:      document.getElementById("event-detection-count"),

  modal:        document.getElementById("modal"),
  modalTitle:   document.getElementById("modal-title"),
  modalSubtitle:document.getElementById("modal-subtitle"),
  modalBody:    document.getElementById("modal-body"),

  eventModal:        document.getElementById("event-modal"),
  eventNameInput:    document.getElementById("event-name-input"),
  btnConfirmStart:   document.getElementById("btn-confirm-start"),

  // Lightbox (built statically in index.html)
  lb:           document.getElementById("lightbox"),
  lbImage:      document.getElementById("lb-image"),
  lbCounter:    document.getElementById("lb-counter"),
  lbHint:       document.getElementById("lb-hint"),
  lbMeta:       document.getElementById("lb-meta"),
  lbValidation: document.getElementById("lb-validation"),
  lbArrowPrev:  document.getElementById("lb-arrow-prev"),
  lbArrowNext:  document.getElementById("lb-arrow-next"),
  lbPrev:       document.getElementById("lb-prev"),
  lbNext:       document.getElementById("lb-next"),
};

const state = {
  // event info
  activeEvent: null,                  // { event_id, name, started_at, ... } or null
  // camera_id -> camera record (see ensureCamera/applyCameraList)
  cameras: new Map(),
  // detection_id -> detection (current event only)
  detectionsById: new Map(),
  // open modal target ("camera" id, or "slot:N", or null)
  openTarget: null,
  // total detections in the current event
  eventDetectionCount: 0,
  // number of empty/offline slots to pre-allocate (loaded from /api/info)
  dashboardSlots: 8,
  // Active lightbox session, or null. Snapshot of the camera's detection
  // list so the operator can browse without being shifted by new arrivals.
  // { camera_id, detections: [refs], index: int, imageType: "frame"|"crop" }
  lightbox: null,
};

/* ---------------- helpers ---------------- */
function fmtTime(iso) {
  if (!iso) return "—";
  const t = (iso.split("T")[1] || iso).slice(0, 12);
  return t;
}
function fmtDate(iso) {
  if (!iso) return "—";
  return (iso.split("T")[0] || iso);
}
function fmtConf(c) { return (c * 100).toFixed(1) + "%"; }
function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
  ));
}

function tickClock() {
  const d = new Date();
  els.clock.textContent =
    String(d.getHours()).padStart(2, "0") + ":" +
    String(d.getMinutes()).padStart(2, "0") + ":" +
    String(d.getSeconds()).padStart(2, "0");
}
setInterval(tickClock, 1000); tickClock();

/* ---------------- model info ---------------- */
async function loadInfo() {
  try {
    const r = await fetch("/dashboard/api/info");
    const j = await r.json();
    els.modelPill.textContent = `model: ${j.detector}`;
    els.modelPill.style.color = j.detector === "REAL_YOLO" ? "var(--good)" : "var(--warn)";
    if (typeof j.dashboard_slots === "number" && j.dashboard_slots >= 0) {
      state.dashboardSlots = j.dashboard_slots;
      renderGrid();
    }
  } catch (_) { /* ignore */ }
}

/* Pull the configured camera list early so Team 1..N show up in their
   slots before any detection arrives or the WebSocket connects. */
async function loadCameras() {
  try {
    const r = await fetch("/dashboard/api/cameras");
    const j = await r.json();
    applyCameraList(j.cameras || []);
    renderGrid();
  } catch (_) { /* ignore — WS hello will populate it instead */ }
}

/* ---------------- event control ---------------- */
function renderEvent() {
  const ev = state.activeEvent;
  if (ev) {
    els.eventName.textContent = ev.name || `Event ${ev.event_id}`;
    els.eventName.classList.remove("empty");
    els.eventMeta.textContent =
      `id: ${ev.event_id} · started ${fmtTime(ev.started_at)} ${fmtDate(ev.started_at)}`;
    els.btnClose.disabled = false;
    els.btnStart.textContent = "Start New Event";
  } else {
    els.eventName.textContent = "— no active event —";
    els.eventName.classList.add("empty");
    els.eventMeta.textContent = "Detections will not be attributed to any event until you start one.";
    els.btnClose.disabled = true;
    els.btnStart.textContent = "Start Event";
  }
}

async function startEvent(name) {
  const r = await fetch("/dashboard/api/events/start", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ event_name: name || null }),
  });
  if (!r.ok) {
    alert("Failed to start event: " + r.status);
    return;
  }
  const ev = await r.json();
  onEventStarted(ev);
}

async function closeEvent() {
  if (!state.activeEvent) return;
  if (!confirm(`Close event "${state.activeEvent.name || state.activeEvent.event_id}"?`)) return;
  const r = await fetch("/dashboard/api/events/close", { method: "POST" });
  if (!r.ok) {
    alert("Failed to close event: " + r.status);
    return;
  }
  const ev = await r.json();
  onEventClosed(ev);
}

/* Reset detection state (counts, history) on event boundaries, but keep
   the camera cards in their slots so the wall layout is stable. */
function _resetDetectionsKeepCameras() {
  for (const cam of state.cameras.values()) {
    cam.detections = [];
    cam.flashing = false;
  }
  state.detectionsById.clear();
  state.eventDetectionCount = 0;
  els.eventDetCount.textContent = "0 detections";
  state.openTarget = null;
  els.modal.classList.add("hidden");
}
function onEventStarted(ev) {
  state.activeEvent = ev;
  _resetDetectionsKeepCameras();
  renderEvent();
  renderGrid();
}
function onEventClosed(_ev) {
  state.activeEvent = null;
  _resetDetectionsKeepCameras();
  renderEvent();
  renderGrid();
}

/* ---------------- camera grid ----------------
   Cameras are addressed by their `slot` (1..N). Configured cameras get
   stable slots from the server (cam_1 -> Slot 1, cam_2 -> Slot 2, ...);
   field-device cameras claim the next free slot when their first
   detection arrives. Cards persist across event start/close — only
   their detection lists clear. */

function _newCameraRecord(over) {
  return Object.assign({
    camera_id:      "",
    camera_name:    "",
    slot:           null,
    source:         "",
    configured:     false,
    status:         "offline",  // offline | online | completed | live
    detections:     [],
    lastSeenAt:     null,
    flashing:       false,
    last_frame_url: null,       // raw latest-frame preview, independent of detections
  }, over);
}

function ensureCamera(cam_id, cam_name) {
  if (!state.cameras.has(cam_id)) {
    // Field-device camera that wasn't pre-registered. Pick the next
    // free slot after every existing one.
    const maxSlot = Array.from(state.cameras.values())
                          .reduce((m, c) => Math.max(m, c.slot || 0), 0);
    state.cameras.set(cam_id, _newCameraRecord({
      camera_id:   cam_id,
      camera_name: cam_name || cam_id,
      slot:        maxSlot + 1,
      source:      "cloud-live-websocket",
      configured:  false,
      status:      "live",
    }));
  } else if (cam_name) {
    state.cameras.get(cam_id).camera_name = cam_name;
  }
  return state.cameras.get(cam_id);
}

/* Apply a server-provided camera list (from /api/cameras or WS hello).
   Preserves any existing detection arrays so a reconnect doesn't lose
   the in-memory event view. */
function applyCameraList(list) {
  if (!Array.isArray(list)) return;
  const next = new Map();
  for (const c of list) {
    const prev = state.cameras.get(c.camera_id);
    next.set(c.camera_id, _newCameraRecord({
      camera_id:      c.camera_id,
      camera_name:    c.camera_name || c.camera_id,
      slot:           c.slot || (next.size + 1),
      source:         c.source || "",
      configured:     !!c.configured,
      status:         c.status || "offline",
      detections:     prev?.detections || [],
      lastSeenAt:     prev?.lastSeenAt || null,
      flashing:       false,
      // Server-supplied URL wins; otherwise keep what the dashboard already had.
      last_frame_url: c.last_frame_url || prev?.last_frame_url || null,
    }));
  }
  // Keep any field-device cameras the dashboard learned about that the
  // server hasn't echoed back yet (e.g. mid-broadcast race).
  for (const [cid, cam] of state.cameras.entries()) {
    if (!next.has(cid)) next.set(cid, cam);
  }
  state.cameras = next;
}

function applyCameraStatus({ camera_id, status }) {
  const cam = state.cameras.get(camera_id);
  if (!cam) return;
  cam.status = status;
  // Don't clobber lastSeenAt; status colour computation will use it.
  rerenderActiveCard(camera_id);
}

/* Live preview: the server saves outputs/<date>/<camera_id>/last.jpg on
   every inbound frame, and broadcasts a throttled `frame_update` WS
   message. The dashboard simply swaps the left thumb's src. */
function applyFrameUpdate({ camera_id, last_frame_url }) {
  if (!camera_id || !last_frame_url) return;
  const cam = state.cameras.get(camera_id);
  if (!cam) return;
  cam.last_frame_url = last_frame_url;
  rerenderActiveCard(camera_id);
}

function ingestDetection(d, isLive) {
  const isNewCamera = !state.cameras.has(d.camera_id);
  const cam = ensureCamera(d.camera_id, d.camera_name);
  cam.lastSeenAt = new Date();
  // Don't downgrade a "completed" indicator just because a backfilled
  // detection lands; only fresh, live detections imply the source is
  // currently feeding the server.
  if (isLive && cam.status !== "completed") cam.status = "online";
  // Keep newest first for both the card preview and the modal list.
  cam.detections.unshift(d);
  state.detectionsById.set(d.detection_id, d);
  state.eventDetectionCount += 1;
  els.eventDetCount.textContent =
    `${state.eventDetectionCount} detection${state.eventDetectionCount === 1 ? "" : "s"}`;

  if (isLive) cam.flashing = true;

  // A new camera or detection always triggers a full grid redraw —
  // simpler than partial DOM updates and the grid stays fast (small N).
  if (isNewCamera || isLive) {
    renderGrid();
  } else {
    // Backfill (silent): only refresh the affected card.
    rerenderActiveCard(cam.camera_id);
  }

  // Refresh modal if the user is looking at this camera.
  if (state.openTarget === `cam:${cam.camera_id}`) {
    renderActiveCameraModalBody(cam);
    updateModalHeaderForCamera(cam);
  }
}

/* Status comes from the server (camera_status WS message) plus a local
   freshness check on lastSeenAt. Order of precedence:
     - "completed"  : worker hit EOF — show the indicator until reset.
     - "online"+fresh detection (<30s) : live
     - "online" but stale (>=30s)      : idle
     - anything else (or never seen)   : offline */
function statusClass(cam) {
  if (cam.status === "completed") return "completed";
  const fresh = cam.lastSeenAt &&
                (Date.now() - cam.lastSeenAt.getTime()) / 1000 < 30;
  if (cam.status === "online" || cam.status === "live") {
    return fresh ? "online" : "idle";
  }
  if (fresh) return "online";
  return "offline";
}
function statusLabel(cam) {
  const cls = statusClass(cam);
  if (cls === "completed") return "completed";
  if (cls === "online")    return "online";
  if (cls === "idle")      return "idle";
  return "offline";
}

function classDot(name) {
  const safe = CLASS_NAMES.includes(name) ? name : "";
  return `<span class="dot-class cls-${safe}"></span>`;
}

/* ---------- card markup (active vs empty share the same structure) ---------- */
function activeCardHTML(cam, slot) {
  const latest = cam.detections[0];
  const total = cam.detections.length;
  const banner = latest ? `
    <div class="cam-banner">
      <div>${classDot(latest.class_name)} Detected ${escapeHtml(latest.class_name)} ${fmtConf(latest.confidence)}</div>
      <div class="banner-time">${fmtTime(latest.timestamp)}</div>
    </div>` : `
    <div class="cam-banner empty">No detection yet for this camera.</div>`;

  // LEFT thumb = the camera's latest raw frame (always shown when we
  // have one, regardless of detections). Falls back to the annotated
  // detection frame, then the placeholder.
  // RIGHT thumb = the latest detection's crop, only when a detection exists.
  const frameSrc = cam.last_frame_url || (latest && latest.frame_path) || null;
  const cropSrc  = (latest && latest.crop_path) || null;
  const thumbs = `
    <div class="cam-thumbs">
      ${frameSrc
          ? `<img src="${escapeHtml(frameSrc)}" alt="last frame"/>`
          : `<div class="placeholder">no frame</div>`}
      ${cropSrc
          ? `<img src="${escapeHtml(cropSrc)}" alt="crop"/>`
          : `<div class="placeholder">no crop</div>`}
    </div>`;

  const meta = `
    <div class="cam-meta">
      <div><b>Class:</b> ${latest
        ? `<span class="cell-class">${classDot(latest.class_name)}${escapeHtml(latest.class_name)}</span>`
        : "—"}</div>
      <div><b>Confidence:</b> ${latest ? fmtConf(latest.confidence) : "—"}</div>
      <div><b>Time:</b> ${latest ? fmtTime(latest.timestamp) : "—"}</div>
      <div><b>Detections:</b> ${total}</div>
    </div>`;

  return `
    <div class="cam-head">
      <div>
        <div class="cam-name">${escapeHtml(cam.camera_name)}</div>
        <div class="cam-id">slot ${slot} · ${escapeHtml(cam.camera_id)}</div>
      </div>
      <div class="cam-status ${statusClass(cam)}">
        <span class="dot"></span>${statusLabel(cam)}
      </div>
    </div>
    ${banner}
    ${thumbs}
    ${meta}
    <div class="cam-foot">
      <span class="placeholder-tag">location: TBD</span>
      <span class="placeholder-tag">team: TBD</span>
    </div>
  `;
}

function emptySlotHTML(slot) {
  return `
    <div class="cam-head">
      <div>
        <div class="cam-name">Slot ${slot}</div>
        <div class="cam-id">— no camera assigned —</div>
      </div>
      <div class="cam-status offline">
        <span class="dot"></span>offline
      </div>
    </div>
    <div class="cam-banner empty">No input — slot is empty.</div>
    <div class="cam-thumbs">
      <div class="placeholder">no frame</div>
      <div class="placeholder">no crop</div>
    </div>
    <div class="cam-meta">
      <div><b>Class:</b> —</div>
      <div><b>Confidence:</b> —</div>
      <div><b>Time:</b> —</div>
      <div><b>Detections:</b> 0</div>
    </div>
    <div class="cam-foot">
      <span class="placeholder-tag">location: TBD</span>
      <span class="placeholder-tag">team: TBD</span>
    </div>
  `;
}

/* ---------- whole-grid render ----------
   Cameras render into their assigned `slot` (1-based). Configured
   cameras keep stable positions across events. Empty slots remain
   visible and clickable. The grid grows in rows of 4 if more cameras
   appear than DASHBOARD_SLOTS. */
function renderGrid() {
  els.grid.innerHTML = "";
  const cams = Array.from(state.cameras.values());
  // Build slot -> camera map. If two cameras claimed the same slot
  // (shouldn't happen), the later one wins.
  const bySlot = new Map();
  let maxSlot = 0;
  for (const c of cams) {
    if (typeof c.slot === "number" && c.slot > 0) {
      bySlot.set(c.slot, c);
      if (c.slot > maxSlot) maxSlot = c.slot;
    }
  }
  const minSlots = state.dashboardSlots;
  const total    = Math.max(maxSlot, minSlots);

  for (let slot = 1; slot <= total; slot++) {
    const cam = bySlot.get(slot);
    const card = document.createElement("div");
    card.dataset.slot = slot;
    if (cam) {
      card.className = "cam-card";
      card.dataset.cid = cam.camera_id;
      card.innerHTML = activeCardHTML(cam, slot);
      card.addEventListener("click", () => openCameraModal(cam.camera_id));
      if (cam.flashing) {
        card.classList.add("flash");
        cam.flashing = false;
        setTimeout(() => card.classList.remove("flash"), 2500);
      }
    } else {
      card.className = "cam-card empty-slot";
      card.innerHTML = emptySlotHTML(slot);
      card.addEventListener("click", () => openSlotModal(slot));
    }
    els.grid.appendChild(card);
  }
  refreshGridChrome();
}

function rerenderActiveCard(camera_id) {
  const cam = state.cameras.get(camera_id);
  if (!cam) return;
  const card = els.grid.querySelector(`[data-cid="${cssEscape(camera_id)}"]`);
  if (!card) { renderGrid(); return; }
  card.innerHTML = activeCardHTML(cam, cam.slot || 0);
}

function refreshGridChrome() {
  const n = state.cameras.size;
  els.cameraCount.textContent = `${n} camera${n === 1 ? "" : "s"}`;
}

/* CSS.escape polyfill for older browsers */
function cssEscape(s) {
  return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/[^a-zA-Z0-9_-]/g, "\\$&");
}

/* ---------------- per-camera modal (active) ---------------- */
function camSlotNumber(camera_id) {
  const cams = Array.from(state.cameras.keys());
  const i = cams.indexOf(camera_id);
  return i < 0 ? null : i + 1;
}

function updateModalHeaderForCamera(cam) {
  const slot = camSlotNumber(cam.camera_id);
  els.modalTitle.textContent = cam.camera_name;
  const det = cam.detections.length;
  els.modalSubtitle.textContent =
    `slot ${slot} · ${cam.camera_id} · ${det} detection${det === 1 ? "" : "s"} in this event`;
}

function openCameraModal(camera_id) {
  const cam = state.cameras.get(camera_id);
  if (!cam) return;
  state.openTarget = `cam:${camera_id}`;
  updateModalHeaderForCamera(cam);
  renderActiveCameraModalBody(cam);
  els.modal.classList.remove("hidden");
}

function closeCameraModal() {
  state.openTarget = null;
  els.modal.classList.add("hidden");
}

function renderActiveCameraModalBody(cam) {
  const slot = camSlotNumber(cam.camera_id);
  const evId = state.activeEvent ? state.activeEvent.event_id : "— no active event —";
  const latest = cam.detections[0];
  const latestText = latest
    ? `${latest.class_name} ${fmtConf(latest.confidence)} @ ${fmtTime(latest.timestamp)}`
    : "none";

  const slotMeta = `
    <div class="slot-meta">
      <div><b>Slot:</b> ${slot}</div>
      <div><b>Status:</b> ${statusLabel(cam)}</div>
      <div><b>camera_id:</b> ${escapeHtml(cam.camera_id)}</div>
      <div><b>camera_name:</b> ${escapeHtml(cam.camera_name)}</div>
      <div><b>Detections:</b> ${cam.detections.length}</div>
      <div><b>Latest alert:</b> ${escapeHtml(latestText)}</div>
      <div><b>Location (GPS):</b> TBD</div>
      <div><b>Event ID:</b> ${escapeHtml(evId)}</div>
    </div>`;

  if (!cam.detections.length) {
    els.modalBody.innerHTML =
      slotMeta +
      `<div class="slot-empty-list">No detections received for this camera slot yet.</div>`;
    return;
  }

  const items = cam.detections.map(detRowHTML).join("");
  els.modalBody.innerHTML = slotMeta + `<ul class="det-list">${items}</ul>`;

  for (const btn of els.modalBody.querySelectorAll(".btn-validate")) {
    btn.addEventListener("click", (ev) => {
      ev.stopPropagation();
      validateDetection(btn.dataset.id, btn.dataset.status);
    });
  }
  for (const img of els.modalBody.querySelectorAll("img.thumb")) {
    img.addEventListener("click", (ev) => {
      ev.stopPropagation();
      const camId = state.openTarget && state.openTarget.startsWith("cam:")
        ? state.openTarget.slice(4) : null;
      if (!camId) return;
      openLightboxForDetection(camId, img.dataset.id, img.dataset.type);
    });
  }
}

/* ---------------- empty/offline slot modal ---------------- */
function openSlotModal(slot) {
  state.openTarget = `slot:${slot}`;
  const evId = state.activeEvent
    ? state.activeEvent.event_id
    : "— no active event —";

  els.modalTitle.textContent = `Slot ${slot}`;
  els.modalSubtitle.textContent = "— no camera assigned —";
  els.modalBody.innerHTML = `
    <div class="slot-meta">
      <div><b>Slot:</b> ${slot}</div>
      <div><b>Status:</b> offline / no input</div>
      <div><b>camera_id:</b> not assigned</div>
      <div><b>camera_name:</b> not assigned</div>
      <div><b>Detections:</b> 0</div>
      <div><b>Latest alert:</b> none</div>
      <div><b>Location (GPS):</b> TBD</div>
      <div><b>Event ID:</b> ${escapeHtml(evId)}</div>
    </div>
    <div class="slot-empty-list">No detections received for this camera slot yet.</div>
  `;
  els.modal.classList.remove("hidden");
}

function detRowHTML(d) {
  const v = d.validation_status || "";
  const badge = v
    ? `<span class="validation-badge ${v}">${v.toUpperCase()}</span>`
    : "";

  const buttons = ["correct", "incorrect", "unsure"].map(s => {
    const active = s === v ? "active" : "";
    const label = s.charAt(0).toUpperCase() + s.slice(1);
    return `<button class="btn-validate ${s} ${active}"
                    data-id="${escapeHtml(d.detection_id)}"
                    data-status="${s}">${label}</button>`;
  }).join("");

  const frame = d.frame_path
    ? `<img class="thumb" src="${escapeHtml(d.frame_path)}"
            data-id="${escapeHtml(d.detection_id)}" data-type="frame"
            alt="frame"/>`
    : `<div class="placeholder-img">no frame</div>`;
  const crop = d.crop_path
    ? `<img class="thumb" src="${escapeHtml(d.crop_path)}"
            data-id="${escapeHtml(d.detection_id)}" data-type="crop"
            alt="crop"/>`
    : `<div class="placeholder-img">no crop</div>`;

  return `
    <li class="det-row" data-id="${escapeHtml(d.detection_id)}">
      ${frame}
      ${crop}
      <div class="det-info">
        <div class="det-line">
          <span class="det-class">${classDot(d.class_name)}${escapeHtml(d.class_name)}</span>
          <span class="det-conf">${fmtConf(d.confidence)}</span>
          ${badge}
        </div>
        <div class="det-line"><b>Time:</b> ${fmtTime(d.timestamp)} <span class="small">${fmtDate(d.timestamp)}</span></div>
        <div class="det-line"><b>Camera:</b> ${escapeHtml(d.camera_name || d.camera_id)} <span class="small">${escapeHtml(d.camera_id)}</span></div>
        <div class="det-line"><b>Location (GPS):</b> <span class="small">— TBD —</span></div>
        <div class="det-validation">${buttons}</div>
      </div>
    </li>`;
}

async function validateDetection(detection_id, status) {
  try {
    const r = await fetch(`/dashboard/api/detections/${encodeURIComponent(detection_id)}/validate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    });
    if (!r.ok) {
      const txt = await r.text().catch(() => "");
      alert(`Validation failed: ${r.status} ${txt}`);
      return;
    }
    const j = await r.json();
    applyValidation(j);   // optimistic – WS will echo too
  } catch (e) {
    alert("Validation request failed: " + e.message);
  }
}

function applyValidation({ detection_id, validation_status, validation_note, validated_at }) {
  const det = state.detectionsById.get(detection_id);
  if (det) {
    det.validation_status = validation_status;
    det.validation_note   = validation_note;
    det.validated_at      = validated_at;
  }
  // Refresh the underlying camera modal if it's open for this detection.
  if (state.openTarget && state.openTarget.startsWith("cam:")) {
    const cid = state.openTarget.slice(4);
    const cam = state.cameras.get(cid);
    if (cam && cam.detections.some(d => d.detection_id === detection_id)) {
      renderActiveCameraModalBody(cam);
    }
  }
  // And the lightbox if it's showing this detection.
  if (state.lightbox &&
      state.lightbox.detections.some(d => d.detection_id === detection_id)) {
    renderLightbox();
  }
}

/* ---------------- lightbox (browse + validate) ----------------
   The operator clicks any frame/crop in the camera modal, which opens
   the lightbox positioned on that detection. Prev/Next (buttons or
   arrow keys) walks through the camera's detections in this event.
   The same Correct / Incorrect / Unsure buttons live inside the
   lightbox and call the existing validation API. */

function openLightboxForDetection(camera_id, detection_id, imageType) {
  const cam = state.cameras.get(camera_id);
  if (!cam || !cam.detections.length) return;
  // Snapshot the list of detection refs so the index stays stable while
  // browsing. Validation mutates the underlying objects in place, so
  // status updates still flow through.
  const detections = cam.detections.slice();
  const index = Math.max(0, detections.findIndex(d => d.detection_id === detection_id));
  state.lightbox = {
    camera_id,
    detections,
    index,
    imageType: (imageType === "crop") ? "crop" : "frame",
  };
  els.lb.classList.remove("hidden");
  renderLightbox();
}

function closeLightbox() {
  state.lightbox = null;
  els.lb.classList.add("hidden");
}

function lightboxStep(delta) {
  const lb = state.lightbox;
  if (!lb) return;
  const n = lb.detections.length;
  lb.index = Math.max(0, Math.min(n - 1, lb.index + delta));
  renderLightbox();
}

function renderLightbox() {
  const lb = state.lightbox;
  if (!lb) return;
  const det = lb.detections[lb.index];
  if (!det) { closeLightbox(); return; }

  // Pick the requested image, gracefully fall back if it's missing.
  let url, shown;
  if (lb.imageType === "crop") {
    url = det.crop_path || det.frame_path;
    shown = det.crop_path ? "crop" : (det.frame_path ? "frame" : null);
  } else {
    url = det.frame_path || det.crop_path;
    shown = det.frame_path ? "frame" : (det.crop_path ? "crop" : null);
  }
  els.lbImage.src = url || "";
  els.lbImage.style.visibility = url ? "visible" : "hidden";
  els.lbHint.textContent = shown ? `showing ${shown}` : "no image available";

  els.lbCounter.textContent =
    `Detection ${lb.index + 1} of ${lb.detections.length}`;

  const v = det.validation_status || "";
  const badge = v
    ? `<span class="validation-badge ${v}">${v.toUpperCase()}</span>`
    : "";

  els.lbMeta.innerHTML = `
    <span><b>Camera:</b> ${escapeHtml(det.camera_name || det.camera_id)}
          <span class="small">${escapeHtml(det.camera_id)}</span></span>
    <span><b>Class:</b>
      <span class="det-class">${classDot(det.class_name)}${escapeHtml(det.class_name)}</span></span>
    <span><b>Confidence:</b> ${fmtConf(det.confidence)}</span>
    <span><b>Time:</b> ${fmtTime(det.timestamp)} <span class="small">${fmtDate(det.timestamp)}</span></span>
    ${badge}
  `;

  for (const btn of els.lbValidation.querySelectorAll(".btn-validate")) {
    const status = btn.dataset.status;
    btn.classList.toggle("active", status === v);
  }

  els.lbArrowPrev.disabled = lb.index === 0;
  els.lbArrowNext.disabled = lb.index === lb.detections.length - 1;
  els.lbPrev.disabled       = lb.index === 0;
  els.lbNext.disabled       = lb.index === lb.detections.length - 1;
}

/* Validate the detection currently shown in the lightbox.
   Reuses the existing validateDetection() -> /api/detections/{id}/validate
   path, so persistence + WS broadcast behavior is identical. */
function lightboxValidate(status) {
  const lb = state.lightbox;
  if (!lb) return;
  const det = lb.detections[lb.index];
  validateDetection(det.detection_id, status);
}

/* ---------------- WebSocket ---------------- */
function connectWS() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(`${proto}//${location.host}/dashboard/ws`);

  ws.addEventListener("open", () => {
    els.wsStatus.textContent = "● live";
    els.wsStatus.classList.add("connected");
    els.wsStatus.classList.remove("disconnected");
  });
  ws.addEventListener("close", () => {
    els.wsStatus.textContent = "● disconnected";
    els.wsStatus.classList.add("disconnected");
    els.wsStatus.classList.remove("connected");
    setTimeout(connectWS, 1500);
  });
  ws.addEventListener("error", () => ws.close());
  ws.addEventListener("message", (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); }
    catch { return; }

    switch (msg.type) {
      case "hello": {
        // Backfill: configured cameras + active event + recent detections.
        state.activeEvent = msg.data.active_event || null;
        state.cameras.clear();
        state.detectionsById.clear();
        state.eventDetectionCount = 0;
        // Render configured cameras FIRST so even with zero detections
        // they appear in their assigned slots.
        applyCameraList(msg.data.cameras || []);
        const recent = (msg.data.recent || []).slice().reverse();  // oldest first
        for (const d of recent) ingestDetection(normaliseDb(d), false);
        renderEvent();
        renderGrid();
        break;
      }
      case "detection":
        ingestDetection(msg.data, true);
        break;
      case "validation":
        applyValidation(msg.data);
        break;
      case "camera_status":
        applyCameraStatus(msg.data);
        break;
      case "frame_update":
        applyFrameUpdate(msg.data);
        break;
      case "event_started":
        onEventStarted(msg.data);
        break;
      case "event_closed":
        onEventClosed(msg.data);
        break;
    }
  });
}

function normaliseDb(row) {
  // SQLite rows already match the live shape — just guard defaults.
  return {
    detection_id: row.detection_id,
    camera_id:    row.camera_id,
    camera_name:  row.camera_name,
    class_id:     row.class_id,
    class_name:   row.class_name,
    confidence:   row.confidence,
    bbox:         row.bbox || { x1: 0, y1: 0, x2: 0, y2: 0 },
    timestamp:    row.timestamp,
    frame_path:   row.frame_path,
    crop_path:    row.crop_path,
    event_id:     row.event_id,
    validation_status: row.validation_status || null,
    validation_note:   row.validation_note   || null,
    validated_at:      row.validated_at      || null,
  };
}

/* ---------------- wire up controls ---------------- */
els.btnStart.addEventListener("click", () => {
  els.eventNameInput.value = "";
  els.eventModal.classList.remove("hidden");
  setTimeout(() => els.eventNameInput.focus(), 50);
});
els.btnClose.addEventListener("click", closeEvent);
els.btnConfirmStart.addEventListener("click", () => {
  els.eventModal.classList.add("hidden");
  startEvent(els.eventNameInput.value.trim());
});
els.eventModal.addEventListener("click", (e) => {
  if (e.target.dataset.closeEvent) els.eventModal.classList.add("hidden");
});
els.eventNameInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    els.btnConfirmStart.click();
  } else if (e.key === "Escape") {
    els.eventModal.classList.add("hidden");
  }
});

els.modal.addEventListener("click", (e) => {
  if (e.target.dataset.close) closeCameraModal();
});

/* Lightbox wiring */
els.lb.addEventListener("click", (e) => {
  if (e.target.dataset.lbClose) closeLightbox();
});
els.lbArrowPrev.addEventListener("click", () => lightboxStep(-1));
els.lbArrowNext.addEventListener("click", () => lightboxStep(1));
els.lbPrev.addEventListener("click",       () => lightboxStep(-1));
els.lbNext.addEventListener("click",       () => lightboxStep(1));
for (const btn of els.lbValidation.querySelectorAll(".btn-validate")) {
  btn.addEventListener("click", (ev) => {
    ev.stopPropagation();
    lightboxValidate(btn.dataset.status);
  });
}

document.addEventListener("keydown", (e) => {
  const lbOpen = state.lightbox !== null;
  if (e.key === "Escape") {
    if (lbOpen) {
      closeLightbox();
    } else {
      closeCameraModal();
      els.eventModal.classList.add("hidden");
    }
    return;
  }
  if (lbOpen) {
    if (e.key === "ArrowLeft")  { e.preventDefault(); lightboxStep(-1); }
    if (e.key === "ArrowRight") { e.preventDefault(); lightboxStep(1);  }
  }
});

/* Refresh online/idle/offline indicators every 10s. We touch only
   the per-card status element so we don't fight modal interactions. */
setInterval(() => {
  for (const cam of state.cameras.values()) {
    const card = els.grid.querySelector(`[data-cid="${cssEscape(cam.camera_id)}"]`);
    if (!card) continue;
    const status = card.querySelector(".cam-status");
    if (!status) continue;
    status.className = `cam-status ${statusClass(cam)}`;
    // The status element looks like:  <span class="dot"></span>label
    const text = status.childNodes[status.childNodes.length - 1];
    if (text && text.nodeType === Node.TEXT_NODE) {
      text.textContent = statusLabel(cam);
    }
  }
}, 10000);

/* ---------------- bootstrap ---------------- */
loadInfo();
loadCameras();
connectWS();
renderEvent();
renderGrid();
