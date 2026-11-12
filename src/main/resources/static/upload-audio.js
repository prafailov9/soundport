const fileInput = document.getElementById("file-input");
const drop = document.getElementById("drop");
const fileNameEl = document.getElementById("file-name");
const fileMetaEl = document.getElementById("file-meta");
const uploadBtn = document.getElementById("upload-btn");
const progressEl = document.getElementById("progress");
const progressBar = progressEl.firstElementChild;
const statusEl = document.getElementById("status");
const resultEl = document.getElementById("result");
const resultText = document.getElementById("result-text");
const copyBtn = document.getElementById("copy-btn");

const POLL_MS = 1500; // how often to ask the server how the transcription is going

let file = null;

// ---- choosing a file -------------------------------------------------------

fileInput.onchange = () => choose(fileInput.files[0]);

// Drag and drop, for the desktop
drop.ondragover = e => { e.preventDefault(); drop.classList.add("over"); };
drop.ondragleave = () => drop.classList.remove("over");
drop.ondrop = e => {
  e.preventDefault();
  drop.classList.remove("over");
  choose(e.dataTransfer.files[0]);
};

function choose(f) {
  if (!f) return;
  file = f;
  fileNameEl.textContent = f.name;
  fileMetaEl.textContent = `${formatSize(f.size)} · ${f.type || "unknown type"}`;
  uploadBtn.disabled = false;
  setStatus("");
  resultEl.classList.remove("shown");
}

function formatSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function setStatus(text, isError = false) {
  statusEl.textContent = text;
  statusEl.classList.toggle("error", isError);
}

// ---- uploading -------------------------------------------------------------

uploadBtn.onclick = upload;

function upload() {
  if (!file) return;

  const formData = new FormData();
  formData.append("file", file);

  // XMLHttpRequest instead of fetch: only it reports upload progress
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/upload-audio");

  xhr.upload.onprogress = e => {
    if (!e.lengthComputable) return;
    const pct = Math.round((e.loaded / e.total) * 100);
    progressBar.style.width = pct + "%";
    setStatus(pct < 100 ? `Uploading… ${pct}%` : "Uploaded, waiting for the server…");
  };
  xhr.onload = () => {
    progressEl.classList.remove("active");
    if (xhr.status === 202) {
      // the server queued it: ask about the job until it's done
      pollJob(JSON.parse(xhr.responseText).id);
    } else {
      done();
      setStatus(`Upload failed (${xhr.status})${xhr.responseText ? ": " + xhr.responseText : ""}`, true);
    }
  };
  xhr.onerror = () => {
    done();
    setStatus("Upload failed: can't reach the server", true);
  };

  uploadBtn.disabled = true;
  resultEl.classList.remove("shown");
  progressBar.style.width = "0";
  progressEl.classList.add("active");
  setStatus("Uploading…");
  // No Content-Type header: the browser sets multipart/form-data with its boundary
  xhr.send(formData);
}

// ---- waiting for the transcript ----------------------------------------------

// The button stays disabled until the job finishes, so one upload never starts two of these
async function pollJob(id) {
  let job;
  try {
    const res = await fetch(`/jobs/${encodeURIComponent(id)}`);
    if (res.status === 404) {
      done();
      setStatus("The server lost this job (was it restarted?)", true);
      return;
    }
    job = await res.json();
  } catch {
    setStatus("Can't reach the server, still trying…");
    setTimeout(() => pollJob(id), POLL_MS);
    return;
  }

  if (job.state === "queued") {
    setStatus("Waiting for Whisper…");
  } else if (job.state === "running") {
    setStatus("Transcribing…");
  } else if (job.state === "done") {
    done();
    if (!job.text) {
      setStatus("Done, but no speech was found");
    } else {
      setStatus(job.file ? `Done ✓  Saved as ${job.file}` : "Done ✓  (the .txt couldn't be saved)");
      showResult(job.text);
    }
    return;
  } else {
    done();
    setStatus(`Transcription failed${job.error ? ": " + job.error : ""}`, true);
    return;
  }
  setTimeout(() => pollJob(id), POLL_MS);
}

function done() {
  uploadBtn.disabled = false;
  progressEl.classList.remove("active");
}

function showResult(text) {
  if (!text) return;
  resultText.textContent = text;
  resultEl.classList.add("shown");
}

copyBtn.onclick = async () => {
  try {
    await navigator.clipboard.writeText(resultText.textContent);
    copyBtn.textContent = "Copied ✓";
  } catch {
    copyBtn.textContent = "Can't copy";
  }
  setTimeout(() => { copyBtn.textContent = "Copy"; }, 1500);
};
