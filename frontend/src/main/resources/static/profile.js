(() => {
  const API_REQUEST_TIMEOUT_MS = 7000;
  const CALENDAR_VISIBILITY_KEY = "impera_calendar_visibility";

  const el = {
    status: document.getElementById("profileStatus"),
    username: document.getElementById("profileUsername"),
    prefix: document.getElementById("profilePrefix"),
    timezone: document.getElementById("profileTimezone"),
    timezoneInput: document.getElementById("timezoneInput"),
    timezoneSaveBtn: document.getElementById("timezoneSaveBtn"),
    calendarSwitches: document.getElementById("calendarSwitches")
  };
  const state = {
    profile: null
  };

  init();

  async function init() {
    const common = window.AiCalCommon;
    if (common && typeof common.wakeUpServices === "function") {
      await common.wakeUpServices((msg) => setStatus(msg));
    }
    if (el.timezoneSaveBtn) {
      el.timezoneSaveBtn.addEventListener("click", saveTimezone);
    }
    await loadProfile();
  }

  async function loadProfile() {
    setStatus("Подготавливаю профиль...");
    const res = await requestWithFallback(["/api/miniapp/me"], [{ method: "GET" }]);
    if (!res.success) {
      setStatus(res.message);
      return;
    }
    const data = res.data || {};
    if (el.username) {
      const user = data.username ? `@${data.username}` : "-";
      el.username.textContent = user;
    }
    state.profile = data;
    if (el.prefix) {
      el.prefix.textContent = data.titlePrefix || "-";
    }
    if (el.timezone) {
      el.timezone.textContent = data.timezone || "-";
    }
    if (el.timezoneInput) {
      el.timezoneInput.value = data.timezone || "";
    }
    renderCalendarSwitches(data.calendars || {});
    setStatus("");
  }

  function renderCalendarSwitches(calendars) {
    if (!el.calendarSwitches) return;
    const visibility = readCalendarVisibility();
    const rows = [
      { key: "internal", label: "Internal", sub: "Локальный календарь", connected: calendars.internal !== false },
      { key: "google", label: "Google", sub: calendars.google ? "Подключен" : "Не подключен", connected: !!calendars.google },
      { key: "ical", label: "iCal", sub: calendars.ical ? "Подключен" : "Не подключен", connected: !!calendars.ical }
    ];
    el.calendarSwitches.innerHTML = "";
    for (const row of rows) {
      const wrap = document.createElement("div");
      wrap.className = "profile-cal-row";
      const info = document.createElement("div");
      info.innerHTML = `<div class="profile-cal-name">${row.label}</div><div class="profile-cal-sub">${row.sub}</div>`;
      const toggle = document.createElement("button");
      toggle.type = "button";
      toggle.className = `toggle-switch${(visibility[row.key] ?? row.connected) ? " on" : ""}`;
      toggle.disabled = !row.connected;
      toggle.title = row.connected ? "Показать/скрыть" : "Источник не подключен";
      toggle.addEventListener("click", () => {
        const next = !toggle.classList.contains("on");
        toggle.classList.toggle("on", next);
        const current = readCalendarVisibility();
        current[row.key] = next;
        saveCalendarVisibility(current);
        setStatus("Настройки отображения календарей сохранены");
      });
      wrap.appendChild(info);
      wrap.appendChild(toggle);
      el.calendarSwitches.appendChild(wrap);
    }
  }

  async function saveTimezone() {
    const value = String(el.timezoneInput && el.timezoneInput.value ? el.timezoneInput.value : "").trim();
    if (!value) {
      setStatus("Укажи часовой пояс, например Europe/Moscow");
      return;
    }
    setStatus("Сохраняю часовой пояс...");
    const res = await requestWithFallback(
      ["/api/miniapp/me/timezone"],
      [{ method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ timezone: value }) }]
    );
    if (!res.success) {
      setStatus(res.message);
      return;
    }
    if (el.timezone) {
      el.timezone.textContent = value;
    }
    if (state.profile) {
      state.profile.timezone = value;
    }
    setStatus("Часовой пояс обновлен");
  }

  function readCalendarVisibility() {
    try {
      const parsed = JSON.parse(localStorage.getItem(CALENDAR_VISIBILITY_KEY) || "{}");
      if (!parsed || typeof parsed !== "object") return {};
      return parsed;
    } catch {
      return {};
    }
  }

  function saveCalendarVisibility(data) {
    try {
      localStorage.setItem(CALENDAR_VISIBILITY_KEY, JSON.stringify(data || {}));
    } catch {
      // ignore
    }
  }

  async function request(path, init = {}, forcedBase = "") {
    const common = window.AiCalCommon;
    const base = forcedBase || (common && common.getApiBaseUrl ? common.getApiBaseUrl() : window.location.origin);
    const auth = common && common.buildAuth ? common.buildAuth() : { headers: {}, telegramId: "" };
    const url = new URL(base + path);
    if (auth.telegramId) {
      url.searchParams.set("telegramId", auth.telegramId);
    }
    try {
      const response = await fetchWithTimeout(url.toString(), { ...init, headers: { ...(init.headers || {}), ...auth.headers } }, API_REQUEST_TIMEOUT_MS);
      if (!response.ok) {
        return { success: false, status: response.status, message: apiErrorMessage(response.status) };
      }
      const data = await response.json().catch(() => null);
      return { success: true, data };
    } catch {
      return { success: false, message: "Ошибка сети" };
    }
  }

  async function requestWithFallback(paths, variants) {
    let lastError = { success: false, message: "Ошибка API" };
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
        }
      }
    }
    return lastError;
  }

  function apiErrorMessage(status) {
    if (status === 401) return "Нет доступа. Открой Mini App через Telegram";
    if (status === 404) return "Профиль временно недоступен.";
    return `Ошибка API (${status})`;
  }

  function setStatus(message) {
    if (el.status) el.status.textContent = message || "";
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
