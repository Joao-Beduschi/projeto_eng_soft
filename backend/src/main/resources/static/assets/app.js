const state = {
  user: null,
  csrfToken: null,
  currentFolderId: null,
  path: [{ id: null, name: "My files" }],
  folders: [],
  files: [],
  authMode: "login",
  dialogResolver: null,
};

const $ = (selector) => document.querySelector(selector);
const authView = $("#auth-view");
const appView = $("#app-view");
const authForm = $("#auth-form");
const authEmail = $("#auth-email");
const authPassword = $("#auth-password");
const authSubmit = $("#auth-submit");
const passwordHint = $("#password-hint");
const toastRegion = $("#toast-region");
const fileInput = $("#file-input");
const dropArea = $("#drop-area");
const dropOverlay = $("#drop-overlay");
const actionDialog = $("#action-dialog");
const actionForm = $("#action-form");
const dialogFields = $("#dialog-fields");

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

function showToast(message, kind = "success") {
  const toast = document.createElement("div");
  toast.className = `toast ${kind === "error" ? "error" : ""}`;
  toast.textContent = message;
  toastRegion.append(toast);
  window.setTimeout(() => toast.remove(), 4300);
}

function authHeaders() {
  return { Accept: "application/json" };
}

async function fetchCsrf() {
  const response = await fetch("/api/auth/csrf", {
    method: "GET",
    credentials: "same-origin",
    headers: authHeaders(),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.token) {
    throw new ApiError(result?.message || "Could not start a secure session.", response.status);
  }
  state.csrfToken = result.token;
}

async function api(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(authHeaders());
  let body = options.body;

  if (body && !(body instanceof FormData) && typeof body !== "string") {
    body = JSON.stringify(body);
    headers.set("Content-Type", "application/json");
  } else if (typeof body === "string") {
    headers.set("Content-Type", "application/json");
  }

  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    if (!state.csrfToken) {
      await fetchCsrf();
    }
    headers.set("X-CSRF-TOKEN", state.csrfToken);
  }

  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers,
    body,
  });

  let payload = null;
  if (response.status !== 204) {
    const text = await response.text();
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch {
        payload = text;
      }
    }
  }

  if (!response.ok) {
    if (response.status === 401 && state.user) {
      state.user = null;
      showAuthentication();
      showToast("Your session ended. Sign in again to continue.", "error");
    }
    throw new ApiError(
      payload?.message || (typeof payload === "string" ? payload : "The request could not be completed."),
      response.status,
      payload?.code,
    );
  }
  return payload;
}

function showAuthentication() {
  appView.hidden = true;
  authView.hidden = false;
  state.user = null;
}

async function showApplication(user) {
  state.user = user;
  authView.hidden = true;
  appView.hidden = false;
  $("#user-email").textContent = user.email;
  $("#user-avatar").textContent = user.email.trim().charAt(0).toUpperCase() || "U";
  await loadFolder(null, [{ id: null, name: "My files" }]);
}

function setAuthMode(mode) {
  state.authMode = mode;
  const registering = mode === "register";
  document.querySelectorAll("[data-auth-mode]").forEach((button) => {
    const selected = button.dataset.authMode === mode;
    button.classList.toggle("is-active", selected);
    button.setAttribute("aria-selected", String(selected));
  });
  $("#auth-eyebrow").textContent = registering ? "GET STARTED" : "WELCOME BACK";
  $("#auth-title").textContent = registering ? "Create your account" : "Sign in to your space";
  $("#auth-description").textContent = registering
    ? "Set up your private space for files and folders."
    : "Your files are right where you left them.";
  authSubmit.textContent = registering ? "Create account" : "Sign in";
  passwordHint.hidden = !registering;
  authPassword.autocomplete = registering ? "new-password" : "current-password";
  authPassword.minLength = registering ? 12 : 0;
}

document.querySelectorAll("[data-auth-mode]").forEach((button) => {
  button.addEventListener("click", () => setAuthMode(button.dataset.authMode));
});

authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!authForm.reportValidity()) return;

  const email = authEmail.value.trim();
  const password = authPassword.value;
  const registering = state.authMode === "register";
  authSubmit.disabled = true;
  authSubmit.textContent = registering ? "Creating account…" : "Signing in…";

  try {
    await fetchCsrf();
    if (registering) {
      await api("/api/auth/register", {
        method: "POST",
        body: { email, password },
      });
      await fetchCsrf();
    }
    await api("/api/auth/login", {
      method: "POST",
      body: { email, password },
    });
    await fetchCsrf();
    const user = await api("/api/users/me");
    await showApplication(user);
    showToast(registering ? "Your account is ready." : "Welcome back.");
    authForm.reset();
  } catch (error) {
    const validationHint = registering && error.status === 400
      ? " Check the email address and use a password from 12 to 128 characters."
      : "";
    showToast(`${error.message || "Could not sign in."}${validationHint}`, "error");
  } finally {
    authSubmit.disabled = false;
    authSubmit.textContent = registering ? "Create account" : "Sign in";
  }
});

async function boot() {
  setAuthMode("login");
  try {
    await fetchCsrf();
    const user = await api("/api/users/me");
    await showApplication(user);
  } catch {
    showAuthentication();
  }
}

async function loadFolder(folderId, nextPath = null) {
  $("#loading-state").hidden = false;
  $("#empty-state").hidden = true;
  $("#folders-section").hidden = true;
  $("#files-section").hidden = true;
  state.currentFolderId = folderId;
  if (nextPath) state.path = nextPath;

  try {
    if (folderId === null) {
      const [files, folders] = await Promise.all([
        api("/api/files"),
        api("/api/folders"),
      ]);
      state.files = files;
      state.folders = folders;
    } else {
      const contents = await api(`/api/folders/${encodeURIComponent(folderId)}/contents`);
      state.files = contents.files;
      state.folders = contents.folders;
    }
    renderWorkspace();
  } catch (error) {
    showToast(error.message || "Could not load this folder.", "error");
  } finally {
    $("#loading-state").hidden = true;
  }
}

async function refreshCurrentFolder() {
  await loadFolder(state.currentFolderId);
}

function navigateInto(folder) {
  const nextPath = [...state.path, { id: folder.id, name: folder.name }];
  loadFolder(folder.id, nextPath);
}

function navigateToBreadcrumb(index) {
  const nextPath = state.path.slice(0, index + 1);
  loadFolder(nextPath.at(-1).id, nextPath);
}

function renderBreadcrumbs() {
  $("#breadcrumbs").innerHTML = state.path.map((part, index) => {
    const isCurrent = index === state.path.length - 1;
    const separator = index > 0 ? '<span class="breadcrumb-separator" aria-hidden="true">/</span>' : "";
    return `${separator}<button class="breadcrumb-button ${isCurrent ? "is-current" : ""}" type="button" data-crumb-index="${index}" ${isCurrent ? 'aria-current="page"' : ""}>${escapeHtml(part.name)}</button>`;
  }).join("");
}

function renderWorkspace() {
  const currentName = state.path.at(-1)?.name || "My files";
  $("#page-title").textContent = currentName;
  $("#page-eyebrow").textContent = state.currentFolderId ? "FOLDER" : "YOUR WORKSPACE";
  $("#page-description").textContent = state.currentFolderId
    ? "Files and folders inside this location."
    : "Keep your work organized and easy to find.";
  renderBreadcrumbs();

  const search = $("#search-input").value.trim().toLocaleLowerCase();
  const folders = state.folders.filter((folder) => folder.name.toLocaleLowerCase().includes(search));
  const files = state.files.filter((file) => file.name.toLocaleLowerCase().includes(search));

  $("#folder-count").textContent = String(folders.length);
  $("#file-count").textContent = String(files.length);
  $("#contents-count").textContent = `${folders.length + files.length} ${folders.length + files.length === 1 ? "item" : "items"}`;
  $("#folders-section-count").textContent = folders.length ? String(folders.length) : "";
  $("#files-section-count").textContent = files.length ? String(files.length) : "";

  const foldersSection = $("#folders-section");
  foldersSection.hidden = folders.length === 0;
  $("#files-section").hidden = files.length === 0;

  $("#folder-grid").innerHTML = folders.map(renderFolder).join("");
  $("#file-table-body").innerHTML = files.map(renderFile).join("");

  const hasItems = folders.length + files.length > 0;
  const emptyState = $("#empty-state");
  emptyState.hidden = hasItems;
  if (!hasItems) {
    const title = $("#empty-state h3");
    const message = $("#empty-state p");
    if (search) {
      title.textContent = "No matching items";
      message.textContent = "Try another name or clear the search.";
    } else {
      title.textContent = currentName === "My files" ? "Your space is ready" : "This folder is ready for something";
      message.textContent = "Upload a file or create a folder to get started.";
    }
  }
}

function renderFolder(folder) {
  return `
    <article class="folder-card">
      <button class="folder-open" type="button" data-open-folder="${escapeHtml(folder.id)}" aria-label="Open folder ${escapeHtml(folder.name)}">
        <span class="folder-symbol" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none"><path d="M3.8 6.5A1.7 1.7 0 0 1 5.5 4.8h4l1.8 1.8h7.2a1.7 1.7 0 0 1 1.7 1.7v9.9a1.7 1.7 0 0 1-1.7 1.7H5.5a1.7 1.7 0 0 1-1.7-1.7V6.5Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>
        </span>
        <span class="folder-copy"><strong>${escapeHtml(folder.name)}</strong><span>Open folder</span></span>
      </button>
      <div class="folder-actions">
        <button class="tiny-action" type="button" data-folder-action="rename" data-id="${escapeHtml(folder.id)}" aria-label="Rename ${escapeHtml(folder.name)}" title="Rename">
          <svg viewBox="0 0 24 24" fill="none"><path d="m14.2 5.2 4.6 4.6M4.8 19.2l4.2-.9L19 8.3a2 2 0 0 0-2.8-2.8l-10 10-.9 3.7Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button class="tiny-action" type="button" data-folder-action="move" data-id="${escapeHtml(folder.id)}" aria-label="Move ${escapeHtml(folder.name)}" title="Move">
          <svg viewBox="0 0 24 24" fill="none"><path d="M4 12h15m-5-5 5 5-5 5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button class="tiny-action danger" type="button" data-folder-action="delete" data-id="${escapeHtml(folder.id)}" aria-label="Delete ${escapeHtml(folder.name)}" title="Delete">
          <svg viewBox="0 0 24 24" fill="none"><path d="M5 7h14M9 7V4.8h6V7m2 0-.8 12H7.8L7 7m3.5 3v5.5m3-5.5v5.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </article>
  `;
}

function renderFile(file) {
  const extension = file.name.includes(".") ? file.name.split(".").pop().toLocaleLowerCase() : "";
  const typeClass = ["png", "jpg", "jpeg", "gif", "webp", "svg"].includes(extension)
    ? "type-image"
    : extension === "pdf" ? "type-pdf"
      : ["zip", "rar", "7z", "tar", "gz"].includes(extension) ? "type-archive" : "";
  const modified = formatDate(file.updatedAt || file.createdAt);
  return `
    <tr>
      <td>
        <div class="file-name-cell">
          <span class="file-type-icon ${typeClass}" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none"><path d="M6 3.8h8l4 4v12.4H6V3.8Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M14 4v4h4M9 12h6M9 15.5h6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </span>
          <span class="file-name-copy"><strong title="${escapeHtml(file.name)}">${escapeHtml(file.name)}</strong><span>${escapeHtml(file.mimeType || "File")}</span></span>
        </div>
      </td>
      <td>${formatSize(file.sizeBytes)}</td>
      <td class="file-date">${modified}</td>
      <td>
        <div class="file-actions">
          <a class="download-link" href="/api/files/${encodeURIComponent(file.id)}/download">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 4.5v10m0 0 3.5-3.5M12 14.5l-3.5-3.5M5 17v2h14v-2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
            Download
          </a>
          <button class="tiny-action" type="button" data-file-action="rename" data-id="${escapeHtml(file.id)}" aria-label="Rename ${escapeHtml(file.name)}" title="Rename">
            <svg viewBox="0 0 24 24" fill="none"><path d="m14.2 5.2 4.6 4.6M4.8 19.2l4.2-.9L19 8.3a2 2 0 0 0-2.8-2.8l-10 10-.9 3.7Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button class="tiny-action" type="button" data-file-action="move" data-id="${escapeHtml(file.id)}" aria-label="Move ${escapeHtml(file.name)}" title="Move">
            <svg viewBox="0 0 24 24" fill="none"><path d="M4 12h15m-5-5 5 5-5 5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button class="tiny-action danger" type="button" data-file-action="delete" data-id="${escapeHtml(file.id)}" aria-label="Delete ${escapeHtml(file.name)}" title="Delete">
            <svg viewBox="0 0 24 24" fill="none"><path d="M5 7h14M9 7V4.8h6V7m2 0-.8 12H7.8L7 7m3.5 3v5.5m3-5.5v5.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </div>
      </td>
    </tr>
  `;
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]);
}

function formatSize(bytes) {
  const size = Number(bytes) || 0;
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  return `${(size / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat(undefined, { year: "numeric", month: "short", day: "numeric" }).format(date);
}

async function getAllFolders() {
  const result = [];
  async function visit(parentId = null, depth = 0) {
    const path = parentId
      ? `/api/folders?${new URLSearchParams({ parentId })}`
      : "/api/folders";
    const children = await api(path);
    for (const folder of children) {
      result.push({ ...folder, depth });
      await visit(folder.id, depth + 1);
    }
  }
  await visit();
  return result;
}

function folderOptions(folders, selectedId = null, excluded = new Set()) {
  const options = [{ value: "", label: "My files (root)" }];
  for (const folder of folders) {
    if (excluded.has(folder.id)) continue;
    options.push({
      value: folder.id,
      label: `${"— ".repeat(folder.depth || 0)}${folder.name}`,
    });
  }
  return options.map((option) => ({
    ...option,
    selected: option.value === (selectedId || ""),
  }));
}

function descendantsOf(folderId, folders) {
  const excluded = new Set([folderId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const folder of folders) {
      if (folder.parentId && excluded.has(folder.parentId) && !excluded.has(folder.id)) {
        excluded.add(folder.id);
        changed = true;
      }
    }
  }
  return excluded;
}

function openActionDialog({ title, submitText = "Save changes", fields }) {
  $("#dialog-title").textContent = title;
  $("#dialog-submit").textContent = submitText;
  dialogFields.replaceChildren();

  for (const field of fields) {
    const label = document.createElement("label");
    label.className = "dialog-field";
    const labelText = document.createElement("span");
    labelText.textContent = field.label;
    label.append(labelText);

    let control;
    if (field.type === "select") {
      control = document.createElement("select");
      for (const option of field.options || []) {
        const optionElement = document.createElement("option");
        optionElement.value = option.value;
        optionElement.textContent = option.label;
        optionElement.selected = Boolean(option.selected);
        control.append(optionElement);
      }
    } else {
      control = document.createElement("input");
      control.type = field.type || "text";
      control.placeholder = field.placeholder || "";
      if (field.maxLength) control.maxLength = field.maxLength;
      if (field.required !== false) control.required = true;
      if (field.value != null) control.value = field.value;
    }
    control.name = field.name;
    if (field.autocomplete) control.autocomplete = field.autocomplete;
    label.append(control);
    dialogFields.append(label);
  }

  actionDialog.showModal();
  const first = dialogFields.querySelector("input, select");
  first?.focus();

  return new Promise((resolve) => {
    let finished = false;
    const finish = (value) => {
      if (finished) return;
      finished = true;
      actionDialog.close();
      state.dialogResolver = null;
      resolve(value);
    };
    state.dialogResolver = finish;
  });
}

actionForm.addEventListener("submit", (event) => {
  event.preventDefault();
  if (!actionForm.reportValidity()) return;
  const values = Object.fromEntries(new FormData(actionForm).entries());
  state.dialogResolver?.(values);
});
$("#dialog-cancel").addEventListener("click", () => state.dialogResolver?.(null));
$("#dialog-close").addEventListener("click", () => state.dialogResolver?.(null));
actionDialog.addEventListener("cancel", (event) => {
  event.preventDefault();
  state.dialogResolver?.(null);
});

async function createFolder() {
  const folders = await getAllFolders();
  const values = await openActionDialog({
    title: "Create a folder",
    submitText: "Create folder",
    fields: [
      { name: "name", label: "Folder name", placeholder: "e.g. Project files", maxLength: 255 },
      {
        name: "parentId",
        label: "Create inside",
        type: "select",
        options: folderOptions(folders, state.currentFolderId),
      },
    ],
  });
  if (!values) return;
  await api("/api/folders", {
    method: "POST",
    body: { name: values.name, parentId: values.parentId || null },
  });
  showToast("Folder created.");
  await refreshCurrentFolder();
}

async function renameFolder(folderId) {
  const folder = state.folders.find((item) => item.id === folderId);
  if (!folder) return;
  const values = await openActionDialog({
    title: "Rename folder",
    submitText: "Save name",
    fields: [{ name: "name", label: "Folder name", value: folder.name, maxLength: 255 }],
  });
  if (!values) return;
  await api(`/api/folders/${encodeURIComponent(folderId)}`, {
    method: "PATCH",
    body: { name: values.name },
  });
  showToast("Folder renamed.");
  await refreshCurrentFolder();
}

async function moveFolder(folderId) {
  const folder = state.folders.find((item) => item.id === folderId);
  if (!folder) return;
  const folders = await getAllFolders();
  const excluded = descendantsOf(folderId, folders);
  const values = await openActionDialog({
    title: "Move folder",
    submitText: "Move folder",
    fields: [{
      name: "parentId",
      label: "New parent folder",
      type: "select",
      options: folderOptions(folders, folder.parentId, excluded),
    }],
  });
  if (!values) return;
  await api(`/api/folders/${encodeURIComponent(folderId)}`, {
    method: "PATCH",
    body: { parentId: values.parentId || null },
  });
  showToast("Folder moved.");
  await refreshCurrentFolder();
}

async function deleteFolder(folderId) {
  const folder = state.folders.find((item) => item.id === folderId);
  if (!folder || !window.confirm(`Delete the folder “${folder.name}”? It must be empty.`)) return;
  await api(`/api/folders/${encodeURIComponent(folderId)}`, { method: "DELETE" });
  showToast("Folder deleted.");
  await refreshCurrentFolder();
}

async function renameFile(fileId) {
  const file = state.files.find((item) => item.id === fileId);
  if (!file) return;
  const values = await openActionDialog({
    title: "Rename file",
    submitText: "Save name",
    fields: [{ name: "name", label: "File name", value: file.name, maxLength: 255 }],
  });
  if (!values) return;
  await api(`/api/files/${encodeURIComponent(fileId)}`, {
    method: "PATCH",
    body: { name: values.name },
  });
  showToast("File renamed.");
  await refreshCurrentFolder();
}

async function moveFile(fileId) {
  const folders = await getAllFolders();
  const values = await openActionDialog({
    title: "Move file",
    submitText: "Move file",
    fields: [{
      name: "folderId",
      label: "Destination folder",
      type: "select",
      options: folderOptions(folders, state.currentFolderId),
    }],
  });
  if (!values) return;
  await api(`/api/files/${encodeURIComponent(fileId)}`, {
    method: "PATCH",
    body: { folderId: values.folderId || null },
  });
  showToast("File moved.");
  await refreshCurrentFolder();
}

async function deleteFile(fileId) {
  const file = state.files.find((item) => item.id === fileId);
  if (!file || !window.confirm(`Delete “${file.name}” permanently?`)) return;
  await api(`/api/files/${encodeURIComponent(fileId)}`, { method: "DELETE" });
  showToast("File deleted.");
  await refreshCurrentFolder();
}

async function uploadFiles(files) {
  const selected = Array.from(files || []);
  if (!selected.length) return;
  for (const file of selected) {
    if (file.size > 100 * 1024 * 1024) {
      showToast(`${file.name} is larger than the 100 MB upload limit.`, "error");
      continue;
    }
    try {
      const form = new FormData();
      form.append("file", file, file.name);
      if (state.currentFolderId) form.append("folderId", state.currentFolderId);
      await api("/api/files", { method: "POST", body: form });
      showToast(`${file.name} uploaded.`);
    } catch (error) {
      showToast(`${file.name}: ${error.message || "upload failed"}`, "error");
    }
  }
  await refreshCurrentFolder();
}

function handleActionError(error) {
  const message = error instanceof ApiError
    ? error.status === 409 && error.code === "folder_not_empty"
      ? "This folder contains items. Move or delete them before deleting the folder."
      : error.message
    : error?.message || "Something went wrong.";
  showToast(message, "error");
}

function bindEvents() {
  $("#nav-my-files").addEventListener("click", () => {
    $("#search-input").value = "";
    loadFolder(null, [{ id: null, name: "My files" }]);
  });
  $("#new-folder-button").addEventListener("click", () => createFolder().catch(handleActionError));
  $("#upload-button").addEventListener("click", () => fileInput.click());
  $("[data-empty-upload]").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    await uploadFiles(fileInput.files);
    fileInput.value = "";
  });
  $("#search-input").addEventListener("input", renderWorkspace);

  $("#breadcrumbs").addEventListener("click", (event) => {
    const button = event.target.closest("[data-crumb-index]");
    if (button) navigateToBreadcrumb(Number(button.dataset.crumbIndex));
  });
  $("#folder-grid").addEventListener("click", (event) => {
    const open = event.target.closest("[data-open-folder]");
    if (open) {
      const folder = state.folders.find((item) => item.id === open.dataset.openFolder);
      if (folder) navigateInto(folder);
      return;
    }
    const action = event.target.closest("[data-folder-action]");
    if (!action) return;
    const operations = {
      rename: renameFolder,
      move: moveFolder,
      delete: deleteFolder,
    };
    operations[action.dataset.folderAction]?.(action.dataset.id).catch(handleActionError);
  });
  $("#file-table-body").addEventListener("click", (event) => {
    const action = event.target.closest("[data-file-action]");
    if (!action) return;
    const operations = {
      rename: renameFile,
      move: moveFile,
      delete: deleteFile,
    };
    operations[action.dataset.fileAction]?.(action.dataset.id).catch(handleActionError);
  });

  const logout = async () => {
    try {
      await api("/api/auth/logout", { method: "POST" });
      state.csrfToken = null;
      state.user = null;
      state.path = [{ id: null, name: "My files" }];
      showAuthentication();
      showToast("You’ve been signed out.");
    } catch (error) {
      handleActionError(error);
    }
  };
  $("#logout-button").addEventListener("click", logout);
  $("#mobile-logout").addEventListener("click", logout);

  dropArea.addEventListener("dragenter", (event) => {
    if (event.dataTransfer?.types.includes("Files")) {
      event.preventDefault();
      dropOverlay.hidden = false;
    }
  });
  dropArea.addEventListener("dragover", (event) => {
    if (event.dataTransfer?.types.includes("Files")) {
      event.preventDefault();
      event.dataTransfer.dropEffect = "copy";
      dropOverlay.hidden = false;
    }
  });
  dropArea.addEventListener("dragleave", (event) => {
    if (!dropArea.contains(event.relatedTarget)) dropOverlay.hidden = true;
  });
  dropArea.addEventListener("drop", async (event) => {
    event.preventDefault();
    dropOverlay.hidden = true;
    await uploadFiles(event.dataTransfer?.files);
  });
  window.addEventListener("dragover", (event) => {
    if (event.dataTransfer?.types.includes("Files")) event.preventDefault();
  });
  window.addEventListener("drop", (event) => {
    if (event.dataTransfer?.types.includes("Files")) event.preventDefault();
  });
  document.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k" && !appView.hidden) {
      event.preventDefault();
      $("#search-input").focus();
    }
  });
}

bindEvents();
boot();
