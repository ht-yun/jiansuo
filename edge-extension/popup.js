const byId = id => document.getElementById(id);

async function load() {
  const stored = await chrome.storage.local.get("collectorState");
  render(stored.collectorState);
}

function render(state) {
  if (!state) {
    byId("status").textContent = "等待项目前端发起验证。";
    return;
  }
  const company = state.target?.companyName ? `\n当前企业：${state.target.companyName}` : "";
  byId("status").className = `status ${state.level || ""}`;
  byId("status").textContent = `${state.message || state.status || "等待操作"}${company}`;
}

async function send(type) {
  const buttons = [...document.querySelectorAll("button")];
  buttons.forEach(button => { button.disabled = true; });
  try {
    const stored = await chrome.storage.local.get("collectorConfig");
    const config = stored.collectorConfig;
    if (type !== "COLLECTOR_AUTO_CONNECT" && !config) {
      throw new Error("尚未连接验证任务，请先从项目前端点击“开始官网验证”。");
    }
    const response = await chrome.runtime.sendMessage({type, config});
    if (response?.error) {
      render({level: "error", message: response.error});
    } else if (response?.state) {
      render(response.state);
    }
  } catch (error) {
    render({level: "error", message: error.message || String(error)});
  } finally {
    buttons.forEach(button => { button.disabled = false; });
  }
}

byId("reconnect").addEventListener("click", () => send("COLLECTOR_AUTO_CONNECT"));
byId("continue").addEventListener("click", () => send("COLLECTOR_CONTINUE"));
byId("capture").addEventListener("click", () => send("COLLECTOR_CAPTURE_CURRENT"));
byId("stop").addEventListener("click", async () => {
  const response = await chrome.runtime.sendMessage({type: "COLLECTOR_STOP"});
  render(response?.state || {level: "warn", message: "已停止采集。"});
});
chrome.storage.onChanged.addListener(changes => {
  if (changes.collectorState) render(changes.collectorState.newValue);
});
load();
