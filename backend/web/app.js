const state = {
  workflow: "IMAGE",
  mode: "IMAGE",
  sourceFile: null,
  faceFile: null,
  faceFiles: [],
  detectedFaces: [],
  analyzingFaces: false,
  currentProjectId: null,
  objectUrls: [],
  polling: null
};

const workflows = {
  IMAGE: {
    mediaType: "IMAGE",
    accept: "image/jpeg,image/png,image/webp",
    sourceLabel: "Original image",
    multiFace: false
  },
  MULTI: {
    mediaType: "IMAGE",
    accept: "image/jpeg,image/png,image/webp",
    sourceLabel: "Original image",
    multiFace: true
  },
  VIDEO: {
    mediaType: "VIDEO",
    accept: "video/mp4,video/quicktime,video/x-matroska,.mp4,.mov,.mkv",
    sourceLabel: "Original video",
    multiFace: false
  },
  GIF: {
    mediaType: "GIF",
    accept: "image/gif,.gif",
    sourceLabel: "Original GIF",
    multiFace: false
  }
};

const els = {
  healthDot: document.getElementById("healthDot"),
  healthText: document.getElementById("healthText"),
  modelNotice: document.getElementById("modelNotice"),
  modeButtons: [...document.querySelectorAll(".mode")],
  sourceDrop: document.getElementById("sourceDrop"),
  faceDrop: document.getElementById("faceDrop"),
  sourceInput: document.getElementById("sourceInput"),
  faceInput: document.getElementById("faceInput"),
  sourceName: document.getElementById("sourceName"),
  faceName: document.getElementById("faceName"),
  sourcePreview: document.getElementById("sourcePreview"),
  facePreview: document.getElementById("facePreview"),
  multiFacePanel: document.getElementById("multiFacePanel"),
  faceMappingRows: document.getElementById("faceMappingRows"),
  addReplacementButton: document.getElementById("addReplacementButton"),
  extraFaceInput: document.getElementById("extraFaceInput"),
  resultPreview: document.getElementById("resultPreview"),
  qualityInput: document.getElementById("qualityInput"),
  imageFormatField: document.getElementById("imageFormatField"),
  imageFormatInput: document.getElementById("imageFormatInput"),
  videoResolutionField: document.getElementById("videoResolutionField"),
  videoResolutionInput: document.getElementById("videoResolutionInput"),
  fpsField: document.getElementById("fpsField"),
  fpsInput: document.getElementById("fpsInput"),
  trimStartField: document.getElementById("trimStartField"),
  trimStartInput: document.getElementById("trimStartInput"),
  trimEndField: document.getElementById("trimEndField"),
  trimEndInput: document.getElementById("trimEndInput"),
  multiFaceInput: document.getElementById("multiFaceInput"),
  visibleWatermarkInput: document.getElementById("visibleWatermarkInput"),
  metadataWatermarkInput: document.getElementById("metadataWatermarkInput"),
  tokenInput: document.getElementById("tokenInput"),
  consentInput: document.getElementById("consentInput"),
  startButton: document.getElementById("startButton"),
  cancelButton: document.getElementById("cancelButton"),
  downloadLink: document.getElementById("downloadLink"),
  progressBar: document.getElementById("progressBar"),
  progressValue: document.getElementById("progressValue"),
  statusText: document.getElementById("statusText")
};

const API_BASE = window.location.protocol === "file:" ? "http://127.0.0.1:8000" : "";

function apiUrl(path) {
  return `${API_BASE}${path}`;
}

function setMode(workflow) {
  const config = workflows[workflow] || workflows.IMAGE;
  state.workflow = workflow;
  state.mode = config.mediaType;
  document.body.dataset.workflow = workflow;
  els.modeButtons.forEach((button) => button.classList.toggle("active", button.dataset.mode === workflow));
  els.sourceInput.accept = config.accept;
  els.sourceDrop.querySelector(".drop-title").textContent = config.sourceLabel;
  els.imageFormatField.hidden = state.mode !== "IMAGE";
  els.videoResolutionField.hidden = state.mode !== "VIDEO";
  els.fpsField.hidden = state.mode !== "VIDEO";
  els.trimStartField.hidden = state.mode !== "VIDEO";
  els.trimEndField.hidden = state.mode !== "VIDEO";
  els.multiFaceInput.checked = config.multiFace;
  els.multiFaceInput.disabled = workflow === "MULTI";
  updateStartButtonText();
  updateMultiFacePanel();
  if (state.sourceFile) {
    state.sourceFile = null;
    state.detectedFaces = [];
    els.sourceInput.value = "";
    els.sourceName.textContent = "No file selected";
    clearSlot(els.sourcePreview);
    renderFaceMappingRows();
  }
  clearResult();
}

function clearSlot(slot) {
  slot.classList.remove("has-media");
  slot.replaceChildren();
}

function clearResult() {
  clearSlot(els.resultPreview);
  els.downloadLink.classList.add("disabled");
  els.downloadLink.removeAttribute("href");
}

function updateStartButtonText() {
  els.startButton.textContent = els.multiFaceInput.checked ? "Start Multi-Face Swapping" : "Start processing";
}

function rememberUrl(url) {
  state.objectUrls.push(url);
  return url;
}

function renderFilePreview(slot, file, mediaType) {
  clearSlot(slot);
  const url = rememberUrl(URL.createObjectURL(file));
  const element = mediaType === "VIDEO" ? document.createElement("video") : document.createElement("img");
  element.src = url;
  if (mediaType === "VIDEO") {
    element.controls = true;
    element.muted = true;
    element.playsInline = true;
  }
  slot.classList.add("has-media");
  slot.append(element);
}

function renderRemoteResult(url) {
  clearSlot(els.resultPreview);
  const element = state.mode === "VIDEO" ? document.createElement("video") : document.createElement("img");
  element.src = url;
  if (state.mode === "VIDEO") {
    element.controls = true;
    element.playsInline = true;
  }
  els.resultPreview.classList.add("has-media");
  els.resultPreview.append(element);
  els.downloadLink.href = url;
  els.downloadLink.download = `ai_face_result.${state.mode === "VIDEO" ? "mp4" : state.mode === "GIF" ? "gif" : "jpg"}`;
  els.downloadLink.classList.remove("disabled");
}

function setProgress(progress, message) {
  const value = Math.max(0, Math.min(100, Number(progress) || 0));
  els.progressBar.style.width = `${value}%`;
  els.progressValue.textContent = `${value}%`;
  els.statusText.textContent = message || "Processing";
}

function headers() {
  const token = els.tokenInput.value.trim();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function requestPayload(projectId) {
  return {
    projectId,
    mediaType: state.mode,
    imageFormat: els.imageFormatInput.value,
    quality: els.qualityInput.value,
    videoResolution: els.videoResolutionInput.value,
    fps: Number(els.fpsInput.value),
    multiFace: els.multiFaceInput.checked,
    faceMappings: buildFaceMappings(),
    videoTrimStartMs: secondsToMs(els.trimStartInput.value),
    videoTrimEndMs: secondsToMs(els.trimEndInput.value),
    visibleWatermark: els.visibleWatermarkInput.checked,
    metadataWatermark: els.metadataWatermarkInput.checked,
    userOwnershipDeclared: els.consentInput.checked,
    termsAccepted: els.consentInput.checked
  };
}

function projectId() {
  const random = crypto.getRandomValues(new Uint32Array(2));
  return `web-${Date.now().toString(36)}-${random[0].toString(36)}${random[1].toString(36)}`;
}

function secondsToMs(value) {
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return 0;
  return Math.round(seconds * 1000);
}

function buildFaceMappings() {
  if (!els.multiFaceInput.checked) return [];
  const sourceCount = state.detectedFaces.length || state.faceFiles.length;
  const mappings = [];
  let replacementIndex = 0;
  for (let sourceFaceIndex = 0; sourceFaceIndex < Math.min(sourceCount, 12); sourceFaceIndex += 1) {
    if (state.faceFiles[sourceFaceIndex]) {
      mappings.push({ sourceFaceIndex, replacementIndex });
      replacementIndex += 1;
    }
  }
  return mappings;
}

function selectedFaceFiles() {
  return state.faceFiles.filter(Boolean).slice(0, 12);
}

async function startProcessing() {
  const uploadFaces = selectedFaceFiles();
  if (!state.sourceFile || uploadFaces.length === 0) {
    setProgress(0, "Select both files");
    return;
  }
  if (
    els.multiFaceInput.checked &&
    state.detectedFaces.length > 0 &&
    state.detectedFaces.some((_, index) => !state.faceFiles[index])
  ) {
    setProgress(0, "Add one replacement face for each detected source face");
    return;
  }
  if (!els.consentInput.checked) {
    setProgress(0, "Consent is required");
    return;
  }
  const trimStart = secondsToMs(els.trimStartInput.value);
  const trimEnd = secondsToMs(els.trimEndInput.value);
  if (state.mode === "VIDEO" && trimEnd > 0 && trimEnd <= trimStart) {
    setProgress(0, "End time must be after start time");
    return;
  }

  clearResult();
  els.startButton.disabled = true;
  setProgress(2, "Uploading Files");

  const id = projectId();
  state.currentProjectId = id;
  els.cancelButton.disabled = false;
  const form = new FormData();
  form.append("source", state.sourceFile, state.sourceFile.name);
  form.append("face", uploadFaces[0], uploadFaces[0].name);
  uploadFaces.slice(1).forEach((file) => {
    form.append("faces", file, file.name);
  });
  form.append("request", JSON.stringify(requestPayload(id)));

  try {
    const response = await fetch(apiUrl("/v1/projects"), {
      method: "POST",
      headers: headers(),
      body: form
    });
    const job = await parseJsonResponse(response);
    setProgress(job.progress, job.statusMessage);
    await pollJob(job.projectId);
  } catch (error) {
    setProgress(0, error.message || "Processing failed");
  } finally {
    els.startButton.disabled = false;
    if (!state.polling) {
      els.cancelButton.disabled = true;
      state.currentProjectId = null;
    }
  }
}

async function pollJob(id) {
  clearInterval(state.polling);
  return new Promise((resolve, reject) => {
    state.polling = setInterval(async () => {
      try {
        const response = await fetch(apiUrl(`/v1/projects/${encodeURIComponent(id)}`), { headers: headers() });
        const job = await parseJsonResponse(response);
        setProgress(job.progress, job.statusMessage);
        if (job.status.toLowerCase() === "completed") {
          clearInterval(state.polling);
          state.polling = null;
          state.currentProjectId = null;
          els.cancelButton.disabled = true;
          setProgress(100, "Completed");
          renderRemoteResult(job.signedDownloadUrl || job.resultUrl);
          resolve(job);
        } else if (["failed", "canceled", "cancelled"].includes(job.status.toLowerCase())) {
          clearInterval(state.polling);
          state.polling = null;
          state.currentProjectId = null;
          els.cancelButton.disabled = true;
          reject(new Error(job.statusMessage || "Processing failed"));
        }
      } catch (error) {
        clearInterval(state.polling);
        state.polling = null;
        reject(error);
      }
    }, 1800);
  });
}

async function cancelProcessing() {
  if (!state.currentProjectId) {
    return;
  }
  els.cancelButton.disabled = true;
  setProgress(Number(els.progressValue.textContent.replace("%", "")) || 0, "Terminating processing");
  try {
    await fetch(apiUrl(`/v1/projects/${encodeURIComponent(state.currentProjectId)}/cancel`), {
      method: "POST",
      headers: headers()
    });
  } catch {
    setProgress(0, "Terminate request failed");
    els.cancelButton.disabled = false;
  }
}

async function parseJsonResponse(response) {
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { detail: text };
  }
  if (!response.ok) {
    throw new Error(data.detail || `HTTP ${response.status}`);
  }
  return data;
}

async function checkHealth() {
  try {
    const response = await fetch(apiUrl("/health"));
    const data = await parseJsonResponse(response);
    const neuralReady = Boolean(data.processor?.neuralAvailable);
    els.healthDot.className = `dot ${neuralReady ? "ok" : "bad"}`;
    els.healthText.textContent = neuralReady ? "Neural backend ready" : "Backend online; AI model missing";
    els.modelNotice.hidden = neuralReady;
    if (!neuralReady) {
      els.modelNotice.textContent = "The backend is online, but the neural face-swap model (inswapper_128.onnx) is not installed. Processing is unavailable until the model is provisioned. This Render Free instance is not downloading a large model automatically.";
      els.startButton.disabled = true;
    } else {
      els.modelNotice.textContent = "";
      els.startButton.disabled = false;
    }
  } catch {
    els.healthDot.className = "dot bad";
    els.healthText.textContent = "Backend offline";
    els.modelNotice.hidden = false;
    els.modelNotice.textContent = "Cannot reach the backend health endpoint. Try refreshing the page."; 
    els.startButton.disabled = true;
  }
}

function bindDrop(label, input, onFile) {
  input.addEventListener("change", () => {
    const file = input.files?.[0];
    if (file) onFile(file);
  });
  label.addEventListener("dragover", (event) => {
    event.preventDefault();
    label.classList.add("dragging");
  });
  label.addEventListener("dragleave", () => label.classList.remove("dragging"));
  label.addEventListener("drop", (event) => {
    event.preventDefault();
    label.classList.remove("dragging");
    const file = event.dataTransfer.files?.[0];
    if (file) {
      input.files = event.dataTransfer.files;
      onFile(file);
    }
  });
}

function updateMultiFacePanel() {
  const enabled = els.multiFaceInput.checked;
  els.multiFacePanel.hidden = !enabled;
  updateStartButtonText();
  if (enabled && state.sourceFile) {
    analyzeSourceFaces();
  }
  renderFaceMappingRows();
}

async function analyzeSourceFaces() {
  if (!els.multiFaceInput.checked || !state.sourceFile || state.analyzingFaces) {
    return;
  }
  state.analyzingFaces = true;
  renderFaceMappingRows("Detecting source faces");
  const form = new FormData();
  form.append("source", state.sourceFile, state.sourceFile.name);
  form.append("mediaType", state.mode);
  form.append("videoTrimStartMs", String(secondsToMs(els.trimStartInput.value)));
  try {
    const response = await fetch(apiUrl("/v1/faces/detect"), {
      method: "POST",
      headers: headers(),
      body: form
    });
    const data = await parseJsonResponse(response);
    state.detectedFaces = data.faces || [];
  } catch (error) {
    state.detectedFaces = [];
    setProgress(0, error.message || "Face detection failed");
  } finally {
    state.analyzingFaces = false;
    renderFaceMappingRows();
  }
}

function renderFaceMappingRows(message) {
  els.faceMappingRows.replaceChildren();
  if (!els.multiFaceInput.checked) return;
  if (!state.sourceFile) {
    const note = document.createElement("div");
    note.className = "replacement-name";
    note.textContent = "Upload the original media to detect faces.";
    els.faceMappingRows.append(note);
    return;
  }
  if (message) {
    const note = document.createElement("div");
    note.className = "replacement-name";
    note.textContent = message;
    els.faceMappingRows.append(note);
    return;
  }
  const rowCount = Math.max(state.detectedFaces.length, state.faceFiles.length, 1);
  for (let index = 0; index < Math.min(rowCount, 12); index += 1) {
    const row = document.createElement("div");
    row.className = "face-map-row";

    const source = document.createElement("img");
    source.className = "face-thumb";
    source.alt = "";
    if (state.detectedFaces[index]?.previewDataUrl) {
      source.src = state.detectedFaces[index].previewDataUrl;
    }

    const arrow = document.createElement("span");
    arrow.className = "map-arrow";
    arrow.textContent = ">";

    const picker = document.createElement("div");
    picker.className = "replacement-picker";
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = state.faceFiles[index] ? "Change face" : "Choose face";
    button.addEventListener("click", () => chooseReplacementForIndex(index));
    const name = document.createElement("span");
    name.className = "replacement-name";
    name.textContent = state.faceFiles[index]?.name || `Replacement ${index + 1}`;
    picker.append(button, name);

    let replacement;
    if (state.faceFiles[index]) {
      replacement = document.createElement("img");
      replacement.className = "replacement-thumb";
      replacement.alt = "";
      replacement.src = rememberUrl(URL.createObjectURL(state.faceFiles[index]));
    } else {
      replacement = document.createElement("button");
      replacement.className = "replacement-empty";
      replacement.type = "button";
      replacement.textContent = "+";
      replacement.addEventListener("click", () => chooseReplacementForIndex(index));
    }

    const remove = document.createElement("button");
    remove.className = "remove-face-button";
    remove.type = "button";
    remove.textContent = "x";
    remove.disabled = !state.faceFiles[index];
    remove.addEventListener("click", () => {
      state.faceFiles[index] = null;
      state.faceFile = selectedFaceFiles()[0] || null;
      updateFaceSummary();
      renderFaceMappingRows();
      clearResult();
    });

    row.append(source, arrow, picker, replacement, remove);
    els.faceMappingRows.append(row);
  }
}

function chooseReplacementForIndex(index) {
  const input = document.createElement("input");
  input.type = "file";
  input.accept = "image/jpeg,image/png,image/webp";
  input.addEventListener("change", () => {
    const file = input.files?.[0];
    if (!file) return;
    state.faceFiles[index] = file;
    state.faceFile = selectedFaceFiles()[0] || null;
    updateFaceSummary();
    renderFaceMappingRows();
    clearResult();
  });
  input.click();
}

function addReplacementFiles(files) {
  const incoming = [...files].slice(0, 12 - state.faceFiles.length);
  state.faceFiles.push(...incoming);
  state.faceFile = selectedFaceFiles()[0] || null;
  updateFaceSummary();
  renderFaceMappingRows();
  clearResult();
}

function updateFaceSummary() {
  const files = selectedFaceFiles();
  const count = files.length;
  if (count === 0) {
    els.faceName.textContent = "No file selected";
    clearSlot(els.facePreview);
    return;
  }
  els.faceName.textContent = count === 1 ? files[0].name : `${count} face images selected`;
  renderFilePreview(els.facePreview, files[0], "IMAGE");
}

els.modeButtons.forEach((button) => button.addEventListener("click", () => setMode(button.dataset.mode)));
bindDrop(els.sourceDrop, els.sourceInput, (file) => {
  state.sourceFile = file;
  els.sourceName.textContent = file.name;
  renderFilePreview(els.sourcePreview, file, state.mode);
  if (state.mode === "VIDEO") {
    readVideoDuration(file);
  }
  if (els.multiFaceInput.checked) {
    analyzeSourceFaces();
  }
  clearResult();
});
bindDrop(els.faceDrop, els.faceInput, (file) => {
  state.faceFile = file;
  state.faceFiles = [file];
  updateFaceSummary();
  renderFaceMappingRows();
  clearResult();
});
els.multiFaceInput.addEventListener("change", updateMultiFacePanel);
els.addReplacementButton.addEventListener("click", () => els.extraFaceInput.click());
els.extraFaceInput.addEventListener("change", () => {
  if (els.extraFaceInput.files?.length) {
    addReplacementFiles(els.extraFaceInput.files);
    els.extraFaceInput.value = "";
  }
});
els.trimStartInput.addEventListener("change", () => {
  state.detectedFaces = [];
  analyzeSourceFaces();
});
els.startButton.addEventListener("click", startProcessing);
els.cancelButton.addEventListener("click", cancelProcessing);

function readVideoDuration(file) {
  const video = document.createElement("video");
  const url = rememberUrl(URL.createObjectURL(file));
  video.preload = "metadata";
  video.src = url;
  video.onloadedmetadata = () => {
    if (Number.isFinite(video.duration) && video.duration > 0) {
      const duration = Math.round(video.duration * 10) / 10;
      els.trimStartInput.value = "0";
      els.trimEndInput.value = String(duration);
      els.trimStartInput.max = String(duration);
      els.trimEndInput.max = String(duration);
      els.trimEndInput.placeholder = `Full video (${duration}s)`;
      if (els.multiFaceInput.checked) {
        analyzeSourceFaces();
      }
    }
  };
}

window.addEventListener("beforeunload", () => {
  state.objectUrls.forEach((url) => URL.revokeObjectURL(url));
});

setMode("IMAGE");
checkHealth();
