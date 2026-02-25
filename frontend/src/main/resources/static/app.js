(() => {
  const menuItems = [
    { icon: "▦", label: "Расписание", href: "index.html", key: "schedule" },
    { icon: "✓", label: "Задачи", href: "tasks.html", key: "tasks" },
    { icon: "✎", label: "Заметки", href: "notes.html", key: "notes" }
  ];

  const dayNames = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
  const MIN_EVENT_HEIGHT = 22;
  const API_REQUEST_TIMEOUT_MS = 7000;
  const PROFILE_REQUEST_TIMEOUT_MS = 1500;
  const TELEGRAM_ID_STORAGE_KEY = "aical_telegram_id";

  const page = document.body.dataset.page || "schedule";

  const el = {
    menu: document.getElementById("menu"),
    menuToggle: document.getElementById("menuToggle"),
    sidebarBackdrop: document.getElementById("sidebarBackdrop"),
    userName: document.getElementById("userName"),
    userId: document.getElementById("userId"),
    userAvatar: document.getElementById("userAvatar"),
    weekRange: document.getElementById("weekRange"),
    weekHead: document.getElementById("weekHead"),
    weekGrid: document.getElementById("weekGrid"),
    prevBtn: document.getElementById("prevBtn"),
    nextBtn: document.getElementById("nextBtn"),
    scheduleStatus: document.getElementById("scheduleStatus")
  };

  const state = {
    weekStart: startOfWeek(new Date()),
    meetings: [],
    nowTimer: null
  };

  init();

  async function init() {
    renderMenu();
    bindSidebar();
    hydrateUser();

    if (page !== "schedule" || !el.weekGrid) {
      return;
    }

    bindScheduleNav();
    renderWeekSkeleton();
    await loadMeetingsAndRender();
    scheduleNowLine();
  }

  function bindSidebar() {
    if (el.menuToggle) {
      el.menuToggle.addEventListener("click", () => {
        document.body.classList.toggle("sidebar-open");
      });
    }
    if (el.sidebarBackdrop) {
      el.sidebarBackdrop.addEventListener("click", () => {
        document.body.classList.remove("sidebar-open");
      });
    }
  }

  function bindScheduleNav() {
    if (el.prevBtn) {
      el.prevBtn.addEventListener("click", async () => {
        state.weekStart = addDays(state.weekStart, -7);
        await loadMeetingsAndRender();
      });
    }

    if (el.nextBtn) {
      el.nextBtn.addEventListener("click", async () => {
        state.weekStart = addDays(state.weekStart, 7);
        await loadMeetingsAndRender();
      });
    }
  }

  function renderMenu() {
    if (!el.menu) return;
    el.menu.innerHTML = "";
    const search = window.location.search || "";
    for (const item of menuItems) {
      const link = document.createElement("a");
      link.className = `menu-item${item.key === page ? " active" : ""}`;
      link.href = `${item.href}${search}`;
      link.innerHTML = `<span class="menu-icon">${item.icon}</span><span>${item.label}</span>`;
      el.menu.appendChild(link);
    }
  }

  function hydrateUser() {
    const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
    if (tg && typeof tg.ready === "function") {
      try {
        tg.ready();
      } catch {
        // ignore
      }
    }

    const tgUser = tg && tg.initDataUnsafe ? tg.initDataUnsafe.user : null;
    const userId = tgUser && tgUser.id ? String(tgUser.id) : getTelegramIdFromContext();
    const photoUrl = tgUser && tgUser.photo_url ? String(tgUser.photo_url) : "";
    const username = tgUser && tgUser.username ? `@${tgUser.username}` : "";
    const displayName = username || (userId ? `id${userId}` : "user");

    if (el.userName) {
      el.userName.textContent = `Mr ${displayName}`;
    }
    if (el.userId) {
      el.userId.textContent = "";
    }

    resolveTitlePrefix().then((resolvedPrefix) => {
      if (resolvedPrefix && el.userName) {
        el.userName.textContent = `${resolvedPrefix} ${displayName}`;
      }
    });

    if (!el.userAvatar) return;
    if (photoUrl) {
      const img = document.createElement("img");
      img.src = photoUrl;
      img.alt = "Профиль";
      img.width = 38;
      img.height = 38;
      img.style.width = "100%";
      img.style.height = "100%";
      img.style.objectFit = "cover";
      el.userAvatar.textContent = "";
      el.userAvatar.appendChild(img);
      return;
    }

    el.userAvatar.textContent = userId ? String(userId).slice(-2) : "ID";
  }

  async function resolveTitlePrefix() {
    const auth = buildAuth();
    const bases = [getApiBaseUrl()];
    for (const base of bases) {
      try {
        const url = new URL(base + "/api/miniapp/me");
        if (auth.telegramId) {
          url.searchParams.set("telegramId", auth.telegramId);
        }
        const response = await fetchWithTimeout(
          url.toString(),
          { headers: auth.headers },
          PROFILE_REQUEST_TIMEOUT_MS
        );
        if (!response.ok) continue;
        const data = await response.json().catch(() => null);
        const prefix = data && typeof data.titlePrefix === "string" ? data.titlePrefix.trim() : "";
        if (prefix === "Mr" || prefix === "Ms") {
          return prefix;
        }
      } catch {
        // ignore
      }
    }
    return "";
  }

  function getTelegramIdFromContext() {
    try {
      const params = new URLSearchParams(window.location.search);
      const value = params.get("telegramId") || params.get("tg") || "";
      const parsed = Number(value);
      if (Number.isFinite(parsed) && parsed > 0) {
        const id = String(parsed);
        saveTelegramId(id);
        return id;
      }
    } catch {
      // ignore
    }

    try {
      const saved = String(localStorage.getItem(TELEGRAM_ID_STORAGE_KEY) || "").trim();
      const parsed = Number(saved);
      return Number.isFinite(parsed) && parsed > 0 ? String(parsed) : "";
    } catch {
      return "";
    }
  }

  async function loadMeetingsAndRender() {
    setScheduleStatus("Загрузка...");
    const from = toYmd(state.weekStart);
    const to = toYmd(addDays(state.weekStart, 6));
    const endpoints = getMeetingEndpoints().map((p) => `${p}?from=${from}&to=${to}`);
    const res = await requestWithFallback(endpoints, [{ method: "GET" }]);
    if (!res.success) {
      state.meetings = [];
      renderWeekSkeleton();
      setScheduleStatus(res.message || "Ошибка загрузки календаря");
      return;
    }

    state.meetings = Array.isArray(res.data) ? res.data : [];
    renderWeekSkeleton();
    renderMeetings();
    setScheduleStatus(state.meetings.length ? "" : "Событий на неделю нет");
  }

  function renderWeekSkeleton() {
    renderRange();
    renderHead();
    renderGrid();
    renderNowLine();
  }

  function renderRange() {
    if (!el.weekRange) return;
    const start = state.weekStart;
    const end = addDays(start, 6);
    const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
    const startText = `${start.getDate()} ${monthShort(start)}`;
    const endText = `${end.getDate()} ${monthShort(end)}`;
    el.weekRange.textContent = sameMonth ? `${start.getDate()}-${end.getDate()} ${monthShort(end)}` : `${startText} - ${endText}`;
  }

  function renderHead() {
    if (!el.weekHead) return;
    el.weekHead.innerHTML = "";
    const today = startOfDay(new Date());

    const blank = document.createElement("div");
    blank.className = "week-head-cell";
    el.weekHead.appendChild(blank);

    for (let i = 0; i < 7; i++) {
      const d = addDays(state.weekStart, i);
      const cell = document.createElement("div");
      cell.className = "week-head-cell";
      if (sameDay(d, today)) {
        cell.classList.add("today");
      }
      cell.innerHTML = `<span>${d.getDate()}</span><span class="dow">${dayNames[i]}</span>`;
      el.weekHead.appendChild(cell);
    }
  }

  function renderGrid() {
    if (!el.weekGrid) return;
    el.weekGrid.innerHTML = "";
    const today = startOfDay(new Date());

    for (let hour = 0; hour < 24; hour++) {
      const timeCell = document.createElement("div");
      timeCell.className = "time-cell" + (hour === 0 ? " first-hour" : "");
      timeCell.style.gridRow = String(hour + 1);
      timeCell.style.gridColumn = "1";
      timeCell.innerHTML = `<span class="time-label">${hour}:00</span>`;
      el.weekGrid.appendChild(timeCell);

      for (let day = 0; day < 7; day++) {
        const dayCell = document.createElement("div");
        dayCell.className = "day-cell";
        const currentDay = addDays(state.weekStart, day);
        if (sameDay(currentDay, today)) {
          dayCell.classList.add("today");
        }
        dayCell.style.gridRow = String(hour + 1);
        dayCell.style.gridColumn = String(day + 2);
        el.weekGrid.appendChild(dayCell);
      }
    }
  }

  function renderMeetings() {
    if (!el.weekGrid) return;
    const rowHeight = getRowHeight();
    const timeColWidth = getTimeColWidth();
    const dayWidth = getDayColWidth(timeColWidth);
    if (dayWidth <= 1) return;

    const weekStart = startOfDay(state.weekStart);
    const weekEnd = addDays(weekStart, 7);

    for (const meeting of state.meetings) {
      const startsAt = new Date(meeting.startsAt);
      const endsAt = new Date(meeting.endsAt);
      if (!isValidDate(startsAt) || !isValidDate(endsAt) || endsAt <= startsAt) continue;
      if (endsAt <= weekStart || startsAt >= weekEnd) continue;

      for (let dayIdx = 0; dayIdx < 7; dayIdx++) {
        const dayStart = addDays(weekStart, dayIdx);
        const dayEnd = addDays(dayStart, 1);
        const segStart = startsAt > dayStart ? startsAt : dayStart;
        const segEnd = endsAt < dayEnd ? endsAt : dayEnd;
        if (segEnd <= segStart) continue;

        const startMinutes = segStart.getHours() * 60 + segStart.getMinutes();
        const endMinutes = segEnd.getHours() * 60 + segEnd.getMinutes();
        const top = (startMinutes / 60) * rowHeight + 1;
        const height = Math.max(MIN_EVENT_HEIGHT, ((endMinutes - startMinutes) / 60) * rowHeight - 2);
        const left = timeColWidth + dayIdx * dayWidth + 4;
        const width = Math.max(16, dayWidth - 8);

        const pill = document.createElement("a");
        pill.className = "meeting-pill";
        pill.style.top = `${top}px`;
        pill.style.left = `${left}px`;
        pill.style.width = `${width}px`;
        pill.style.height = `${height}px`;
        if (meeting.externalLink && /^https?:\/\//i.test(meeting.externalLink)) {
          pill.href = meeting.externalLink;
          pill.target = "_blank";
          pill.rel = "noopener noreferrer";
        } else {
          pill.href = "#";
          pill.addEventListener("click", (e) => e.preventDefault());
        }
        const timeText = `${pad2(segStart.getHours())}:${pad2(segStart.getMinutes())}-${pad2(segEnd.getHours())}:${pad2(segEnd.getMinutes())}`;
        pill.innerHTML = `<span class="meeting-pill-title">${escapeHtml(meeting.title || "Событие")}</span><span class="meeting-pill-time">${timeText}</span>`;
        el.weekGrid.appendChild(pill);
      }
    }
  }

  function scheduleNowLine() {
    if (state.nowTimer) clearInterval(state.nowTimer);
    state.nowTimer = setInterval(() => {
      renderNowLine();
    }, 60 * 1000);
  }

  function renderNowLine() {
    if (!el.weekGrid) return;
    const oldLine = el.weekGrid.querySelector(".now-time-line");
    if (oldLine) oldLine.remove();

    const now = new Date();
    const weekStart = startOfDay(state.weekStart);
    const weekEnd = addDays(weekStart, 7);
    if (now < weekStart || now >= weekEnd) return;

    const minutes = now.getHours() * 60 + now.getMinutes();
    const top = (minutes / 60) * getRowHeight();

    const line = document.createElement("div");
    line.className = "now-time-line";
    line.style.top = `${top}px`;

    const dot = document.createElement("div");
    dot.className = "now-time-dot";
    line.appendChild(dot);
    el.weekGrid.appendChild(line);
  }

  async function request(path, init = {}, forcedBase = "") {
    const base = forcedBase || getApiBaseUrl();
    const auth = buildAuth();
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
    const bases = getApiBaseCandidates();

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

  function getMeetingEndpoints() {
    return getEndpointCandidates("meetings", ["/api/miniapp/meetings"]);
  }

  function apiErrorMessage(status) {
    if (status === 401) return "Нет доступа. Открой Mini App через Telegram";
    if (status === 404) return "API не найден. Проверь apiBaseUrl";
    return `Ошибка API (${status})`;
  }

  function getApiBaseUrl() {
    const queryBase = readQueryApiBase();
    if (queryBase) {
      try { localStorage.setItem("aical_api_base", queryBase); } catch {}
      return queryBase;
    }
    const cfgBase = readConfigApiBase();
    if (cfgBase) return cfgBase;
    const saved = readSavedApiBase();
    if (saved) return saved;
    return window.location.origin.replace(/\/+$/, "");
  }

  function getApiBaseCandidates() {
    const protocol = window.location.protocol || "http:";
    const host = window.location.hostname || "";
    const base = getApiBaseUrl();
    const explicitBase = readQueryApiBase() || readConfigApiBase() || readSavedApiBase();
    const list = [base];

    if (!explicitBase) {
      const inferredRenderBase = inferRenderMiniAppApiBase();
      if (inferredRenderBase) {
        list.push(inferredRenderBase);
      }
      if (host) {
        list.push(`${protocol}//${host}:8011`);
        list.push(`${protocol}//${host}:8010`);
        list.push(`${protocol}//${host}:8080`);
      }
      if (protocol !== "https:") {
        list.push("http://localhost:8011");
        list.push("http://127.0.0.1:8011");
        list.push("http://localhost:8010");
        list.push("http://127.0.0.1:8010");
        list.push("http://localhost:8080");
        list.push("http://127.0.0.1:8080");
      }
    } else {
      const origin = window.location.origin.replace(/\/+$/, "");
      if (origin && origin !== base) {
        list.push(origin);
      }
    }

    return Array.from(new Set(list.map((x) => String(x || "").trim().replace(/\/+$/, "")).filter(Boolean)));
  }

  function getEndpointCandidates(key, fallback) {
    const cfg = window.__APP_CONFIG__;
    const endpoints = cfg && cfg.endpoints && typeof cfg.endpoints === "object" ? cfg.endpoints : null;
    const value = endpoints && Array.isArray(endpoints[key]) ? endpoints[key] : null;
    return value && value.length ? value : fallback;
  }

  function buildAuth() {
    const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
    const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
    const unsafeUser = tg && tg.initDataUnsafe ? tg.initDataUnsafe.user : null;
    const telegramId = unsafeUser && unsafeUser.id ? String(unsafeUser.id) : getTelegramIdFromContext();
    if (telegramId) {
      saveTelegramId(telegramId);
    }
    const headers = {
      "X-Pinggy-No-Screen": "1"
    };
    if (initData) headers["X-Telegram-Init-Data"] = initData;
    return { headers, initData, telegramId };
  }

  function readQueryApiBase() {
    const params = new URLSearchParams(window.location.search);
    const queryBase = (params.get("apiBaseUrl") || "").trim();
    return queryBase ? queryBase.replace(/\/+$/, "") : "";
  }

  function readConfigApiBase() {
    const cfgBase = window.__APP_CONFIG__ && typeof window.__APP_CONFIG__.apiBaseUrl === "string"
      ? window.__APP_CONFIG__.apiBaseUrl.trim()
      : "";
    return cfgBase ? cfgBase.replace(/\/+$/, "") : "";
  }

  function readSavedApiBase() {
    try {
      const saved = (localStorage.getItem("aical_api_base") || "").trim();
      return saved ? saved.replace(/\/+$/, "") : "";
    } catch {
      return "";
    }
  }

  function inferRenderMiniAppApiBase() {
    const protocol = window.location.protocol || "https:";
    const hostname = window.location.hostname || "";
    if (!hostname || !hostname.endsWith(".onrender.com")) {
      return "";
    }
    if (hostname.includes("-frontend.")) {
      return `${protocol}//${hostname.replace("-frontend.", "-miniapp-api.")}`;
    }
    if (hostname.includes("frontend")) {
      return `${protocol}//${hostname.replace("frontend", "miniapp-api")}`;
    }
    return "";
  }

  function saveTelegramId(value) {
    try {
      if (!value) return;
      localStorage.setItem(TELEGRAM_ID_STORAGE_KEY, String(value));
    } catch {
      // ignore
    }
  }

  function setScheduleStatus(message) {
    if (el.scheduleStatus) el.scheduleStatus.textContent = message || "";
  }

  function getTimeColWidth() {
    const cell = el.weekGrid ? el.weekGrid.querySelector(".time-cell") : null;
    if (!cell) return 64;
    const w = cell.getBoundingClientRect().width;
    return Number.isFinite(w) && w > 0 ? w : 64;
  }

  function getDayColWidth(timeColWidth) {
    if (!el.weekGrid) return 0;
    const total = el.weekGrid.getBoundingClientRect().width;
    const available = total - timeColWidth;
    return available / 7;
  }

  function getRowHeight() {
    const firstCell = el.weekGrid ? el.weekGrid.querySelector(".time-cell") : null;
    if (!firstCell) return 52;
    const h = firstCell.getBoundingClientRect().height;
    return Number.isFinite(h) && h > 0 ? h : 52;
  }

  function toYmd(date) {
    const y = date.getFullYear();
    const m = pad2(date.getMonth() + 1);
    const d = pad2(date.getDate());
    return `${y}-${m}-${d}`;
  }

  function startOfWeek(date) {
    const d = startOfDay(date);
    const day = (d.getDay() + 6) % 7;
    return addDays(d, -day);
  }

  function startOfDay(date) {
    const d = new Date(date);
    d.setHours(0, 0, 0, 0);
    return d;
  }

  function addDays(date, days) {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    return d;
  }

  function sameDay(a, b) {
    return (
      a.getFullYear() === b.getFullYear() &&
      a.getMonth() === b.getMonth() &&
      a.getDate() === b.getDate()
    );
  }

  function monthShort(date) {
    return date.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
  }

  function pad2(n) {
    return String(n).padStart(2, "0");
  }

  function isValidDate(value) {
    return value instanceof Date && !Number.isNaN(value.getTime());
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
