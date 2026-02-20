(() => {
  const menuItems = [
    { icon: "⌂", label: "Главная" },
    { icon: "▦", label: "Расписание", active: true },
    { icon: "☰", label: "Зачётка" },
    { icon: "◍", label: "Учебный план" },
    { icon: "✎", label: "Запись по выбору" },
    { icon: "♡", label: "Спорт" },
    { icon: "▣", label: "Практики" },
    { icon: "◈", label: "Финансы" },
    { icon: "◉", label: "Персоналии" },
    { icon: "≣", label: "Заявки и очереди" },
    { icon: "◫", label: "Сервисы" }
  ];

  const dayNames = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];

  const el = {
    menu: document.getElementById("menu"),
    weekRange: document.getElementById("weekRange"),
    weekHead: document.getElementById("weekHead"),
    weekGrid: document.getElementById("weekGrid"),
    prevBtn: document.getElementById("prevBtn"),
    nextBtn: document.getElementById("nextBtn"),
    menuToggle: document.getElementById("menuToggle"),
    sidebarClose: document.getElementById("sidebarClose"),
    sidebarBackdrop: document.getElementById("sidebarBackdrop"),
    userName: document.getElementById("userName"),
    userId: document.getElementById("userId"),
    userAvatar: document.getElementById("userAvatar")
  };

  const state = {
    weekStart: startOfWeek(new Date()),
    nowTimer: null
  };

  init();

  function init() {
    hydrateUser();
    renderMenu();
    bind();
    renderWeek();
    scheduleNowLine();
  }

  function bind() {
    el.prevBtn.addEventListener("click", () => {
      state.weekStart = addDays(state.weekStart, -7);
      renderWeek();
    });

    el.nextBtn.addEventListener("click", () => {
      state.weekStart = addDays(state.weekStart, 7);
      renderWeek();
    });

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

    if (el.sidebarClose) {
      el.sidebarClose.addEventListener("click", () => {
        document.body.classList.remove("sidebar-open");
      });
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
    const userId = tgUser && tgUser.id ? String(tgUser.id) : getTelegramIdFromUrl();
    const photoUrl = tgUser && tgUser.photo_url ? String(tgUser.photo_url) : "";
    const username = tgUser && tgUser.username ? `@${tgUser.username}` : "";
    const displayName = username || (userId ? `id${userId}` : "user");

    if (el.userName) {
      el.userName.textContent = `Mr/Ms ${displayName}`;
    }
    if (el.userId) {
      el.userId.textContent = "";
    }

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

  function getTelegramIdFromUrl() {
    try {
      const params = new URLSearchParams(window.location.search);
      const value = params.get("telegramId") || params.get("tg") || "";
      const n = Number(value);
      return Number.isFinite(n) && n > 0 ? String(n) : "";
    } catch {
      return "";
    }
  }

  function renderMenu() {
    el.menu.innerHTML = "";
    for (const item of menuItems) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = `menu-item${item.active ? " active" : ""}`;
      btn.innerHTML = `<span class="menu-icon">${item.icon}</span><span>${item.label}</span>`;
      el.menu.appendChild(btn);
    }
  }

  function renderWeek() {
    renderRange();
    renderHead();
    renderGrid();
    renderNowLine();
  }

  function renderRange() {
    const start = state.weekStart;
    const end = addDays(start, 6);
    const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
    const startText = `${start.getDate()} ${monthShort(start)}`;
    const endText = `${end.getDate()} ${monthShort(end)}`;
    el.weekRange.textContent = sameMonth ? `${start.getDate()}-${end.getDate()} ${monthShort(end)}` : `${startText} - ${endText}`;
  }

  function renderHead() {
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

  function scheduleNowLine() {
    if (state.nowTimer) clearInterval(state.nowTimer);
    state.nowTimer = setInterval(() => {
      renderNowLine();
    }, 60 * 1000);
  }

  function renderNowLine() {
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

  function getRowHeight() {
    const firstCell = el.weekGrid.querySelector(".time-cell");
    if (!firstCell) return 52;
    const h = firstCell.getBoundingClientRect().height;
    return Number.isFinite(h) && h > 0 ? h : 52;
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
})();
