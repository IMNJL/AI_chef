(() => {
  const host = String(window.location.hostname || "").toLowerCase();
  const isLocalHost = host === "localhost" || host === "127.0.0.1" || host === "0.0.0.0";

  window.__APP_CONFIG__ = {
    apiBaseUrl: isLocalHost ? "http://localhost:8010" : "",
    endpoints: {
      meetings: ["/api/miniapp/meetings"],
      tasks: ["/api/miniapp/tasks"],
      notes: ["/api/miniapp/notes"]
    }
  };
})();
