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
    nextBtn: document.getElementById("nextBtn")
  };

  const state = {
    weekStart: startOfWeek(new Date())
  };

  init();

  function init() {
    renderMenu();
    bind();
    renderWeek();
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

    const blank = document.createElement("div");
    blank.className = "week-head-cell";
    el.weekHead.appendChild(blank);

    for (let i = 0; i < 7; i++) {
      const d = addDays(state.weekStart, i);
      const cell = document.createElement("div");
      cell.className = "week-head-cell";
      cell.innerHTML = `<span>${d.getDate()}</span><span class="dow">${dayNames[i]}</span>`;
      el.weekHead.appendChild(cell);
    }
  }

  function renderGrid() {
    el.weekGrid.innerHTML = "";

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
        dayCell.style.gridRow = String(hour + 1);
        dayCell.style.gridColumn = String(day + 2);
        el.weekGrid.appendChild(dayCell);
      }
    }
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

  function monthShort(date) {
    return date.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
  }
})();
