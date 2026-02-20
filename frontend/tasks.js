(() => {
  const el = {
    form: document.getElementById("taskForm"),
    title: document.getElementById("taskTitle"),
    priority: document.getElementById("taskPriority"),
    dueAt: document.getElementById("taskDueAt"),
    list: document.getElementById("taskList"),
    status: document.getElementById("taskStatus")
  };

  init();

  function init() {
    if (!el.form || !el.list) return;
    el.form.addEventListener("submit", onCreate);
    loadTasks();
  }

  async function onCreate(e) {
    e.preventDefault();
    const title = (el.title.value || "").trim();
    if (!title) return;

    const payload = {
      title,
      priority: el.priority.value || "MEDIUM",
      dueAt: toOffsetIsoFromInput(el.dueAt.value)
    };

    setStatus("Сохраняю...");
    const ok = await request("/api/miniapp/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!ok.success) {
      setStatus(ok.message);
      return;
    }

    el.form.reset();
    setStatus("Задача создана");
    await loadTasks();
  }

  async function loadTasks() {
    setStatus("Загрузка...");
    const res = await request("/api/miniapp/tasks");
    if (!res.success) {
      setStatus(res.message);
      renderTasks([]);
      return;
    }

    const tasks = Array.isArray(res.data) ? res.data : [];
    renderTasks(tasks);
    setStatus(tasks.length ? "" : "Пока нет задач");
  }

  function renderTasks(tasks) {
    el.list.innerHTML = "";
    for (const t of tasks) {
      const item = document.createElement("div");
      item.className = "page-item";
      const due = t.dueAt ? formatDateTime(t.dueAt) : "без срока";
      item.innerHTML = `
        <div class="page-item-title">${escapeHtml(t.title || "(без названия)")}</div>
        <div class="page-item-meta">${escapeHtml((t.priority || "MEDIUM") + " • " + due)}</div>
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
        const text = await response.text().catch(() => "");
        return { success: false, message: text || `Ошибка API (${response.status})` };
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

  function setStatus(message) {
    if (el.status) el.status.textContent = message || "";
  }

  function toOffsetIsoFromInput(value) {
    const trimmed = (value || "").trim();
    if (!trimmed) return null;
    const parsed = new Date(trimmed);
    if (Number.isNaN(parsed.getTime())) return null;

    const y = parsed.getFullYear();
    const m = pad2(parsed.getMonth() + 1);
    const d = pad2(parsed.getDate());
    const hh = pad2(parsed.getHours());
    const mm = pad2(parsed.getMinutes());
    const ss = pad2(parsed.getSeconds());
    const off = -parsed.getTimezoneOffset();
    const sign = off >= 0 ? "+" : "-";
    const abs = Math.abs(off);
    return `${y}-${m}-${d}T${hh}:${mm}:${ss}${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)}`;
  }

  function formatDateTime(value) {
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value);
    return d.toLocaleString("ru-RU", {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit"
    });
  }

  function pad2(n) {
    return String(n).padStart(2, "0");
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
