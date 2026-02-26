(() => {
  const API_REQUEST_TIMEOUT_MS = 7000;

  const el = {
    status: document.getElementById("profileStatus"),
    username: document.getElementById("profileUsername"),
    prefix: document.getElementById("profilePrefix"),
    timezone: document.getElementById("profileTimezone")
  };

  init();

  async function init() {
    const common = window.AiCalCommon;
    if (common && typeof common.wakeUpServices === "function") {
      await common.wakeUpServices((msg) => setStatus(msg));
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
    if (el.prefix) {
      el.prefix.textContent = data.titlePrefix || "-";
    }
    if (el.timezone) {
      el.timezone.textContent = data.timezone || "-";
    }
    setStatus("");
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
