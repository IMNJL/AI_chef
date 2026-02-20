(() => {
  const menuItems = [
    { icon: "▦", label: "Расписание", href: "index.html", key: "schedule" },
    { icon: "✓", label: "Задачи", href: "tasks.html", key: "tasks" },
    { icon: "✎", label: "Заметки", href: "notes.html", key: "notes" }
  ];

  const page = document.body.dataset.page || "schedule";

  const el = {
    menu: document.getElementById("menu"),
    menuToggle: document.getElementById("menuToggle"),
    sidebarBackdrop: document.getElementById("sidebarBackdrop"),
    userName: document.getElementById("userName"),
    userId: document.getElementById("userId"),
    userAvatar: document.getElementById("userAvatar")
  };

  init();

  function init() {
    renderMenu();
    bindSidebar();
    hydrateUser();
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

  window.AiCalCommon = {
    getApiBaseUrl,
    buildAuth
  };

  function getApiBaseUrl() {
    return window.location.origin;
  }

  function buildAuth() {
    const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
    const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
    const telegramId = getTelegramIdFromUrl();
    const headers = {};
    if (initData) {
      headers["X-Telegram-Init-Data"] = initData;
    }
    return { headers, initData, telegramId };
  }
})();
