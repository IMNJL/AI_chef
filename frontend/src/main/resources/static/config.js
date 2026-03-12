(() => {
  const host = String(window.location.hostname || "").toLowerCase();
  const isLocalHost = host === "localhost" || host === "127.0.0.1" || host === "0.0.0.0";
  const sameOriginApiBase = String(window.location.origin || "").replace(/\/+$/, "");

  window.__APP_CONFIG__ = {
    apiBaseUrl: isLocalHost ? "http://localhost:8010" : sameOriginApiBase,
    endpoints: {
      meetings: ["/api/miniapp/meetings"],
      tasks: ["/api/miniapp/tasks"],
      notes: ["/api/miniapp/notes"]
    }
  };
})();
