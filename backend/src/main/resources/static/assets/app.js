const $ = (selector) => document.querySelector(selector);
const appView = $("#app-view");
const apiBase = appView.dataset.apiBase;
const loginUrl = appView.dataset.loginUrl;
const locale = document.documentElement.lang;
const numbers = new Intl.NumberFormat(locale);
const messages = new Map(
  Array.from($("#localized-messages").content.children, (entry) => [entry.dataset.key, entry.textContent]),
);

function message(key, ...values) {
  return messages.get(key).replace(/\{(\d+)\}/g, (_, index) => String(values[index]));
}
const state = {
  csrfToken: null,
  currentFolderId: appView.dataset.folderId || null,
  dialogResolver: null,
};
const toastRegion = $("#toast-region");
const fileInput = $("#file-input");
const dropArea = $("#drop-area");
const dropOverlay = $("#drop-overlay");
const actionDialog = $("#action-dialog");
const actionForm = $("#action-form");
const dialogFields = $("#dialog-fields");
const emptyTitle = $("#empty-state h3").textContent;
const emptyMessage = $("#empty-state p").textContent;

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
  const response = await fetch(apiBase + "/auth/csrf", {
    method: "GET",
    credentials: "same-origin",
    headers: authHeaders(),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.token) {
    throw new ApiError(result?.message || message("error.secure_session"), response.status);
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

  const response = await fetch(apiBase + path, {
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
    if (response.status === 401) {
      window.location.assign(loginUrl);
    }
    throw new ApiError(
      payload?.message || message("error.request_failed"),
      response.status,
      payload?.code,
    );
  }
  return payload;
}

async function refreshCurrentFolder() {
  const response = await fetch(window.location.pathname, { headers: { Accept: "text/html" } });
  if (response.status === 401) {
    window.location.assign(loginUrl);
    return;
  }
  if (!response.ok) {
    throw new ApiError(message("error.refresh"), response.status);
  }
  const rendered = new DOMParser().parseFromString(await response.text(), "text/html");
  if (!rendered.querySelector("#app-view")) {
    window.location.assign(loginUrl);
    return;
  }
  for (const selector of ["#folder-grid", "#file-table-body"]) {
    $(selector).replaceChildren(...rendered.querySelector(selector).children);
  }
  filterContents();
}

function filterContents() {
  const search = $("#search-input").value.trim().toLocaleLowerCase(locale);
  const countMatches = (container) => {
    let count = 0;
    for (const item of $(container).children) {
      item.hidden = !item.dataset.itemName.toLocaleLowerCase(locale).includes(search);
      if (!item.hidden) count++;
    }
    return count;
  };
  const folders = countMatches("#folder-grid");
  const files = countMatches("#file-table-body");
  const count = folders + files;
  $("#folder-count").textContent = numbers.format(folders);
  $("#file-count").textContent = numbers.format(files);
  $("#contents-count").textContent = message(count === 1 ? "workspace.item.count" : "workspace.items.count", numbers.format(count));
  $("#folders-section-count").textContent = numbers.format(folders);
  $("#files-section-count").textContent = numbers.format(files);
  $("#folders-section").hidden = folders === 0;
  $("#files-section").hidden = files === 0;
  $("#empty-state").hidden = count !== 0;
  $("#empty-state h3").textContent = search ? message("workspace.search.empty") : emptyTitle;
  $("#empty-state p").textContent = search ? message("workspace.search.retry") : emptyMessage;
}

function itemName(container, id) {
  return $(`${container} [data-item-id="${CSS.escape(id)}"]`)?.dataset.itemName;
}

async function getAllFolders() {
  const result = [];
  async function visit(parentId = null, depth = 0) {
    const path = parentId
      ? `/folders?${new URLSearchParams({ parentId })}`
      : "/folders";
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
  const options = [{ value: "", label: message("workspace.root_option") }];
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

function openActionDialog({ title, submitText = message("button.save"), fields }) {
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
      if (field.maxLength) {
        control.maxLength = field.maxLength;
        control.dataset.validationLength = message("validation.name.length");
      }
      if (field.required !== false) {
        control.required = true;
        if (field.requiredMessage) control.dataset.validationRequired = message(field.requiredMessage);
      }
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
    title: message("folders.create_title"),
    submitText: message("folders.create"),
    fields: [
      { name: "name", label: message("folders.dialog.name"), requiredMessage: "validation.folder.required", placeholder: message("folders.dialog.example"), maxLength: 255 },
      {
        name: "parentId",
        label: message("folders.create_inside"),
        type: "select",
        options: folderOptions(folders, state.currentFolderId),
      },
    ],
  });
  if (!values) return;
  await api("/folders", {
    method: "POST",
    body: { name: values.name, parentId: values.parentId || null },
  });
  showToast(message("success.folder_created"));
  await refreshCurrentFolder();
}

async function renameFolder(folderId) {
  const name = itemName("#folder-grid", folderId);
  if (!name) return;
  const values = await openActionDialog({
    title: message("folders.rename"),
    submitText: message("button.save_name"),
    fields: [{ name: "name", label: message("folders.dialog.name"), requiredMessage: "validation.folder.required", value: name, maxLength: 255 }],
  });
  if (!values) return;
  await api(`/folders/${encodeURIComponent(folderId)}`, {
    method: "PATCH",
    body: { name: values.name },
  });
  showToast(message("success.folder_renamed"));
  await refreshCurrentFolder();
}

async function moveFolder(folderId) {
  const folders = await getAllFolders();
  const folder = folders.find((item) => item.id === folderId);
  if (!folder) return;
  const excluded = descendantsOf(folderId, folders);
  const values = await openActionDialog({
    title: message("folders.move"),
    submitText: message("folders.move"),
    fields: [{
      name: "parentId",
      label: message("folders.parent"),
      type: "select",
      options: folderOptions(folders, folder.parentId, excluded),
    }],
  });
  if (!values) return;
  await api(`/folders/${encodeURIComponent(folderId)}`, {
    method: "PATCH",
    body: { parentId: values.parentId || null },
  });
  showToast(message("success.folder_moved"));
  await refreshCurrentFolder();
}

async function deleteFolder(folderId) {
  const name = itemName("#folder-grid", folderId);
  if (!name || !window.confirm(message("folders.delete.confirm", name))) return;
  await api(`/folders/${encodeURIComponent(folderId)}`, { method: "DELETE" });
  showToast(message("success.folder_deleted"));
  await refreshCurrentFolder();
}

async function renameFile(fileId) {
  const name = itemName("#file-table-body", fileId);
  if (!name) return;
  const values = await openActionDialog({
    title: message("files.rename"),
    submitText: message("button.save_name"),
    fields: [{ name: "name", label: message("files.dialog.name"), requiredMessage: "validation.file.required", value: name, maxLength: 255 }],
  });
  if (!values) return;
  await api(`/files/${encodeURIComponent(fileId)}`, {
    method: "PATCH",
    body: { name: values.name },
  });
  showToast(message("success.file_renamed"));
  await refreshCurrentFolder();
}

async function moveFile(fileId) {
  const folders = await getAllFolders();
  const values = await openActionDialog({
    title: message("files.move"),
    submitText: message("files.move"),
    fields: [{
      name: "folderId",
      label: message("files.destination"),
      type: "select",
      options: folderOptions(folders, state.currentFolderId),
    }],
  });
  if (!values) return;
  await api(`/files/${encodeURIComponent(fileId)}`, {
    method: "PATCH",
    body: { folderId: values.folderId || null },
  });
  showToast(message("success.file_moved"));
  await refreshCurrentFolder();
}

async function deleteFile(fileId) {
  const name = itemName("#file-table-body", fileId);
  if (!name || !window.confirm(message("files.delete.confirm", name))) return;
  await api(`/files/${encodeURIComponent(fileId)}`, { method: "DELETE" });
  showToast(message("success.file_deleted"));
  await refreshCurrentFolder();
}

async function uploadFiles(files) {
  const selected = Array.from(files || []);
  if (!selected.length) return;
  for (const file of selected) {
    if (file.size > 100 * 1024 * 1024) {
      showToast(message("files.upload.too_large", file.name), "error");
      continue;
    }
    try {
      const form = new FormData();
      form.append("file", file, file.name);
      if (state.currentFolderId) form.append("folderId", state.currentFolderId);
      await api("/files", { method: "POST", body: form });
      showToast(message("success.file_uploaded", file.name));
    } catch (error) {
      showToast(message("error.upload_file", file.name, actionErrorMessage(error)), "error");
    }
  }
  await refreshCurrentFolder();
}

function actionErrorMessage(error) {
  if (error instanceof ApiError) {
    return error.status === 409 && error.code === "folder_not_empty"
      ? message("folders.delete.nonempty")
      : error.message;
  }
  return message("error.request_failed");
}

function handleActionError(error) {
  showToast(actionErrorMessage(error), "error");
}

function bindEvents() {
  $("#new-folder-button").addEventListener("click", () => createFolder().catch(handleActionError));
  $("#upload-button").addEventListener("click", () => fileInput.click());
  $("[data-empty-upload]").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    await uploadFiles(fileInput.files).catch(handleActionError);
    fileInput.value = "";
  });
  $("#search-input").addEventListener("input", filterContents);

  $("#folder-grid").addEventListener("click", (event) => {
    const action = event.target.closest("[data-folder-action]");
    if (!action) return;
    const operations = {
      rename: renameFolder,
      move: moveFolder,
      delete: deleteFolder,
    };
    operations[action.dataset.folderAction]?.(action.closest("[data-item-id]").dataset.itemId).catch(handleActionError);
  });
  $("#file-table-body").addEventListener("click", (event) => {
    const action = event.target.closest("[data-file-action]");
    if (!action) return;
    const operations = {
      rename: renameFile,
      move: moveFile,
      delete: deleteFile,
    };
    operations[action.dataset.fileAction]?.(action.closest("[data-item-id]").dataset.itemId).catch(handleActionError);
  });

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
    await uploadFiles(event.dataTransfer?.files).catch(handleActionError);
  });
  window.addEventListener("dragover", (event) => {
    if (event.dataTransfer?.types.includes("Files")) event.preventDefault();
  });
  window.addEventListener("drop", (event) => {
    if (event.dataTransfer?.types.includes("Files")) event.preventDefault();
  });
  document.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k" && appView) {
      event.preventDefault();
      $("#search-input").focus();
    }
  });
}

bindEvents();
filterContents();
const notice = $("#toast-region .toast");
if (notice) window.setTimeout(() => notice.remove(), 4300);
