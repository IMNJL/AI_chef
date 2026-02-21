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
    getApiBaseCandidates,
    buildAuth,
    getEndpointCandidates
  };

  function getApiBaseUrl() {
    const params = new URLSearchParams(window.location.search);
    const queryBase = (params.get("apiBaseUrl") || "").trim();
    if (queryBase) {
      try {
        localStorage.setItem("aical_api_base", queryBase);
      } catch {
        // ignore
      }
      return queryBase.replace(/\/+$/, "");
    }

    const cfgBase = window.__APP_CONFIG__ && typeof window.__APP_CONFIG__.apiBaseUrl === "string"
      ? window.__APP_CONFIG__.apiBaseUrl.trim()
      : "";
    if (cfgBase) {
      return cfgBase.replace(/\/+$/, "");
    }

    try {
      const saved = (localStorage.getItem("aical_api_base") || "").trim();
      if (saved) {
        return saved.replace(/\/+$/, "");
      }
    } catch {
      // ignore
    }

    return window.location.origin.replace(/\/+$/, "");
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

  function getApiBaseCandidates() {
    const protocol = window.location.protocol || "http:";
    const host = window.location.hostname || "";
    const list = [getApiBaseUrl()];

    if (host) {
      list.push(`${protocol}//${host}:8010`);
      list.push(`${protocol}//${host}:8080`);
    }
    list.push("http://localhost:8010");
    list.push("http://127.0.0.1:8010");
    list.push("http://localhost:8080");
    list.push("http://127.0.0.1:8080");

    return Array.from(new Set(list.map((x) => String(x || "").trim().replace(/\/+$/, "")).filter(Boolean)));
  }

  function getEndpointCandidates(key, fallback) {
    const cfg = window.__APP_CONFIG__;
    const endpoints = cfg && cfg.endpoints && typeof cfg.endpoints === "object" ? cfg.endpoints : null;
    const value = endpoints && Array.isArray(endpoints[key]) ? endpoints[key] : null;
    if (value && value.length) {
      return value;
    }
    return fallback;
  }
})();
