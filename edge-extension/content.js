(() => {
  if (globalThis.__companyCollectorLoaded) return;
  globalThis.__companyCollectorLoaded = true;

  const DETAIL_MARKERS = ["统一社会信用代码", "法定代表人", "登记机关", "登记状态", "经营范围"];
  const CHALLENGE_MARKERS = ["安全验证", "请完成验证", "拖动滑块", "点击完成验证", "访问过于频繁", "验证码"];
  const EMPTY_RESULT_MARKERS = ["未查询到", "没有查询到", "暂无查询结果", "无匹配结果", "未找到相关企业"];
  const RESULT_HINT_MARKERS = ["查询结果", "搜索结果", "企业信用信息", "共查询到", "条记录"];
  const RESULT_STABILIZATION_MS = 8000;
  const candidateElements = new Map();
  let currentTarget = null;

  function text() {
    return (document.body?.innerText || "").replace(/\u00a0/g, " ").trim();
  }

  function normalized(value) {
    return String(value || "").normalize("NFKC").replace(/\s+/g, "").toUpperCase();
  }

  function visible(element) {
    if (!element) return false;
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;
  }

  function blocked(body) {
    const lower = body.toLowerCase();
    return lower.includes("error 521") || lower.includes("web server is down")
      || lower.includes("403 forbidden") || lower.includes("access denied")
      || lower.includes("ip请求异常") || lower.includes("ip 地址异常")
      || lower.includes("请求异常，请稍后再试") || lower.includes("访问频繁")
      || lower.includes("操作过于频繁") || lower.includes("系统繁忙");
  }

  function challenged(body) {
    return CHALLENGE_MARKERS.some(marker => body.includes(marker))
      || [...document.querySelectorAll("iframe")].some(frame =>
        /captcha|verify|geetest/i.test(frame.src || frame.id || frame.className));
  }

  function detailPage(body, target) {
    const markerCount = DETAIL_MARKERS.filter(marker => body.includes(marker)).length;
    const expectedName = normalized(target?.companyName);
    const expectedCode = normalized(target?.creditCode);
    const full = normalized(body);
    const containsTarget = (!expectedName && !expectedCode)
      || (expectedName && full.includes(expectedName))
      || (expectedCode && full.includes(expectedCode));
    return markerCount >= 3 && containsTarget;
  }

  function looksLikeDetailPage(body) {
    return DETAIL_MARKERS.filter(marker => body.includes(marker)).length >= 3;
  }

  function setNativeValue(input, value) {
    const prototype = input instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (setter) setter.call(input, value); else input.value = value;
    input.dispatchEvent(new Event("input", {bubbles: true}));
    input.dispatchEvent(new Event("change", {bubbles: true}));
  }

  function searchInput() {
    const selectors = [
      "input[placeholder*='企业名称']",
      "input[placeholder*='统一社会信用代码']",
      "input[placeholder*='注册号']",
      "input[placeholder*='关键字']",
      "input[type='search']",
      "input[name*='keyword' i]",
      "input[id*='keyword' i]",
      "input[id*='search' i]"
    ];
    return selectors.flatMap(selector => [...document.querySelectorAll(selector)]).find(visible);
  }

  function clickableByText(labels, root = document) {
    return [...root.querySelectorAll("button,a,[role='button'],input[type='submit']")]
      .find(element => {
        if (!visible(element)) return false;
        const value = (element.innerText || element.value || "").trim().replace(/\s+/g, "");
        return labels.some(label => value === label || value.includes(label));
      });
  }

  function clickablesByText(labels) {
    return [...document.querySelectorAll("button,a,[role='button'],input[type='submit']")]
      .filter(element => {
        if (!visible(element)) return false;
        const value = (element.innerText || element.value || "").trim();
        return labels.some(label => value === label || value.includes(label));
      });
  }

  function candidateContainer(element) {
    return element.closest("tr,li,[class*='item' i],[class*='card' i],[class*='result' i]")
      || element.parentElement || element;
  }

  function fieldValue(source, label) {
    const match = source.match(new RegExp(label + "\\s*[：:]\\s*([^\\n\\r]{1,160})"));
    return match?.[1]?.trim() || "";
  }

  function interactiveCandidateElement(element) {
    if (element.matches?.("a,[role='link'],button,[role='button'],[onclick]")) return element;
    return element.querySelector?.("a[href],a,[role='link'],button,[role='button'],[onclick]")
      || element.closest?.("a,[role='link'],button,[role='button'],[onclick]")
      || element;
  }

  function candidateName(element, fullText, expected) {
    const direct = (element.innerText || element.textContent || "").trim();
    const matchingLine = (direct + "\n" + fullText).split(/\r?\n/)
      .map(line => line.trim())
      .find(line => normalized(line).includes(expected));
    return matchingLine || direct.split(/\r?\n/)[0].trim();
  }

  function resultCandidateElements(companyName) {
    const expected = normalized(companyName);
    if (!expected) return [];
    const selector = [
      "a", "[role='link']", "button", "[role='button']", "[onclick]", "[data-id]", "[data-name]",
      "h1,h2,h3,h4,h5", ".title",
      "[class*='name' i]", "[class*='company' i]", "[class*='enterprise' i]",
      "[class*='entname' i]", "[class*='ent-name' i]", "[class*='ent-info' i]",
      "[class*='result' i]", "[class*='item' i]"
    ].join(",");
    const elements = [...document.querySelectorAll(selector)]
      .filter(visible)
      .filter(element => {
        const value = normalized(element.innerText || element.textContent);
        // Exclude layout containers that contain the whole result page. Result
        // entries are normally short cards/rows and may include status text.
        return value.length >= expected.length && value.length <= 1400
          && (value === expected || value.includes(expected));
      })
      .map(interactiveCandidateElement);
    return [...new Set(elements)];
  }

  function candidateViews(companyName) {
    candidateElements.clear();
    const seen = new Set();
    return resultCandidateElements(companyName).map((element, index) => {
      const container = candidateContainer(element);
      const fullText = (container.innerText || element.innerText || "").trim();
      const name = candidateName(element, fullText, normalized(companyName));
      const creditCode = (fullText.toUpperCase().match(/[0-9A-Z]{18}/) || [""])[0];
      const anchor = element.closest("a[href]") || (element.matches?.("a[href]") ? element : null);
      const key = normalized(name) + "|" + creditCode + "|" + (anchor?.href || index);
      if (seen.has(key)) return null;
      seen.add(key);
      const candidateId = "candidate-" + index + "-" + (creditCode || normalized(name).slice(0, 48));
      candidateElements.set(candidateId, element);
      return {
        candidateId,
        companyName: name,
        creditCode,
        legalPerson: fieldValue(fullText, "法定代表人"),
        registrationStatus: fieldValue(fullText, "登记状态") || fieldValue(fullText, "经营状态"),
        registeredAddress: fieldValue(fullText, "住所") || fieldValue(fullText, "注册地址"),
        summary: fullText.replace(/\s+/g, " ").slice(0, 500)
      };
    }).filter(Boolean).slice(0, 20);
  }

  function openCandidate(element) {
    const anchor = element.closest("a[href]") || (element.matches?.("a[href]") ? element : null);
    if (anchor?.href && /^https?:/i.test(anchor.href)) {
      location.assign(anchor.href);
    } else {
      element.removeAttribute?.("target");
      element.click();
    }
  }

  function searchButton(input) {
    const scope = input.closest("form,[class*='search' i]") || input.parentElement?.parentElement;
    return clickableByText(["查询", "搜索"], scope || document) || clickableByText(["查询", "搜索"]);
  }

  async function clickExpandAll() {
    const clicked = new WeakSet();
    let count = 0;
    for (let round = 0; round < 2 && count < 8; round++) {
      const expands = clickablesByText(["全部展开", "展开全部"]).filter(item => !clicked.has(item));
      if (!expands.length) break;
      for (const expand of expands) {
        if (count >= 8) break;
        clicked.add(expand);
        expand.click();
        count++;
        await new Promise(resolve => setTimeout(resolve, 3000 + Math.floor(Math.random() * 2001)));
      }
    }
  }

  function submitSearch(input, target) {
    const keyword = target?.companyName || target?.creditCode;
    if (!keyword) return {status: "WAITING_MANUAL", message: "当前企业没有可查询的名称或统一社会信用代码。"};
    const key = String(target?.companyId || "") + ":" + normalized(keyword);
    const submittedKey = document.documentElement.getAttribute("data-company-collector-submit-key");
    if (submittedKey === key) {
      return {status: "SEARCH_PENDING", message: "查询已提交，正在等待官网返回结果。"};
    }
    if (normalized(input.value) !== normalized(keyword)) setNativeValue(input, keyword);
    document.documentElement.setAttribute("data-company-collector-submit-key", key);
    const button = searchButton(input);
    if (button) {
      button.click();
      return {status: "SEARCH_SUBMITTED", message: "已在后台提交企业查询。"};
    }
    if (input.form?.requestSubmit) {
      input.form.requestSubmit();
      return {status: "SEARCH_SUBMITTED", message: "已在后台提交企业查询。"};
    }
    return {status: "WAITING_MANUAL", message: "未能识别官网查询按钮，当前企业待人工补录。"};
  }

  function targetSearchKey(target) {
    const keyword = target?.companyName || target?.creditCode;
    return keyword ? String(target?.companyId || "") + ":" + normalized(keyword) : "";
  }

  function searchAlreadySubmitted(target) {
    const key = targetSearchKey(target);
    return Boolean(key) && document.documentElement.getAttribute("data-company-collector-submit-key") === key;
  }

  function hasResultSignal(body, target) {
    const expectedName = normalized(target?.companyName);
    const expectedCode = normalized(target?.creditCode);
    const content = normalized(body);
    const containsTarget = (expectedName && content.includes(expectedName))
      || (expectedCode && content.includes(expectedCode));
    return containsTarget && RESULT_HINT_MARKERS.some(marker => body.includes(marker));
  }

  function inspectPage(target) {
    const body = text();
    if (blocked(body)) {
      return {status: "BLOCKED", message: "官网提示 IP 请求异常或拒绝访问，已停止自动查询。"};
    }
    if (challenged(body)) {
      return {status: "CHALLENGE_REQUIRED", message: "官网要求验证码或安全验证。"};
    }
    if (detailPage(body, target)) {
      return {status: "DETAIL_READY", message: "已进入目标企业详情页，准备采集。"};
    }

    const candidates = candidateViews(target?.companyName);
    if (candidates.length === 1) {
      const key = String(target?.companyId || "") + ":" + candidates[0].candidateId;
      if (document.documentElement.getAttribute("data-company-collector-open-key") === key) {
        return {status: "WAITING_MANUAL", message: "官网已显示唯一候选企业，但详情页未能稳定打开。"};
      }
      document.documentElement.setAttribute("data-company-collector-open-key", key);
      openCandidate(candidateElements.get(candidates[0].candidateId));
      return {status: "SEARCH_SUBMITTED", message: "已在后台打开唯一匹配企业详情页。"};
    }
    if (candidates.length > 1) {
      return {status: "WAITING_SELECTION", message: "识别到多个企业候选项，请在项目页面选择。", candidates};
    }

    const submitted = searchAlreadySubmitted(target);
    if (submitted && EMPTY_RESULT_MARKERS.some(marker => body.includes(marker))) {
      return {status: "NO_RESULTS", message: "官网明确提示未查询到匹配企业。"};
    }
    if (submitted && hasResultSignal(body, target)) {
      return {status: "WAITING_MANUAL", message: "官网已显示查询结果，但当前页面结构未能识别出可点击的企业候选项。"};
    }

    const input = searchInput();
    if (!input) {
      return {status: "WAITING_MANUAL", message: submitted
        ? "查询已提交，但当前页面未识别到结果列表或详情信息。"
        : "未识别到官网查询输入框，可能仍在加载或页面结构已变化。"};
    }
    if (submitted) {
      return {status: "SEARCH_PENDING", message: "查询已提交，正在等待官网异步返回结果。"};
    }
    return {status: "SEARCH_READY", input};
  }

  function waitForPageOutcome(target, timeoutMs = RESULT_STABILIZATION_MS) {
    return new Promise(resolve => {
      let settled = false;
      let timer = null;
      let observer = null;
      const finish = result => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        observer?.disconnect();
        resolve(result);
      };
      const inspect = () => {
        const result = inspectPage(target);
        if (result.status !== "SEARCH_PENDING") finish(result);
      };
      observer = new MutationObserver(inspect);
      observer.observe(document.documentElement, {childList: true, subtree: true, characterData: true});
      timer = setTimeout(() => finish(inspectPage(target)), timeoutMs);
      inspect();
    });
  }

  async function automate(target) {
    currentTarget = target || currentTarget;
    const result = inspectPage(currentTarget);
    if (result.status === "SEARCH_READY") {
      const submitted = submitSearch(result.input, currentTarget);
      return submitted.status === "SEARCH_SUBMITTED"
        ? waitForPageOutcome(currentTarget) : submitted;
    }
    return result.status === "SEARCH_PENDING"
      ? waitForPageOutcome(currentTarget) : result;
  }

  async function openSelectedCandidate(candidateId, target) {
    currentTarget = target || currentTarget;
    let element = candidateElements.get(candidateId);
    if (!element) {
      candidateViews(target?.companyName);
      element = candidateElements.get(candidateId);
    }
    if (!element) {
      return {status: "WAITING_MANUAL", message: "企业候选项已失效，请稍后重新开始该企业。"};
    }
    openCandidate(element);
    return {status: "SEARCH_SUBMITTED", message: "已在后台打开所选企业详情。"};
  }

  async function collect(target, force = false) {
    let body = text();
    if (blocked(body)) return {message: "当前官网页面返回 521/403，无法采集。"};
    if (challenged(body)) return {message: "当前页面仍处于验证码或安全验证状态。"};
    if (!(force ? looksLikeDetailPage(body) : detailPage(body, target))) {
      return {message: "当前页面未识别为目标企业详情页。"};
    }
    await clickExpandAll();
    body = text();
    return {pageText: body, currentUrl: location.href};
  }

  function captchaRoot() {
    const frame = [...document.querySelectorAll("iframe")].find(element =>
      /captcha|verify|geetest|yidun|nc_|slide/i.test(
        (element.src || "") + " " + (element.id || "") + " " + (element.className || "")));
    if (frame?.contentDocument) return {root: frame.contentDocument, frame};
    return {root: document, frame: null};
  }

  function captchaImage(root) {
    const container = [...root.querySelectorAll("div,form,section,span,p")].find(element =>
      visible(element) && /验证码|安全验证|请完成验证|captcha|verify/i.test(element.innerText || "")
        && element.querySelector("img"));
    const scope = container || root;
    const images = [...scope.querySelectorAll("img")].filter(visible);
    const marked = images.find(img => {
      const source = (img.currentSrc || img.src || "") + " " + (img.id || "")
        + " " + (img.className || "");
      return /captcha|verify|verification|code|slide|yidun|geetest|nc_/i.test(source);
    });
    if (marked) return marked;
    return images
      .filter(img => (img.naturalWidth || img.width || 0) >= 120
        && (img.naturalHeight || img.height || 0) >= 40)
      .sort((a, b) => (b.naturalWidth || 0) * (b.naturalHeight || 0)
        - (a.naturalWidth || 0) * (a.naturalHeight || 0))[0] || null;
  }

  async function imageToDataUrl(img) {
    const source = img.currentSrc || img.src;
    if (!source) return null;
    if (/^data:image\//i.test(source)) return source;
    try {
      const response = await fetch(source, {credentials: "include", cache: "no-store"});
      if (!response.ok) throw new Error("HTTP " + response.status);
      const blob = await response.blob();
      return await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(blob);
      });
    } catch (_) {
      try {
        const canvas = document.createElement("canvas");
        canvas.width = img.naturalWidth || img.width || 300;
        canvas.height = img.naturalHeight || img.height || 100;
        canvas.getContext("2d").drawImage(img, 0, 0);
        return canvas.toDataURL("image/png");
      } catch (_) {
        return null;
      }
    }
  }

  async function captureCaptcha() {
    const {root} = captchaRoot();
    const img = captchaImage(root);
    if (!img) return {status: "CAPTCHA_FAILED", message: "未找到官网验证码图片"};
    const dataUrl = await imageToDataUrl(img);
    if (!dataUrl) return {status: "CAPTCHA_FAILED", message: "无法读取验证码图片"};
    const base64 = dataUrl.includes(",") ? dataUrl.split(",")[1] : dataUrl;
    return {status: "CAPTCHA_CAPTURED", base64};
  }

  function clickAtInRoot(root, x, y) {
    const doc = root?.defaultView ? root : document;
    const element = doc.elementFromPoint(x, y);
    if (!element) return false;
    const options = {bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0};
    for (const type of ["pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
      element.dispatchEvent(new MouseEvent(type, options));
    }
    return true;
  }

  function captchaInput(root) {
    return [...root.querySelectorAll("input")].find(element =>
      visible(element) && /验证码|captcha|verify|code/i.test(
        (element.placeholder || "") + " " + (element.id || "") + " "
          + (element.name || "") + " " + (element.className || "")));
  }

  async function dragSlider(root, distance) {
    const track = [...root.querySelectorAll(
      "[class*='slide' i],[class*='drag' i],[class*='yidun' i],[class*='nc_' i]")]
      .find(visible);
    const handle = track
      ? [...track.querySelectorAll("span,[class*='btn' i],[class*='handle' i],[class*='icon' i]")]
        .find(visible) || track
      : track;
    if (!handle) return false;
    const rect = handle.getBoundingClientRect();
    const trackRect = (track || handle).getBoundingClientRect();
    const startX = rect.left + rect.width / 2;
    const startY = rect.top + rect.height / 2;
    const dx = Math.max(10, Math.min(trackRect.width,
      Number(distance) || Math.round(trackRect.width * 0.8)));
    const steps = 12;
    for (let step = 0; step <= steps; step++) {
      const x = startX + dx * (step / steps);
      const type = step === 0 ? "mousedown" : step === steps ? "mouseup" : "mousemove";
      handle.dispatchEvent(new MouseEvent(type, {
        bubbles: true, cancelable: true, clientX: x, clientY: startY, button: 0}));
      if (step < steps) {
        await new Promise(resolve =>
          setTimeout(resolve, 40 + Math.floor(Math.random() * 40)));
      }
    }
    return true;
  }

  async function applyCaptchaSolution(solution) {
    const {root, frame} = captchaRoot();
    const img = captchaImage(root);
    if (Array.isArray(solution?.points) && solution.points.length) {
      if (!img) return {status: "CAPTCHA_FAILED", message: "未找到验证码图片，无法按坐标点击"};
      const rect = img.getBoundingClientRect();
      const scaleX = rect.width / (img.naturalWidth || img.width || rect.width || 1);
      const scaleY = rect.height / (img.naturalHeight || img.height || rect.height || 1);
      for (const point of solution.points) {
        clickAtInRoot(root, rect.left + point.x * scaleX, rect.top + point.y * scaleY);
        await new Promise(resolve =>
          setTimeout(resolve, 500 + Math.floor(Math.random() * 400)));
      }
      const done = clickableByText(["完成验证", "完成", "确定", "确认"], root);
      if (done) done.click();
      return {status: "CAPTCHA_APPLIED", message: "已按超级鹰坐标完成验证码点击"};
    }
    if (solution?.text) {
      const input = captchaInput(root);
      if (!input) return {status: "CAPTCHA_FAILED", message: "未找到验证码输入框"};
      setNativeValue(input, solution.text);
      const submit = clickableByText(["验证", "确定", "确认", "提交"], root);
      if (submit) submit.click();
      return {status: "CAPTCHA_APPLIED", message: "已填写验证码并提交"};
    }
    if (solution?.dragDistance != null) {
      const dragged = await dragSlider(root, solution.dragDistance);
      if (!dragged) return {status: "CAPTCHA_FAILED", message: "未找到滑块控件"};
      return {status: "CAPTCHA_APPLIED", message: "已按距离拖动滑块"};
    }
    return {status: "CAPTCHA_FAILED", message: "识别结果没有可应用的验证码内容"};
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    const work = message.type === "AUTOMATE_COMPANY"
      ? automate(message.target)
      : message.type === "OPEN_SELECTED_CANDIDATE"
        ? openSelectedCandidate(message.candidateId, message.target)
        : message.type === "COLLECT_DETAIL"
          ? collect(message.target)
          : message.type === "COLLECT_DETAIL_FORCE"
            ? collect(message.target, true)
            : message.type === "CAPTURE_CAPTCHA"
              ? captureCaptcha()
              : message.type === "APPLY_CAPTCHA_SOLUTION"
                ? applyCaptchaSolution(message.solution)
                : null;
    if (!work) return false;
    work.then(sendResponse).catch(error =>
      sendResponse({status: "ERROR", message: error.message || String(error)}));
    return true;
  });

  let lastPageSignal = null;
  function pageSignal() {
    const body = text();
    let phase = "OTHER";
    if (blocked(body)) phase = "BLOCKED";
    else if (challenged(body)) phase = "CHALLENGE";
    else if (detailPage(body, currentTarget)) phase = "DETAIL";
    else if (currentTarget) phase = "RESULT:" + resultCandidateElements(currentTarget.companyName).length;
    else if (searchInput()) phase = "SEARCH";
    return location.href + "|" + phase;
  }

  function notifyPageReady() {
    const signal = pageSignal();
    if (signal === lastPageSignal) return;
    lastPageSignal = signal;
    chrome.runtime.sendMessage({type: "GSXT_PAGE_READY"}).catch(() => {});
  }

  notifyPageReady();
  let pageChangeTimer = null;
  const observer = new MutationObserver(() => {
    clearTimeout(pageChangeTimer);
    pageChangeTimer = setTimeout(notifyPageReady, 2500);
  });
  if (document.body) observer.observe(document.body, {subtree: true, childList: true, attributes: false});
})();
