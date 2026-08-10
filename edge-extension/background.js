const GSXT_URL = "https://www.gsxt.gov.cn/";
const APP_URL = "http://127.0.0.1:8080";
const GSXT_PATTERNS = ["https://www.gsxt.gov.cn/*", "https://*.gsxt.gov.cn/*"];
const SESSION_POLL_ALARM = "companyCollectorSessionPoll";
// GSXT can report the tab as complete before its result list is rendered.
// These checks only inspect the already-open page; they do not submit any
// additional request for the same enterprise.
const MAX_PAGE_STATE_CHECKS = 13;
const PAGE_STATE_CHECK_INTERVAL_MS = 4000;
const MIN_COOLDOWN_MS = 45_000;
const MAX_CAPTCHA_SOLVE_ATTEMPTS = 3;

let running = false;
let autoConnectRunning = false;
let autoCaptchaPreviousPicId = null;
const processingTabs = new Set();

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function isGsxtUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:"
      && (url.hostname === "gsxt.gov.cn" || url.hostname.endsWith(".gsxt.gov.cn"));
  } catch (_) {
    return false;
  }
}

function normalizeConfig(config) {
  return {
    appUrl: String(config?.appUrl || APP_URL).replace(/\/+$/, ""),
    accessToken: String(config?.accessToken || "").trim(),
    jobId: String(config?.jobId || "").trim(),
    sessionId: String(config?.sessionId || "").trim()
  };
}

async function api(config, path, options = {}) {
  const response = await fetch(config.appUrl + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Extension-Token": config.accessToken,
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = {message: text}; }
  if (!response.ok) {
    const error = new Error(data?.message || data?.error || "本地项目返回 HTTP " + response.status);
    error.status = response.status;
    throw error;
  }
  return data;
}

async function setState(status, message, target = null, level = "", candidates = null) {
  const collectorState = {status, message, target, level, candidates, updatedAt: new Date().toISOString()};
  await chrome.storage.local.set({collectorState});
  try {
    const stored = await chrome.storage.local.get("collectorConfig");
    const config = normalizeConfig(stored.collectorConfig);
    if (config.sessionId && config.accessToken) {
      await api(config, "/api/edge-extension/verification-sessions/"
        + encodeURIComponent(config.sessionId) + "/status", {
        method: "POST",
        body: JSON.stringify({
          status,
          message,
          currentCompanyName: target?.companyName || null,
          candidates: Array.isArray(candidates) ? candidates : null
        })
      });
    }
  } catch (_) {
    // A later alarm can reconnect if the local project restarts or the session expires.
  }
  return collectorState;
}

async function claimActiveSession() {
  const response = await fetch(APP_URL + "/api/edge-extension/verification-sessions/active", {
    headers: {"Content-Type": "application/json"}
  });
  if (response.status === 204) return null;
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = {message: text}; }
  if (!response.ok) throw new Error(data?.message || "本地项目返回 HTTP " + response.status);
  return data;
}

async function nextTarget(config) {
  return api(config, "/api/edge-extension/jobs/" + encodeURIComponent(config.jobId) + "/next");
}

/**
 * The extension owns one inactive GSXT tab. It does not focus an existing
 * official-site tab, so the user remains on the project page.
 */
async function findOrOpenGsxtTab(preferredTabId = null, background = true) {
  if (preferredTabId != null) {
    try {
      const preferred = await chrome.tabs.get(preferredTabId);
      if (isGsxtUrl(preferred.url)) {
        if (!background) await chrome.tabs.update(preferred.id, {active: true});
        return preferred;
      }
    } catch (_) {
      // The managed tab was closed.
    }
  }
  if (!background) {
    const active = await chrome.tabs.query({active: true, currentWindow: true, url: GSXT_PATTERNS});
    if (active.length) return active[0];
  }
  return chrome.tabs.create({url: GSXT_URL, active: false});
}

async function waitForTab(tabId, timeoutMs = 30000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const tab = await chrome.tabs.get(tabId);
    if (tab.status === "complete") return tab;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error("等待官网页面加载超时，当前企业已转为待人工处理。");
}

async function sendToPage(tabId, message) {
  try {
    return await chrome.tabs.sendMessage(tabId, message);
  } catch (_) {
    await chrome.scripting.executeScript({target: {tabId}, files: ["content.js"]});
    return chrome.tabs.sendMessage(tabId, message);
  }
}

async function moveToNextTarget(config, tabId, result) {
  if (!result.nextTarget) {
    running = false;
    await chrome.storage.local.set({collectorActive: false});
    await setState("COMPLETED", result.message, null, "ok");
    return;
  }
  autoCaptchaPreviousPicId = null;
  const cooldownMillis = Math.max(MIN_COOLDOWN_MS, Number(result.cooldownMillis || 0));
  const waitSeconds = Math.ceil(cooldownMillis / 1000);
  await chrome.storage.local.set({lastCompanyCompletedAt: Date.now()});
  await setState("COOLDOWN", result.message + "。为保护官网访问，约 " + waitSeconds + " 秒后继续。",
    result.nextTarget, "ok");
  await new Promise(resolve => setTimeout(resolve, cooldownMillis));
  const active = await chrome.storage.local.get("collectorActive");
  if (!running || !active.collectorActive) return;

  let next;
  try {
    next = await nextTarget(config);
  } catch (error) {
    if (error.status === 429) {
      await setState("COOLDOWN", error.message, result.nextTarget, "warn");
      return;
    }
    throw error;
  }
  if (!next) {
    running = false;
    await chrome.storage.local.set({collectorActive: false});
    await setState("COMPLETED", "当前企业任务已经全部处理完成", null, "ok");
    return;
  }
  await setState("NEXT_COMPANY", result.message, next, "ok");
  await chrome.tabs.update(tabId, {url: GSXT_URL, active: false});
}

async function deferCurrentCompany(config, tabId, target, reason) {
  const path = "/api/edge-extension/jobs/" + encodeURIComponent(config.jobId)
    + "/companies/" + target.companyId + "/defer";
  const result = await api(config, path, {
    method: "POST",
    body: JSON.stringify({reason})
  });
  await moveToNextTarget(config, tabId, result);
}

async function waitForManualContinue(config, target, reason) {
  await setState("WAITING_MANUAL", reason, target, "warn");
  const path = "/api/edge-extension/verification-sessions/"
    + encodeURIComponent(config.sessionId);
  while (running) {
    const state = await chrome.storage.local.get("collectorActive");
    if (!state.collectorActive) return false;
    let session;
    try {
      session = await api(config, path);
    } catch (_) {
      return false;
    }
    if (session?.status === "RUNNING") {
      await setState("RUNNING", "已收到人工确认，正在重新识别当前官网页面。", target);
      return true;
    }
    if (["BLOCKED", "COMPLETED", "ERROR", "STOPPED"].includes(session?.status)) return false;
    await new Promise(resolve => setTimeout(resolve, 1500));
  }
  return false;
}

async function captureAndSave(config, tabId, target, force = false) {
  await setState("COLLECTING", "正在读取并解析当前企业详情页…", target);
  const capture = await sendToPage(tabId, {type: force ? "COLLECT_DETAIL_FORCE" : "COLLECT_DETAIL", target});
  if (!capture?.pageText || capture.pageText.length < 80) {
    await deferCurrentCompany(config, tabId, target,
      capture?.message || "未能读取可确认的企业详情页，待人工补录");
    return;
  }
  const path = "/api/edge-extension/jobs/" + encodeURIComponent(config.jobId)
    + "/companies/" + target.companyId + "/capture";
  const result = await api(config, path, {
    method: "POST",
    body: JSON.stringify({pageText: capture.pageText, currentUrl: capture.currentUrl})
  });
  if (!result.completed) {
    await deferCurrentCompany(config, tabId, target,
      result.message || "页面已解析但企业身份无法可靠确认，待人工复核");
    return;
  }
  await moveToNextTarget(config, tabId, result);
}

async function waitForCandidateSelection(config) {
  const path = "/api/edge-extension/verification-sessions/"
    + encodeURIComponent(config.sessionId) + "/selection";
  while (running) {
    const state = await chrome.storage.local.get("collectorActive");
    if (!state.collectorActive) return null;
    const selection = await api(config, path);
    if (selection) return selection;
    await new Promise(resolve => setTimeout(resolve, 1500));
  }
  return null;
}

async function openSelectedCandidate(config, tabId, target, candidates) {
  await setState("WAITING_SELECTION", "请在项目页面选择要采集的企业。", target, "warn", candidates);
  const selection = await waitForCandidateSelection(config);
  if (!selection) return;
  await setState("SEARCHING", "已收到企业选择，正在后台打开企业详情。", target);
  const result = await sendToPage(tabId, {
    type: "OPEN_SELECTED_CANDIDATE",
    target,
    candidateId: selection.candidateId
  });
  if (result?.status === "SEARCH_SUBMITTED") {
    await new Promise(resolve => setTimeout(resolve, PAGE_STATE_CHECK_INTERVAL_MS));
    await processTarget(config, tabId, target);
    return;
  }
  await deferCurrentCompany(config, tabId, target,
    result?.message || "所选企业详情无法打开，待人工补录");
}

/**
 * Asks the local project to solve the current official-site CAPTCHA through
 * Super Eagle and applies the returned text/coordinates/slider result to the
 * already-open page. The project still owns all credentials and rate limits.
 */
async function tryAutoSolveCaptcha(config, tabId, target) {
  await setState("CAPTCHA_AUTO", "正在通过超级鹰自动识别官网验证码。", target, "ok");
  let capture;
  try {
    capture = await sendToPage(tabId, {type: "CAPTURE_CAPTCHA", target});
  } catch (error) {
    return {solved: false, reason: "无法截取验证码图片：" + (error.message || String(error))};
  }
  if (!capture?.base64) {
    return {solved: false, reason: capture?.message || "未能截取验证码图片"};
  }
  try {
    const result = await api(config, "/api/edge-extension/captcha/solve", {
      method: "POST",
      body: JSON.stringify({
        jobId: config.jobId,
        imageBase64: capture.base64,
        previousPicId: autoCaptchaPreviousPicId
      })
    });
    if (!result?.solved) {
      return {solved: false, reason: result?.message || "识别服务未返回结果"};
    }
    autoCaptchaPreviousPicId = result.picId || null;
    const applied = await sendToPage(tabId, {
      type: "APPLY_CAPTCHA_SOLUTION",
      solution: result,
      target
    });
    if (!applied || applied.status === "CAPTCHA_FAILED") {
      return {solved: false, reason: applied?.message || "识别结果无法应用到页面"};
    }
    return {solved: true, reason: applied?.message || "已应用识别结果"};
  } catch (error) {
    return {solved: false, reason: error.message || String(error)};
  }
}

async function processTarget(config, tabId, target) {
  if (!running) return;
  let lastResult = null;
  let captchaAttempts = 0;
  for (let attempt = 0; attempt < MAX_PAGE_STATE_CHECKS; attempt++) {
    const initial = attempt === 0;
    await setState(initial ? "RUNNING" : "SEARCHING",
      initial ? "正在后台检查官网页面并准备查询…" : "正在等待官网返回查询结果…", target);
    if (!initial) {
      await new Promise(resolve => setTimeout(resolve, PAGE_STATE_CHECK_INTERVAL_MS));
    }
    const result = await sendToPage(tabId, {type: "AUTOMATE_COMPANY", target});
    if (!result) throw new Error("扩展未收到官网页面响应");
    lastResult = result;
    if (result.status === "DETAIL_READY") {
      await captureAndSave(config, tabId, target);
      return;
    }
    if (result.status === "SEARCH_SUBMITTED" || result.status === "SEARCH_PENDING") {
      await setState("SEARCHING", result.message
        + `（已进行 ${attempt + 1}/${MAX_PAGE_STATE_CHECKS} 次页面识别，不会重复提交查询）`, target);
      continue;
    }
    if (result.status === "WAITING_SELECTION") {
      await openSelectedCandidate(config, tabId, target, result.candidates || []);
      return;
    }
    if (result.status === "CHALLENGE_REQUIRED") {
      captchaAttempts += 1;
      if (captchaAttempts <= MAX_CAPTCHA_SOLVE_ATTEMPTS) {
        const auto = await tryAutoSolveCaptcha(config, tabId, target);
        if (auto.solved) {
          await setState("RUNNING", "已提交验证码识别结果，正在等待官网刷新。", target, "ok");
          await sleep(PAGE_STATE_CHECK_INTERVAL_MS + 2000);
          continue;
        }
        await setState("SEARCHING",
          "自动验证码识别未通过：" + (auto.reason || "识别服务未返回结果") + "。", target, "warn");
      }
      const resumed = await waitForManualContinue(config, target,
        "官网要求验证码或安全验证。已尝试自动识别但仍未通过；请完成验证后点击“验证完成后继续采集”；系统不会重复提交企业名称。");
      if (resumed) return processTarget(config, tabId, target);
      return;
    }
    if (result.status === "BLOCKED") {
      await setState("BLOCKED", result.message, target, "warn");
      running = false;
      await chrome.storage.local.set({collectorActive: false});
      return;
    }
    if (result.status === "NO_RESULTS") {
      await deferCurrentCompany(config, tabId, target,
        result.message || "官网明确未返回匹配企业，待人工核对企业名称或统一社会信用代码");
      return;
    }
    if (result.status === "WAITING_MANUAL") {
      const resumed = await waitForManualContinue(config, target,
        result.message || "官网页面已变化，但验证助手无法可靠识别结果。请确认当前官网页面后继续采集。");
      if (resumed) return processTarget(config, tabId, target);
      return;
    }
    await deferCurrentCompany(config, tabId, target,
      result.message || "后台自动查询未能继续，待人工补录");
    return;
  }
  const diagnostic = lastResult?.message || "官网结果列表仍未出现可识别内容";
  const resumed = await waitForManualContinue(config, target,
    "官网结果尚未进入可识别状态：" + diagnostic
      + "。请确认官网页面已完成验证或已显示结果，再点击“验证完成后继续采集”。");
  if (resumed) return processTarget(config, tabId, target);
}

async function runTarget(config, tabId, target) {
  if (processingTabs.has(tabId)) return;
  processingTabs.add(tabId);
  try {
    await processTarget(config, tabId, target);
  } finally {
    processingTabs.delete(tabId);
  }
}

async function connectActiveSession(tabId = null, background = true) {
  if (autoConnectRunning) return null;
  autoConnectRunning = true;
  try {
    const claim = await claimActiveSession();
    if (!claim) return null;
    autoCaptchaPreviousPicId = null;
    const stored = await chrome.storage.local.get(["collectorConfig", "collectorState", "collectorTabId"]);
    const existingConfig = normalizeConfig(stored.collectorConfig);
    const sameSession = existingConfig.sessionId === claim.sessionId;
    if (sameSession && ["BLOCKED", "COOLDOWN", "COLLECTING", "NEXT_COMPANY", "COMPLETED", "STOPPED", "ERROR"]
      .includes(stored.collectorState?.status)) {
      return stored.collectorState;
    }
    const config = normalizeConfig({
      appUrl: APP_URL,
      accessToken: claim.accessToken,
      jobId: claim.jobId,
      sessionId: claim.sessionId
    });
    running = true;
    const tab = tabId != null
      ? await chrome.tabs.get(tabId)
      : await findOrOpenGsxtTab(stored.collectorTabId, background);
    await chrome.storage.local.set({
      collectorConfig: config,
      collectorActive: true,
      collectorTabId: tab.id
    });
    const target = sameSession && stored.collectorState?.target
      ? stored.collectorState.target : await nextTarget(config);
    if (!target) {
      running = false;
      await chrome.storage.local.set({collectorActive: false});
      return setState("COMPLETED", "当前企业任务已经全部处理完成", null, "ok");
    }
    await waitForTab(tab.id);
    await runTarget(config, tab.id, target);
    return (await chrome.storage.local.get("collectorState")).collectorState;
  } finally {
    autoConnectRunning = false;
  }
}

async function continueCurrent(configInput, forceCapture = false) {
  const config = normalizeConfig(configInput);
  running = true;
  await chrome.storage.local.set({collectorActive: true});
  const stored = await chrome.storage.local.get(["collectorState", "collectorTabId"]);
  const target = stored.collectorState?.target || await nextTarget(config);
  if (!target) {
    running = false;
    await chrome.storage.local.set({collectorActive: false});
    return setState("COMPLETED", "当前企业任务已经全部处理完成", null, "ok");
  }
  const tab = await findOrOpenGsxtTab(stored.collectorTabId, true);
  await chrome.storage.local.set({collectorTabId: tab.id});
  await waitForTab(tab.id);
  if (!forceCapture) {
    await setState("RUNNING", "正在按用户确认重新识别当前官网页面。", target);
  }
  if (forceCapture) await captureAndSave(config, tab.id, target, true);
  else await runTarget(config, tab.id, target);
  return (await chrome.storage.local.get("collectorState")).collectorState;
}

async function stopCollector() {
  running = false;
  await chrome.storage.local.set({collectorActive: false});
  return setState("STOPPED", "已停止后台采集。请勿连续刷新或立即重试官网查询。", null, "warn");
}

function installSessionPolling() {
  chrome.alarms.create(SESSION_POLL_ALARM, {periodInMinutes: 0.5});
}

chrome.runtime.onInstalled.addListener(installSessionPolling);
chrome.runtime.onStartup.addListener(installSessionPolling);
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === SESSION_POLL_ALARM) {
    connectActiveSession(null, true).catch(() => {});
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  let work = null;
  if (message.type === "COLLECTOR_AUTO_CONNECT") work = connectActiveSession(null, true);
  else if (message.type === "GSXT_PAGE_READY") {
    work = (async () => {
      const stored = await chrome.storage.local.get(["collectorActive", "collectorTabId"]);
      if (!stored.collectorActive || stored.collectorTabId !== sender.tab?.id) return null;
      return connectActiveSession(sender.tab.id, true);
    })();
  } else if (message.type === "COLLECTOR_CONTINUE") work = continueCurrent(message.config, false);
  else if (message.type === "COLLECTOR_CAPTURE_CURRENT") work = continueCurrent(message.config, true);
  else if (message.type === "COLLECTOR_STOP") work = stopCollector();
  if (!work) return false;
  work.then(state => sendResponse({state}))
    .catch(async error => {
      const state = await setState("ERROR", error.message || String(error), null, "error");
      sendResponse({error: state.message, state});
    });
  return true;
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (changeInfo.status !== "complete" || !isGsxtUrl(tab.url)) return;
  const stored = await chrome.storage.local.get(
    ["collectorConfig", "collectorState", "collectorActive", "collectorTabId"]);
  if (!stored.collectorActive || stored.collectorTabId !== tabId) return;
  running = true;
  const state = stored.collectorState;
  if (["WAITING_SELECTION", "COOLDOWN", "BLOCKED", "STOPPED", "COMPLETED", "ERROR"]
    .includes(state?.status)) return;
  if (!stored.collectorConfig || !state?.target) return;
  try {
    await runTarget(normalizeConfig(stored.collectorConfig), tabId, state.target);
  } catch (error) {
    await setState("ERROR", error.message || String(error), state.target, "error");
  }
});

chrome.tabs.onRemoved.addListener(async tabId => {
  const stored = await chrome.storage.local.get(["collectorTabId", "collectorActive", "collectorState"]);
  if (stored.collectorTabId !== tabId) return;
  running = false;
  await chrome.storage.local.set({collectorActive: false});
  await setState("STOPPED", "后台采集标签页已关闭。需要时可重新开始后台查询。",
    stored.collectorState?.target, "warn");
});
