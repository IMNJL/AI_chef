(() => {
  const el = {
    form: document.getElementById("noteForm"),
    title: document.getElementById("noteTitle"),
    content: document.getElementById("noteContent"),
    list: document.getElementById("noteList"),
    status: document.getElementById("noteStatus")
  };

  init();

  function init() {
    if (!el.form || !el.list) return;
    el.form.addEventListener("submit", onCreate);
    loadNotes();
  }

  async function onCreate(e) {
    e.preventDefault();
    const title = (el.title.value || "").trim();
    const content = (el.content.value || "").trim();
    if (!title || !content) return;

    setStatus("Сохраняю...");
    const ok = await request("/api/miniapp/notes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title, content })
    });

    if (!ok.success) {
      setStatus(ok.message);
      return;
    }

    el.form.reset();
    setStatus("Заметка создана");
    await loadNotes();
  }

  async function loadNotes() {
    setStatus("Загрузка...");
    const res = await request("/api/miniapp/notes");
    if (!res.success) {
      setStatus(res.message);
      renderNotes([]);
      return;
    }

    const notes = Array.isArray(res.data) ? res.data : [];
    renderNotes(notes);
    setStatus(notes.length ? "" : "Пока нет заметок");
  }

  function renderNotes(notes) {
    el.list.innerHTML = "";
    for (const n of notes) {
      const item = document.createElement("div");
      item.className = "page-item";
      item.innerHTML = `
        <div class="page-item-title">${escapeHtml(n.title || "(без названия)")}</div>
        <div class="page-item-meta">${escapeHtml(n.content || "")}</div>
      `;
      el.list.appendChild(item);
    }
  }

  async function request(path, init = {}) {
    const common = window.AiCalCommon;
    const base = common && common.getApiBaseUrl ? common.getApiBaseUrl() : window.location.origin;
    const auth = common && common.buildAuth ? common.buildAuth() : { headers: {}, initData: "", telegramId: "" };

    const url = new URL(base + path);
    if (!auth.initData && auth.telegramId) {
      url.searchParams.set("telegramId", auth.telegramId);
    }

    const headers = { ...(init.headers || {}), ...auth.headers };

    try {
      const response = await fetch(url.toString(), { ...init, headers });
      if (!response.ok) {
        return { success: false, message: apiErrorMessage(response.status) };
      }
      if (response.status === 204) {
        return { success: true, data: null };
      }
      const data = await response.json().catch(() => null);
      return { success: true, data };
    } catch {
      return { success: false, message: "Ошибка сети" };
    }
  }

  function apiErrorMessage(status) {
    if (status === 401) return "Нет доступа. Открой Mini App через Telegram";
    if (status === 404) return "API не найден. Проверь apiBaseUrl";
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
})();
