(() => {
  const API_REQUEST_TIMEOUT_MS = 7000;
  const DELETE_DELAY_SECONDS = 5;
  const DAY_MS = 24 * 60 * 60 * 1000;

  const el = {
    addBtn: document.getElementById("taskAddBtn"),
    tabs: Array.from(document.querySelectorAll(".task-tab")),
    modal: document.getElementById("taskModal"),
    modalClose: document.getElementById("taskModalClose"),
    form: document.getElementById("taskForm"),
    title: document.getElementById("taskTitle"),
    priority: document.getElementById("taskPriority"),
    dueAt: document.getElementById("taskDueAt"),
    list: document.getElementById("taskList"),
    status: document.getElementById("taskStatus")
  };

  const state = {
    tasks: [],
    activeTab: "active"
  };

  const pendingDelete = new Map();
  const taskLocks = new Set();

  init();

  function init() {
    if (!el.list) return;

    if (el.addBtn) {
      el.addBtn.addEventListener("click", openModal);
    }
    for (const tab of el.tabs) {
      tab.addEventListener("click", () => {
        const tabName = tab.dataset.tab;
        if (!tabName || tabName === state.activeTab) return;
        state.activeTab = tabName;
        renderTasks();
      });
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

    bootAndLoadTasks();
  }

  async function bootAndLoadTasks() {
    const common = window.AiCalCommon;
    if (common && typeof common.wakeUpServices === "function") {
      await common.wakeUpServices((msg) => setStatus(msg));
    }
    await loadTasks();
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
    const res = await requestWithFallback(
      getTaskEndpoints(),
      [
        { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
        { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }
      ]
    );

    if (!res.success) {
      setStatus(res.message);
      return;
    }

    if (el.form) el.form.reset();
    closeModal();
    setStatus("Задача создана");
    await loadTasks();
  }

  async function loadTasks() {
    clearPendingTimers();
    setStatus("Подготавливаю задачи и синхронизирую изменения...");
    const res = await requestWithFallback(getTaskEndpoints(), [{ method: "GET" }]);
    if (!res.success) {
      state.tasks = [];
      renderTasks();
      setStatus(res.message);
      return;
    }

    state.tasks = Array.isArray(res.data) ? res.data : [];
    renderTasks();
    setStatus(state.tasks.length ? "" : "Пока нет задач");
  }

  function renderTasks() {
    syncTabs();
    el.list.innerHTML = "";

    const filtered = state.tasks.filter((task) => {
      if (state.activeTab === "done") {
        return task.completed;
      }
      if (state.activeTab === "backlog") {
        return !task.completed && !task.dueAt;
      }
      return !task.completed && !!task.dueAt;
    });

    for (const task of filtered) {
      const item = document.createElement("div");
      item.className = "page-item";

      const check = document.createElement("button");
      check.type = "button";
      check.className = "task-check";
      check.setAttribute("aria-label", task.completed ? "Вернуть в работу" : "Отметить выполненной");

      const text = document.createElement("div");
      text.className = "task-text";

      const dueLabel = task.dueAt ? formatDateTime(task.dueAt) : "без срока";
      const meta = document.createElement("div");
      meta.className = "page-item-meta";
      meta.textContent = `${task.priority || "MEDIUM"} • ${dueLabel}`;
      if (isDueSoon(task.dueAt) && !task.completed) {
        meta.classList.add("due-urgent");
      }

      const title = document.createElement("div");
      title.className = "page-item-title";
      title.textContent = task.title || "(без названия)";

      text.appendChild(title);
      text.appendChild(meta);

      const pending = pendingDelete.get(task.id);
      if (task.completed) {
        check.classList.add("done");
        text.classList.add("done");
      }
      if (pending) {
        check.classList.add("done");
        text.classList.add("done");
      }

      check.addEventListener("click", async () => {
        await onTaskToggle(task.id);
      });

      item.appendChild(check);
      item.appendChild(text);
      el.list.appendChild(item);
    }
  }

  async function onTaskToggle(taskId) {
    if (!taskId || taskLocks.has(taskId)) return;
    const task = state.tasks.find((t) => t.id === taskId);
    if (!task) return;

    const pending = pendingDelete.get(taskId);
    if (pending) {
      await cancelPendingDelete(task);
      return;
    }
    if (task.completed) {
      await markTaskCompleted(task, false);
      setStatus("Задача возвращена в работу");
      return;
    }

    const ok = await markTaskCompleted(task, true);
    if (!ok) return;
    startDeleteCountdown(task);
  }

  async function markTaskCompleted(task, completed) {
    taskLocks.add(task.id);
    setStatus(completed ? "Отмечаю выполненной..." : "Возвращаю в работу...");

    const patchRes = await requestWithFallback(
      getTaskEndpoints().map((base) => `${base}/${task.id}`),
      [
        { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ completed }) },
        { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ completed }) }
      ]
    );

    taskLocks.delete(task.id);
    if (!patchRes.success) {
      setStatus(patchRes.message);
      return false;
    }

    task.completed = completed;
    renderTasks();
    return true;
  }

  function startDeleteCountdown(task) {
    let remaining = DELETE_DELAY_SECONDS;
    setStatus(`Задача выполнена. Удалю через ${remaining}... Нажмите еще раз, чтобы отменить.`);

    const intervalId = window.setInterval(() => {
      remaining -= 1;
      if (remaining > 0) {
        setStatus(`Задача выполнена. Удалю через ${remaining}... Нажмите еще раз, чтобы отменить.`);
      }
    }, 1000);

    const timeoutId = window.setTimeout(async () => {
      window.clearInterval(intervalId);
      pendingDelete.delete(task.id);

      const delRes = await requestWithFallback(
        getTaskEndpoints().map((base) => `${base}/${task.id}`),
        [{ method: "DELETE" }]
      );

      if (!delRes.success) {
        setStatus(delRes.message);
        return;
      }

      state.tasks = state.tasks.filter((t) => t.id !== task.id);
      renderTasks();
      setStatus(state.tasks.length ? "" : "Пока нет задач");
    }, DELETE_DELAY_SECONDS * 1000);

    pendingDelete.set(task.id, { timeoutId, intervalId });
    renderTasks();
  }

  async function cancelPendingDelete(task) {
    const pending = pendingDelete.get(task.id);
    if (!pending) return;

    window.clearTimeout(pending.timeoutId);
    window.clearInterval(pending.intervalId);
    pendingDelete.delete(task.id);

    const ok = await markTaskCompleted(task, false);
    if (!ok) return;
    setStatus("Удаление отменено");
  }

  function clearPendingTimers() {
    for (const pending of pendingDelete.values()) {
      window.clearTimeout(pending.timeoutId);
      window.clearInterval(pending.intervalId);
    }
    pendingDelete.clear();
  }

  function syncTabs() {
    const counts = {
      active: state.tasks.filter((t) => !t.completed && !!t.dueAt).length,
      done: state.tasks.filter((t) => t.completed).length,
      backlog: state.tasks.filter((t) => !t.completed && !t.dueAt).length
    };

    for (const tab of el.tabs) {
      const tabName = tab.dataset.tab;
      if (!tabName) continue;
      tab.classList.toggle("active", tabName === state.activeTab);
      tab.textContent = `${tabName} ${counts[tabName] || 0}`;
    }
  }

  function isDueSoon(value) {
    if (!value) return false;
    const due = new Date(value);
    if (Number.isNaN(due.getTime())) return false;
    const diff = due.getTime() - Date.now();
    return diff > 0 && diff <= DAY_MS;
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

  function getTaskEndpoints() {
    const common = window.AiCalCommon;
    if (common && typeof common.getEndpointCandidates === "function") {
      return common.getEndpointCandidates("tasks", ["/api/miniapp/tasks", "/api/tasks", "/tasks"]);
    }
    return ["/api/miniapp/tasks", "/api/tasks", "/tasks"];
  }

  function apiErrorMessage(status) {
    if (status === 401) return "Нет доступа. Открой Mini App через Telegram";
    if (status === 404) return "Сервис задач поднимается. Данные скоро появятся.";
    return `Ошибка API (${status})`;
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
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit"
    });
  }

  function pad2(n) {
    return String(n).padStart(2, "0");
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
