(() => {
  const menuItems = [
    { icon: "▦", label: "Расписание", href: "index.html", key: "schedule" },
    { icon: "✓", label: "Задачи", href: "tasks.html", key: "tasks" },
    { icon: "✎", label: "Заметки", href: "notes.html", key: "notes" }
  ];

  const page = document.body.dataset.page || "schedule";
  const PROFILE_REQUEST_TIMEOUT_MS = 1500;
  const TELEGRAM_ID_STORAGE_KEY = "aical_telegram_id";

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

  function getTelegramIdFromContext() {
    try {
      const params = new URLSearchParams(window.location.search);
      const value = params.get("telegramId") || params.get("tg") || "";
      const n = Number(value);
      if (Number.isFinite(n) && n > 0) {
        const id = String(n);
        saveTelegramId(id);
        return id;
      }
    } catch {
      // ignore
    }

    try {
      const saved = String(localStorage.getItem(TELEGRAM_ID_STORAGE_KEY) || "").trim();
      const n = Number(saved);
      return Number.isFinite(n) && n > 0 ? String(n) : "";
    } catch {
      return "";
    }
  }

  async function resolveTitlePrefix() {
    const auth = buildAuth();
    const bases = [getApiBaseUrl()];
    for (const base of bases) {
      try {
        const url = new URL(base + "/api/miniapp/me");
        if (!auth.initData && auth.telegramId) {
          url.searchParams.set("telegramId", auth.telegramId);
        }
        const response = await fetchWithTimeout(
          url.toString(),
          { headers: auth.headers },
          PROFILE_REQUEST_TIMEOUT_MS
        );
        if (!response.ok) {
          continue;
        }
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

  window.AiCalCommon = {
    getApiBaseUrl,
    getApiBaseCandidates,
    buildAuth,
    getEndpointCandidates
  };

  function getApiBaseUrl() {
    const queryBase = readQueryApiBase();
    if (queryBase) {
      try { localStorage.setItem("aical_api_base", queryBase); } catch {}
      return queryBase;
    }

    const cfgBase = readConfigApiBase();
    if (cfgBase) {
      return cfgBase;
    }

    const saved = readSavedApiBase();
    if (saved) {
      return saved;
    }

    return window.location.origin.replace(/\/+$/, "");
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
    if (initData) {
      headers["X-Telegram-Init-Data"] = initData;
    }
    return { headers, initData, telegramId };
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

  function getEndpointCandidates(key, fallback) {
    const cfg = window.__APP_CONFIG__;
    const endpoints = cfg && cfg.endpoints && typeof cfg.endpoints === "object" ? cfg.endpoints : null;
    const value = endpoints && Array.isArray(endpoints[key]) ? endpoints[key] : null;
    if (value && value.length) {
      return value;
    }
    return fallback;
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
