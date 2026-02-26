(() => {
  const API_REQUEST_TIMEOUT_MS = 7000;

  const el = {
    addBtn: document.getElementById("noteAddBtn"),
    modal: document.getElementById("noteModal"),
    modalClose: document.getElementById("noteModalClose"),
    form: document.getElementById("noteForm"),
    title: document.getElementById("noteTitle"),
    content: document.getElementById("noteContent"),
    list: document.getElementById("noteList"),
    status: document.getElementById("noteStatus"),
    viewModal: document.getElementById("noteViewModal"),
    viewTitle: document.getElementById("noteViewTitle"),
    viewContent: document.getElementById("noteViewContent"),
    viewClose: document.getElementById("noteViewClose"),
    deleteBtn: document.getElementById("noteDeleteBtn")
  };
  const state = {
    notes: [],
    activeNoteId: ""
  };

  init();

  function init() {
    if (!el.list) return;

    if (el.addBtn) {
      el.addBtn.addEventListener("click", openModal);
    }

    if (el.modalClose) {
      el.modalClose.addEventListener("click", closeModal);
    }

    if (el.modal) {
      el.modal.addEventListener("click", (e) => {
        if (e.target === el.modal) closeModal();
      });
    }

    if (el.form) {
      el.form.addEventListener("submit", onCreate);
    }
    if (el.viewClose) {
      el.viewClose.addEventListener("click", closeViewModal);
    }
    if (el.viewModal) {
      el.viewModal.addEventListener("click", (e) => {
        if (e.target === el.viewModal) closeViewModal();
      });
    }
    if (el.deleteBtn) {
      el.deleteBtn.addEventListener("click", onDeleteActiveNote);
    }

    bootAndLoadNotes();
  }

  async function bootAndLoadNotes() {
    const common = window.AiCalCommon;
    if (common && typeof common.wakeUpServices === "function") {
      await common.wakeUpServices((msg) => setStatus(msg));
    }
    await loadNotes();
  }

  async function onCreate(e) {
    e.preventDefault();
    const title = (el.title.value || "").trim();
    const content = (el.content.value || "").trim();
    if (!title) return;

    setStatus("Сохраняю...");
    const ok = await requestWithFallback(
      getNoteEndpoints(),
      [
        { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title, content }) },
        { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title, content }) }
      ]
    );

    if (!ok.success) {
      setStatus(ok.message);
      return;
    }

    el.form.reset();
    closeModal();
    setStatus("Заметка создана");
    await loadNotes();
  }

  async function loadNotes() {
    setStatus("Подготавливаю заметки и подтягиваю последние записи...");
    const res = await requestWithFallback(getNoteEndpoints(), [{ method: "GET" }]);
    if (!res.success) {
      setStatus(res.message);
      renderNotes([]);
      return;
    }

    state.notes = Array.isArray(res.data) ? res.data : [];
    renderNotes(state.notes);
    setStatus(state.notes.length ? "" : "Пока нет заметок");
  }

  function renderNotes(notes) {
    el.list.innerHTML = "";
    for (const n of notes) {
      const item = document.createElement("div");
      item.className = "page-item note-item";
      item.innerHTML = `
        <div class="page-item-title">${escapeHtml(n.title || "(без названия)")}</div>
        <div class="page-item-meta">${escapeHtml(n.content || "")}</div>
      `;
      item.addEventListener("click", () => openViewModal(n.id));
      el.list.appendChild(item);
    }
  }

  function openModal() {
    if (!el.modal) return;
    el.modal.classList.remove("hidden");
    if (el.title) {
      window.setTimeout(() => el.title.focus(), 0);
    }
  }

  function closeModal() {
    if (!el.modal) return;
    el.modal.classList.add("hidden");
  }

  function openViewModal(noteId) {
    const note = state.notes.find((n) => String(n.id) === String(noteId));
    if (!note || !el.viewModal) return;
    state.activeNoteId = String(note.id || "");
    if (el.viewTitle) {
      el.viewTitle.textContent = String(note.title || "Заметка");
    }
    if (el.viewContent) {
      el.viewContent.textContent = String(note.content || "");
    }
    el.viewModal.classList.remove("hidden");
  }

  function closeViewModal() {
    if (!el.viewModal) return;
    el.viewModal.classList.add("hidden");
    state.activeNoteId = "";
  }

  async function onDeleteActiveNote() {
    const id = state.activeNoteId;
    if (!id) return;
    const ok = window.confirm("Удалить заметку?");
    if (!ok) return;

    setStatus("Удаляю заметку...");
    const res = await requestWithFallback(
      getNoteEndpoints().map((base) => `${base}/${id}`),
      [{ method: "DELETE" }]
    );
    if (!res.success) {
      setStatus(res.message);
      return;
    }
    closeViewModal();
    await loadNotes();
  }

  async function request(path, init = {}, forcedBase = "") {
    const common = window.AiCalCommon;
    const base = forcedBase || (common && common.getApiBaseUrl ? common.getApiBaseUrl() : window.location.origin);
    const auth = common && common.buildAuth ? common.buildAuth() : { headers: {}, initData: "", telegramId: "" };

    const url = new URL(base + path);
    if (auth.telegramId) {
      url.searchParams.set("telegramId", auth.telegramId);
    }

    const headers = { ...(init.headers || {}), ...auth.headers };

    try {
      const response = await fetchWithTimeout(
        url.toString(),
        { ...init, headers },
        API_REQUEST_TIMEOUT_MS
      );
      if (!response.ok) {
        return { success: false, status: response.status, message: apiErrorMessage(response.status) };
      }
      if (response.status === 204) {
        return { success: true, status: response.status, data: null };
      }
      const data = await response.json().catch(() => null);
      return { success: true, status: response.status, data };
    } catch {
      if (window.location.protocol === "https:" && String(base).startsWith("http://")) {
        return {
          success: false,
          message: "Ошибка сети: страница открыта по HTTPS, а API указан по HTTP. Нужен HTTPS API URL."
        };
      }
      return { success: false, message: "Ошибка сети" };
    }
  }

  async function requestWithFallback(paths, variants) {
    let lastError = { success: false, message: "Ошибка API" };
    let firstHttpError = null;
    const common = window.AiCalCommon;
    const bases = common && typeof common.getApiBaseCandidates === "function"
      ? common.getApiBaseCandidates()
      : [window.location.origin];

    for (const base of bases) {
      for (const path of paths) {
        for (const variant of variants) {
          const res = await request(path, variant, base);
          if (res.success) return res;

          lastError = res;
          if (res.status != null && firstHttpError == null) {
            firstHttpError = res;
          }
        }
      }
    }

    return firstHttpError || lastError;
  }

  function getNoteEndpoints() {
    const common = window.AiCalCommon;
    if (common && typeof common.getEndpointCandidates === "function") {
      return common.getEndpointCandidates("notes", ["/api/miniapp/notes", "/api/notes", "/notes"]);
    }
    return ["/api/miniapp/notes", "/api/notes", "/notes"];
  }

  function apiErrorMessage(status) {
    if (status === 401) return "Нет доступа. Открой Mini App через Telegram";
    if (status === 404) return "Сервис заметок прогревается. Попробуйте через несколько секунд.";
    return `Ошибка API (${status})`;
  }

  function setStatus(message) {
    if (el.status) el.status.textContent = message || "";
  }

  function escapeHtml(s) {
    return String(s)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  async function fetchWithTimeout(resource, init, timeoutMs) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(resource, { ...init, signal: controller.signal });
    } finally {
      window.clearTimeout(timer);
    }
  }
})();
