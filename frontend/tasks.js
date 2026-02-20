(() => {
  const el = {
    addBtn: document.getElementById("taskAddBtn"),
    modal: document.getElementById("taskModal"),
    modalClose: document.getElementById("taskModalClose"),
    form: document.getElementById("taskForm"),
    title: document.getElementById("taskTitle"),
    priority: document.getElementById("taskPriority"),
    dueAt: document.getElementById("taskDueAt"),
    list: document.getElementById("taskList"),
    status: document.getElementById("taskStatus")
  };

  init();

  function init() {
    if (!el.list) return;

    if (el.addBtn) {
      el.addBtn.addEventListener("click", () => {
        openModal();
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
    setStatus("Загрузка...");
    const res = await requestWithFallback(getTaskEndpoints(), [{ method: "GET" }]);
    if (!res.success) {
      renderTasks([]);
      setStatus(res.message);
      return;
    }

    const tasks = Array.isArray(res.data) ? res.data : [];
    renderTasks(tasks.filter((t) => !t.completed));
    setStatus(tasks.length ? "" : "Пока нет задач");
  }

  function renderTasks(tasks) {
    el.list.innerHTML = "";
    for (const task of tasks) {
      const item = document.createElement("div");
      item.className = "page-item";

      const check = document.createElement("button");
      check.type = "button";
      check.className = "task-check";
      check.setAttribute("aria-label", "Отметить выполненной");

      const text = document.createElement("div");
      text.className = "task-text";
      const due = task.dueAt ? formatDateTime(task.dueAt) : "без срока";
      text.innerHTML = `
        <div class="page-item-title">${escapeHtml(task.title || "(без названия)")}</div>
        <div class="page-item-meta">${escapeHtml((task.priority || "MEDIUM") + " • " + due)}</div>
      `;

      check.addEventListener("click", async () => {
        check.disabled = true;
        check.classList.add("done");
        text.classList.add("done");

        const patchRes = await requestWithFallback(
          getTaskEndpoints().map((base) => `${base}/${task.id}`),
          [
            { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ completed: true }) },
            { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ completed: true }) }
          ]
        );

        if (!patchRes.success) {
          check.disabled = false;
          check.classList.remove("done");
          text.classList.remove("done");
          setStatus(patchRes.message);
          return;
        }

        setStatus("Задача выполнена. Удалю через 5 секунд...");
        window.setTimeout(async () => {
          await requestWithFallback(
            getTaskEndpoints().map((base) => `${base}/${task.id}`),
            [{ method: "DELETE" }]
          );
          item.remove();
          if (!el.list.children.length) {
            setStatus("Пока нет задач");
          } else {
            setStatus("");
          }
        }, 5000);
      });

      item.appendChild(check);
      item.appendChild(text);
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
        return { success: false, status: response.status, message: apiErrorMessage(response.status) };
      }
      if (response.status === 204) {
        return { success: true, status: response.status, data: null };
      }
      const data = await response.json().catch(() => null);
      return { success: true, status: response.status, data };
    } catch {
      return { success: false, message: "Ошибка сети" };
    }
  }

  async function requestWithFallback(paths, variants) {
    let lastError = { success: false, message: "Ошибка API" };
    for (const path of paths) {
      for (const variant of variants) {
        const res = await request(path, variant);
        if (res.success) return res;
        lastError = res;
        if (res.status !== 404 && res.status !== 405) {
          return res;
        }
      }
    }
    return lastError;
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
    if (status === 404) return "API не найден. Проверь apiBaseUrl";
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

  function escapeHtml(s) {
    return String(s)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }
})();
