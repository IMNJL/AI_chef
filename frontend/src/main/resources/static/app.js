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
  const WAKEUP_TIMEOUT_MS = 45000;
  const WAKEUP_STEP_MS = 2500;

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
    scheduleStatus: document.getElementById("scheduleStatus"),
    eventModal: document.getElementById("eventModal"),
    eventCloseBtn: document.getElementById("eventCloseBtn"),
    eventMoreBtn: document.getElementById("eventMoreBtn"),
    eventMoreMenu: document.getElementById("eventMoreMenu"),
    eventDuplicateBtn: document.getElementById("eventDuplicateBtn"),
    eventDeleteBtn: document.getElementById("eventDeleteBtn"),
    eventEditBtn: document.getElementById("eventEditBtn"),
    eventTitle: document.getElementById("eventTitle"),
    eventDateTime: document.getElementById("eventDateTime"),
    eventModalStatus: document.getElementById("eventModalStatus"),
    eventEditForm: document.getElementById("eventEditForm"),
    eventEditTitle: document.getElementById("eventEditTitle"),
    eventEditDate: document.getElementById("eventEditDate"),
    eventEditTime: document.getElementById("eventEditTime"),
    eventEditDuration: document.getElementById("eventEditDuration"),
    eventEditColor: document.getElementById("eventEditColor"),
    eventSubmitBtn: document.getElementById("eventSubmitBtn")
  };

  const state = {
    weekStart: startOfWeek(new Date()),
    meetings: [],
    nowTimer: null,
    activeMeetingId: "",
    editMode: false,
    formMode: "edit",
    wakeUpDone: false
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
    bindEventModal();
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

  function bindEventModal() {
    if (el.eventCloseBtn) {
      el.eventCloseBtn.addEventListener("click", closeEventModal);
    }
    if (el.eventModal) {
      el.eventModal.addEventListener("click", (e) => {
        if (e.target === el.eventModal) {
          closeEventModal();
        }
      });
    }
    if (el.eventMoreBtn) {
      el.eventMoreBtn.addEventListener("click", () => {
        if (!el.eventMoreMenu) return;
        el.eventMoreMenu.classList.toggle("hidden");
      });
    }
    if (el.eventDuplicateBtn) {
      el.eventDuplicateBtn.addEventListener("click", onDuplicateMeeting);
    }
    if (el.eventDeleteBtn) {
      el.eventDeleteBtn.addEventListener("click", onDeleteMeeting);
    }
    if (el.eventEditBtn) {
      el.eventEditBtn.addEventListener("click", () => {
        const nextMode = !state.editMode;
        setEditMode(nextMode);
        if (nextMode && el.eventEditTitle) {
          window.setTimeout(() => {
            el.eventEditTitle.focus();
            el.eventEditTitle.select();
          }, 0);
        }
      });
    }
    if (el.eventEditForm) {
      el.eventEditForm.addEventListener("submit", onSaveMeetingEdit);
    }
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && el.eventModal && !el.eventModal.classList.contains("hidden")) {
        closeEventModal();
      }
    });
    document.addEventListener("click", (e) => {
      if (!el.eventMoreMenu || !el.eventMoreBtn) return;
      if (el.eventMoreMenu.classList.contains("hidden")) return;
      const target = e.target;
      if (target instanceof Node && (el.eventMoreMenu.contains(target) || el.eventMoreBtn.contains(target))) return;
      el.eventMoreMenu.classList.add("hidden");
    });
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
    if (!state.wakeUpDone) {
      await warmUpBackends();
      state.wakeUpDone = true;
    }
    setScheduleStatus("Подготавливаю события и синхронизирую календарь...");
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

        const pill = document.createElement("button");
        pill.type = "button";
        pill.className = "meeting-pill";
        applyMeetingColor(pill, meeting.color);
        pill.style.top = `${top}px`;
        pill.style.left = `${left}px`;
        pill.style.width = `${width}px`;
        pill.style.height = `${height}px`;
        pill.innerHTML = `<span class="meeting-pill-title">${escapeHtml(meeting.title || "Событие")}</span>`;
        pill.addEventListener("click", () => {
          openEventModal(meeting.id);
        });
        el.weekGrid.appendChild(pill);
      }
    }
  }

  function openEventModal(meetingId) {
    const meeting = state.meetings.find((m) => String(m.id) === String(meetingId));
    if (!meeting || !el.eventModal) return;
    state.activeMeetingId = String(meeting.id || "");
    state.editMode = false;
    state.formMode = "edit";
    fillEventModal(meeting);
    setEventModalStatus("");
    setEditMode(false);
    el.eventModal.classList.remove("hidden");
  }

  function closeEventModal() {
    if (!el.eventModal) return;
    el.eventModal.classList.add("hidden");
    state.activeMeetingId = "";
    state.formMode = "edit";
    if (el.eventMoreMenu) el.eventMoreMenu.classList.add("hidden");
    setEventModalStatus("");
  }

  function fillEventModal(meeting) {
    const startsAt = new Date(meeting.startsAt);
    const endsAt = new Date(meeting.endsAt);
    const title = String(meeting.title || "Событие");
    if (el.eventTitle) {
      el.eventTitle.textContent = title;
    }
    if (el.eventDateTime) {
      el.eventDateTime.textContent = formatMeetingDateTimeLine(startsAt, endsAt);
    }
    if (el.eventEditTitle) {
      el.eventEditTitle.value = title;
    }
    if (el.eventEditDate) {
      el.eventEditDate.value = isValidDate(startsAt) ? toYmd(startsAt) : "";
    }
    if (el.eventEditTime) {
      el.eventEditTime.value = isValidDate(startsAt) ? `${pad2(startsAt.getHours())}:${pad2(startsAt.getMinutes())}` : "";
    }
    if (el.eventEditDuration) {
      const mins = isValidDate(startsAt) && isValidDate(endsAt) ? Math.max(5, Math.round((endsAt - startsAt) / 60000)) : 60;
      el.eventEditDuration.value = String(mins);
    }
    if (el.eventEditColor) {
      el.eventEditColor.value = normalizeHexColor(meeting.color) || "#93c5fd";
    }
  }

  function setEditMode(enabled) {
    state.editMode = Boolean(enabled);
    updateSubmitButtonText();
    if (el.eventEditForm) {
      el.eventEditForm.classList.toggle("hidden", !state.editMode);
    }
    if (el.eventEditBtn) {
      el.eventEditBtn.classList.toggle("active", state.editMode);
    }
  }

  async function onSaveMeetingEdit(e) {
    e.preventDefault();
    const activeMeeting = state.meetings.find((m) => String(m.id) === String(state.activeMeetingId));
    if (!activeMeeting) {
      setEventModalStatus("Событие не найдено");
      return;
    }

    const title = String(el.eventEditTitle && el.eventEditTitle.value ? el.eventEditTitle.value : "").trim();
    const date = String(el.eventEditDate && el.eventEditDate.value ? el.eventEditDate.value : "").trim();
    const time = String(el.eventEditTime && el.eventEditTime.value ? el.eventEditTime.value : "").trim();
    const duration = Number(el.eventEditDuration && el.eventEditDuration.value ? el.eventEditDuration.value : 0);
    const color = normalizeHexColor(el.eventEditColor && el.eventEditColor.value ? el.eventEditColor.value : "");
    if (!title || !date || !time || !Number.isFinite(duration) || duration <= 0) {
      setEventModalStatus("Проверьте название, дату, время и длительность");
      return;
    }
    if (!color) {
      setEventModalStatus("Некорректный цвет");
      return;
    }

    const startsAt = parseLocalDateTime(date, time);
    if (!startsAt) {
      setEventModalStatus("Некорректная дата или время");
      return;
    }
    const endsAt = new Date(startsAt.getTime() + duration * 60000);
    const payload = {
      title,
      startsAt: toOffsetIso(startsAt),
      endsAt: toOffsetIso(endsAt),
      color
    };

    setEventModalStatus("Сохраняю...");
    const saveTargets = state.formMode === "duplicate"
      ? getMeetingEndpoints()
      : getMeetingEndpoints().map((base) => `${base}/${activeMeeting.id}`);
    const saveVariants = state.formMode === "duplicate"
      ? [{ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }]
      : [
          { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
          { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }
        ];

    const res = await requestWithFallback(saveTargets, saveVariants);
    if (!res.success) {
      const detail = res.status ? ` (HTTP ${res.status})` : "";
      setEventModalStatus((res.message || "Ошибка сохранения, проверь доступ и формат даты/времени") + detail);
      return;
    }

    const wasDuplicate = state.formMode === "duplicate";
    if (wasDuplicate) {
      setEventModalStatus("Копия создана");
      state.formMode = "edit";
    } else {
      setEventModalStatus("Сохранено");
    }
    setEditMode(false);
    await loadMeetingsAndRender();
    const focusId = wasDuplicate
      ? String(res.data && res.data.id ? res.data.id : activeMeeting.id)
      : String(activeMeeting.id);
    const updated = state.meetings.find((m) => String(m.id) === focusId);
    if (updated) {
      state.activeMeetingId = String(updated.id || "");
      fillEventModal(updated);
    }
  }

  async function onDuplicateMeeting() {
    const activeMeeting = state.meetings.find((m) => String(m.id) === String(state.activeMeetingId));
    if (!activeMeeting) {
      setEventModalStatus("Событие не найдено");
      return;
    }
    if (el.eventMoreMenu) el.eventMoreMenu.classList.add("hidden");

    state.formMode = "duplicate";
    setEditMode(true);
    if (el.eventEditTitle) {
      const originalTitle = String(activeMeeting.title || "Событие").trim();
      el.eventEditTitle.value = `Копия: ${originalTitle}`;
      window.setTimeout(() => {
        el.eventEditTitle.focus();
        el.eventEditTitle.select();
      }, 0);
    }
    setEventModalStatus("Режим дублирования: укажите новое название, дату и время, затем сохраните.");
  }

  async function onDeleteMeeting() {
    const activeMeeting = state.meetings.find((m) => String(m.id) === String(state.activeMeetingId));
    if (!activeMeeting) {
      setEventModalStatus("Событие не найдено");
      return;
    }
    if (el.eventMoreMenu) el.eventMoreMenu.classList.add("hidden");
    const ok = window.confirm("Удалить это мероприятие?");
    if (!ok) return;

    setEventModalStatus("Удаляю...");
    const res = await requestWithFallback(
      getMeetingEndpoints().map((base) => `${base}/${activeMeeting.id}`),
      [{ method: "DELETE" }]
    );
    if (!res.success) {
      const detail = res.status ? ` (HTTP ${res.status})` : "";
      setEventModalStatus((res.message || "Ошибка удаления") + detail);
      return;
    }

    closeEventModal();
    await loadMeetingsAndRender();
    setScheduleStatus("Событие удалено");
  }

  function setEventModalStatus(message) {
    if (el.eventModalStatus) {
      el.eventModalStatus.textContent = message || "";
    }
  }

  function updateSubmitButtonText() {
    if (!el.eventSubmitBtn) return;
    el.eventSubmitBtn.textContent = state.formMode === "duplicate" ? "Создать копию" : "Сохранить";
  }

  function formatMeetingDateTimeLine(startsAt, endsAt) {
    if (!isValidDate(startsAt) || !isValidDate(endsAt)) {
      return "";
    }
    const weekday = startsAt.toLocaleDateString("ru-RU", { weekday: "long" });
    const day = startsAt.getDate();
    const month = startsAt.toLocaleDateString("ru-RU", { month: "long" });
    return `${capitalize(weekday)}, ${day} ${month} · ${pad2(startsAt.getHours())}:${pad2(startsAt.getMinutes())}-${pad2(endsAt.getHours())}:${pad2(endsAt.getMinutes())}`;
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
        const text = await response.text().catch(() => "");
        const baseMessage = apiErrorMessage(response.status);
        const details = text && text.length < 240 ? `: ${text}` : "";
        return { success: false, status: response.status, message: `${baseMessage}${details}` };
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
    if (status === 404) return "Календарный сервис поднимается. Данные скоро появятся.";
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
    const inferredRenderBase = inferRenderMiniAppApiBase();
    const list = [base];
    if (inferredRenderBase && inferredRenderBase !== base) {
      list.push(inferredRenderBase);
    }

    if (!explicitBase) {
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
      if (inferredRenderBase && inferredRenderBase !== origin) {
        list.push(inferredRenderBase);
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

  async function warmUpBackends() {
    const startedAt = Date.now();
    const bases = getApiBaseCandidates();
    const extras = getSiblingServiceOrigins();
    for (const origin of extras) {
      fireWakePing(origin);
    }
    while (Date.now() - startedAt < WAKEUP_TIMEOUT_MS) {
      for (const base of bases) {
        const ok = await probeApiBase(base);
        if (ok) {
          setScheduleStatus("Данные готовы, отображаю события...");
          return;
        }
      }
      const sec = Math.max(1, Math.round((Date.now() - startedAt) / 1000));
      setScheduleStatus(`Подключаю сервисы и собираю ваши данные (${sec}s)...`);
      await sleep(WAKEUP_STEP_MS);
    }
  }

  async function probeApiBase(base) {
    const auth = buildAuth();
    const url = new URL(base + "/api/miniapp/me");
    if (auth.telegramId) {
      url.searchParams.set("telegramId", auth.telegramId);
    }
    try {
      const response = await fetchWithTimeout(url.toString(), { headers: auth.headers }, 3500);
      return response.ok || response.status === 401 || response.status === 403 || response.status === 400;
    } catch {
      return false;
    }
  }

  function getSiblingServiceOrigins() {
    const protocol = window.location.protocol || "https:";
    const host = window.location.hostname || "";
    if (!host.endsWith(".onrender.com")) return [];
    const set = new Set();
    if (host.includes("-frontend.")) {
      set.add(`${protocol}//${host.replace("-frontend.", "-miniapp-api.")}`);
      set.add(`${protocol}//${host.replace("-frontend.", "-telegram.")}`);
    } else if (host.includes("frontend")) {
      set.add(`${protocol}//${host.replace("frontend", "miniapp-api")}`);
      set.add(`${protocol}//${host.replace("frontend", "telegram")}`);
    }
    return Array.from(set);
  }

  function fireWakePing(origin) {
    if (!origin) return;
    try {
      fetch(origin.replace(/\/+$/, "") + "/actuator/health", { mode: "no-cors", cache: "no-store" }).catch(() => {});
    } catch {
      // ignore
    }
  }

  function sleep(ms) {
    return new Promise((resolve) => window.setTimeout(resolve, ms));
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

  function parseLocalDateTime(date, time) {
    const value = `${date}T${time}`;
    const parsed = new Date(value);
    return isValidDate(parsed) ? parsed : null;
  }

  function toOffsetIso(date) {
    if (!isValidDate(date)) return null;
    const y = date.getFullYear();
    const m = pad2(date.getMonth() + 1);
    const d = pad2(date.getDate());
    const hh = pad2(date.getHours());
    const mm = pad2(date.getMinutes());
    const ss = pad2(date.getSeconds());
    const off = -date.getTimezoneOffset();
    const sign = off >= 0 ? "+" : "-";
    const abs = Math.abs(off);
    return `${y}-${m}-${d}T${hh}:${mm}:${ss}${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)}`;
  }

  function capitalize(value) {
    const s = String(value || "").trim();
    if (!s) return s;
    return s[0].toUpperCase() + s.slice(1);
  }

  function normalizeHexColor(value) {
    const v = String(value || "").trim();
    return /^#([0-9a-fA-F]{6})$/.test(v) ? v.toLowerCase() : "";
  }

  function applyMeetingColor(element, color) {
    if (!element) return;
    const safe = normalizeHexColor(color) || "#93c5fd";
    element.style.borderColor = safe;
    element.style.backgroundColor = withAlpha(safe, 0.3);
    element.style.color = "#1e3a8a";
  }

  function withAlpha(hex, alpha) {
    const safe = normalizeHexColor(hex);
    if (!safe) return "rgba(147,197,253,0.3)";
    const raw = safe.slice(1);
    const r = parseInt(raw.slice(0, 2), 16);
    const g = parseInt(raw.slice(2, 4), 16);
    const b = parseInt(raw.slice(4, 6), 16);
    const a = Number.isFinite(alpha) ? Math.max(0, Math.min(1, alpha)) : 0.3;
    return `rgba(${r}, ${g}, ${b}, ${a})`;
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
