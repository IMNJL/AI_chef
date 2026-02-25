(() => {
  const host = String(window.location.hostname || "").toLowerCase();
  const isLocalHost = host === "localhost" || host === "127.0.0.1" || host === "0.0.0.0";
  const renderApiBase = deriveRenderApiBase(window.location);

  window.__APP_CONFIG__ = {
    apiBaseUrl: isLocalHost ? "http://localhost:8010" : renderApiBase,
    endpoints: {
      meetings: ["/api/miniapp/meetings"],
      tasks: ["/api/miniapp/tasks"],
      notes: ["/api/miniapp/notes"]
    }
  };

  function deriveRenderApiBase(loc) {
    const protocol = String(loc && loc.protocol ? loc.protocol : "https:");
    const hostname = String(loc && loc.hostname ? loc.hostname : "");
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
})();
