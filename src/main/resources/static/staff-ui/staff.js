"use strict";

const $ = id => document.getElementById(id);
const login = $("staff-login");
const workspace = $("workspace");
const nav = $("desk-nav");
const dialog = $("action-dialog");
const actionForm = $("action-form");
const profileDialog = $("profile-dialog");
const profileForm = $("profile-form");
const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0 });
const roleName = { STAFF: "Staff", OWNER: "Owner", OPERATOR: "Operator" };
const statusName = {
  CONFIRMED: "Confirmed", PACKED: "Packed", AWAITING_APPROVAL: "Needs approval",
  REJECTED: "Declined", CANCELLED: "Cancelled", SHORT: "Stock short", EXPIRED: "Expired"
};
const messageName = {
  QUEUED: "queued", PENDING: "queued", SENDING: "sending", SUBMITTED: "submitted",
  DELIVERED: "delivered", FAILED_RETRYABLE: "retry queued", NEEDS_REVIEW: "needs review",
  FAILED_PERMANENT: "failed", UNKNOWN: "needs review", SIMULATED: "simulated"
};
const messageKind = {
  READY: "WhatsApp pickup notice", CONFIRMED: "WhatsApp confirmation", AWAITING_APPROVAL: "WhatsApp approval notice",
  CANCELLED: "WhatsApp cancellation", REJECTED: "WhatsApp decline notice",
  SHORT: "WhatsApp shortage notice", APPROVED: "WhatsApp approval confirmation"
};
let token = "";
let role = "";
let refreshTimer;
let streamController;
let streamRetry;
let liveMode = "starting";
let refreshing = false;
let refreshAgain = false;
let busy = false;
let snapshot = "";
let renderedOrders = new Map();
let renderedBatches = new Map();
let noticesToShow = [];
let activePackingId;
let selectedAction;
let returnFocus;
let shopProfile;
let profileSaving = false;

function element(tag, value, className) {
  const node = document.createElement(tag);
  if (value !== undefined) node.textContent = value;
  if (className) node.className = className;
  return node;
}

function message(id, text) {
  $(id).textContent = text;
  $(id).hidden = !text;
}

function connection(status, detail = "") {
  const state = $("live-state");
  state.dataset.status = status;
  state.textContent = { live: "Live", connecting: "Connecting", reconnecting: "Reconnecting" }[status];
  message("connection", detail);
}

async function api(path, method = "GET", body) {
  const response = await fetch(path, {
    method,
    headers: body ? { "X-Staff-Token": token, "Content-Type": "application/json" } : { "X-Staff-Token": token },
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
    signal: AbortSignal.timeout(6500)
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(result?.reason || result?.code || "The desk could not reach the shop.");
    error.status = response.status;
    throw error;
  }
  return result;
}

function time12(value) {
  if (!value) return "—";
  const [hour, minute] = value.split(":").map(Number);
  return `${hour % 12 || 12}:${String(minute).padStart(2, "0")} ${hour >= 12 ? "PM" : "AM"}`;
}

function weight(grams) {
  return grams >= 1000 ? `${grams / 1000} kg` : `${grams} g`;
}

function item(order) {
  return `${weight(order.grams)} ${order.itemName}`;
}

function badge(order) {
  const result = element("span", statusName[order.status] || order.status, "status");
  result.dataset.state = order.status;
  return result;
}

function noticeText(row) {
  const value = messageName[row.status] || row.status.toLowerCase().replaceAll("_", " ");
  return `${messageKind[row.kind] || "WhatsApp"} ${value}`;
}

function delivery(rows) {
  const selected = rows?.find(row => row.kind === "READY") || rows?.at(-1);
  if (!selected) return null;
  const status = element("p", noticeText(selected), "delivery");
  status.dataset.state = selected.status === "DELIVERED" ? "delivered" : "pending";
  return status;
}

function actionButton(label, action, order, primary = false) {
  const button = element("button", label, primary ? "button primary" : "button quiet");
  button.type = "button";
  button.dataset.action = action;
  button.dataset.order = String(order.id);
  button.setAttribute("aria-label", `${label} #${order.id}`);
  return button;
}

function canPack() {
  return role === "STAFF" || role === "OWNER";
}

function orderFact(label, value, icon) {
  const row = element("div");
  const glyph = element("span", icon, "detail-icon");
  glyph.setAttribute("aria-hidden", "true");
  row.append(glyph, element("dt", label), element("dd", value));
  return row;
}

function packBlock(batch, shopTime) {
  if (!batch) return "Tray data isn’t available.";
  if (batch.blocked) return `Tray ${batch.id} is blocked. Recount it before packing.`;
  if (batch.status !== "READY" || batch.readyTime > shopTime) {
    return batch.readyTime <= shopTime ? `Check Tray ${batch.id} and mark it ready before packing.`
      : `Tray ${batch.id} ready at ${time12(batch.readyTime)}.`;
  }
  return "";
}

function spotlight(order, batch, shopTime) {
  if (!order) return element("p", "No confirmed pickups in the queue.", "empty");
  const card = element("article", undefined, "spotlight");
  const label = element("div", undefined, "order-reference");
  label.append(element("span", `#${order.id}`), badge(order));
  const facts = element("dl", undefined, "counter-details");
  facts.append(orderFact("Pickup", time12(order.pickupTime), "◫"),
    orderFact("Pay at pickup", money.format(order.pricePaise / 100), "▤"));
  card.append(label, element("h3", item(order)), facts);
  const blocked = packBlock(batch, shopTime);
  if (blocked) card.append(element("p", blocked, "pack-warning"));
  if (canPack()) {
    const controls = element("div", undefined, "counter-actions");
    const pack = actionButton("Mark packed", "pack", order, true);
    pack.disabled = Boolean(blocked);
    const checkmark = element("span", "✓");
    checkmark.setAttribute("aria-hidden", "true");
    pack.prepend(checkmark);
    controls.append(pack, actionButton("Report shortage", "short", order));
    card.append(controls);
  }
  return card;
}

function orderRow(order, rows, approval = false, shopTime = "") {
  const row = element("article", undefined, approval ? "order-row approval-row" : "order-row");
  const text = element("div");
  text.append(element("h3", `#${order.id} · ${item(order)}`));
  const time = element("p", time12(order.pickupTime), "muted");
  if (approval) time.textContent += ` pickup · ${money.format(order.pricePaise / 100)}`;
  text.append(time);
  const notice = delivery(rows);
  if (notice) text.append(notice);
  const actions = element("div", undefined, "actions");
  if (approval && role === "OWNER") {
    actions.append(actionButton("Review request", "review", order));
  } else if (order.status === "CONFIRMED" && canPack()) {
    const control = actionButton("Mark packed", "pack", order);
    const blocked = packBlock(renderedBatches.get(order.batchId), shopTime);
    control.disabled = Boolean(blocked);
    if (blocked) text.append(element("p", blocked, "pack-warning"));
    actions.append(control);
  } else if (order.returnToTray && canPack()) {
    actions.append(actionButton("Returned to tray", "return", order));
  } else {
    actions.append(badge(order));
  }
  row.append(text, actions);
  return row;
}

function list(container, values, create, empty) {
  container.replaceChildren(...(values.length ? values.map(create) : empty ? [element("p", empty, "empty")] : []));
}

function renderProjection(projection, stock) {
  const option = projection?.option;
  const nextTray = stock.batches.filter(batch => batch.sku === "kaju-katli" && batch.status === "PLANNED"
      && batch.phoneGramsFree > 0 && !batch.blocked).sort((a, b) => a.readyTime.localeCompare(b.readyTime))[0];
  $("next-title").textContent = projection ? "Next pickup to offer" : "Next kaju katli tray";
  $("next-pickup").textContent = option ? time12(option.pickupTime) : !projection && nextTray ? time12(nextTray.readyTime) : "—";
  $("next-description").textContent = option ? `For ${weight(option.grams)} ${option.itemName.toLowerCase()}`
    : projection ? "No pickup currently available for 500 g of kaju katli."
    : nextTray ? `Batch ${nextTray.id} is planned. Check pickup availability before promising a time.`
    : "Pickup availability is currently unavailable.";
  const tray = option || (!projection && nextTray);
  $("next-tray").hidden = !tray;
  if (tray) {
    const free = option ? option.batchFreeGrams : nextTray.phoneGramsFree;
    const ready = option ? option.batchReadyTime : nextTray.readyTime;
    const name = option ? option.batchId : nextTray.id;
    const readyNow = option?.batchStatus === "READY" && ready <= stock.shopTime;
    $("next-tray-grams").textContent = `Tray ${name} · ${weight(free)} ${readyNow ? "free" : "set aside"}`;
    $("next-ready").textContent = readyNow ? "Tray ready" : `Ready at ${time12(ready)}`;
  }
}

function alertDeadline(value) {
  return new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", hour: "numeric", minute: "2-digit", hour12: true })
    .format(new Date(value)).toUpperCase();
}

function isLate(notice) {
  const pending = ["PENDING", "QUEUED", "SENDING", "FAILED_RETRYABLE", "NEEDS_REVIEW"];
  return notice.kind === "READY" && pending.includes(notice.status)
    && (Date.now() - new Date(notice.createdAt).getTime() >= 60000 || ["FAILED_RETRYABLE", "NEEDS_REVIEW"].includes(notice.status));
}

function renderNotice() {
  const late = noticesToShow.find(isLate);
  const chosen = late || noticesToShow.find(notice => notice.kind === "READY" && notice.status !== "SUPPRESSED")
    || noticesToShow.find(notice => notice.orderId === activePackingId && notice.kind === "CONFIRMED") || noticesToShow[0];
  const strip = $("notice-strip");
  strip.dataset.state = late ? "late" : "";
  const label = late ? `Pickup notice needs attention for #${late.orderId}. ${noticeText(late)}.`
    : chosen ? `${noticeText(chosen)} for #${chosen.orderId}` : "";
  message("notice-strip", label);
}

function renderActivity(orders, deliveries) {
  noticesToShow = orders.flatMap(order => (deliveries[order.id] || []).map(notice => ({ ...notice, orderId: order.id })))
    .sort((left, right) => String(right.updatedAt || right.createdAt).localeCompare(String(left.updatedAt || left.createdAt)));
  renderNotice();
  const events = orders.slice(0, 4).flatMap(order => {
    const status = statusName[order.status] || order.status;
    const orderUpdate = { label: `Order #${order.id} ${status.toLowerCase()}`, pending: order.status === "AWAITING_APPROVAL" };
    const deliveryUpdates = (deliveries[order.id] || []).map(notice => ({
      label: noticeText(notice), pending: !["DELIVERED", "SIMULATED"].includes(notice.status)
    }));
    return [orderUpdate, ...deliveryUpdates];
  }).slice(0, 8);
  $("updates").replaceChildren(...(events.length ? events.map(event => {
    const line = element("li", event.label);
    if (event.pending) line.dataset.state = "pending";
    return line;
  }) : [element("li", "Order and message updates will appear here.", "empty")]));
}

function trayRow(batch, shopTime) {
  const row = element("div", undefined, "batch");
  row.dataset.blocked = String(batch.blocked);
  const name = { "kaju-katli": "Kaju katli", "barfi": "Barfi" }[batch.sku] || batch.sku;
  const readyNow = batch.status === "READY" && batch.readyTime <= shopTime;
  const quantity = batch.blocked ? "Blocked" : `${weight(batch.phoneGramsFree)} ${readyNow ? "free" : "set aside"}`;
  const label = element("div", `${name} · ${batch.id}`, "batch-label");
  if (!batch.blocked && !readyNow && batch.readyTime > shopTime) {
    label.append(element("small", `Ready at ${time12(batch.readyTime)}`));
  }
  row.append(label, element("b", quantity, "batch-quantity"));
  const action = batch.blocked && role === "OWNER" ? "unblock"
    : !batch.blocked && !readyNow && canPack() && batch.readyTime <= shopTime ? "ready" : "";
  if (action) {
    const controls = element("div", undefined, "batch-actions");
    const button = element("button", action === "unblock" ? "Recount tray" : "Mark tray ready", "button quiet");
    button.dataset.action = action;
    button.dataset.batch = batch.id;
    button.type = "button";
    button.setAttribute("aria-label", `${button.textContent} ${batch.id}`);
    controls.append(button);
    row.append(controls);
  }
  return row;
}

function render({orders, stock, policy, deliveries, shop}, projection) {
  if (shop) shopProfile = shop;
  const next = orders.filter(order => order.status === "CONFIRMED").sort((a, b) => a.pickupTime.localeCompare(b.pickupTime));
  const approvals = orders.filter(order => order.status === "AWAITING_APPROVAL")
    .sort((a, b) => String(a.approvalExpiresAt).localeCompare(String(b.approvalExpiresAt)));
  const recent = orders.filter(order => !["CONFIRMED", "AWAITING_APPROVAL"].includes(order.status)).slice(0, 12);
  const focused = document.activeElement;
  const actionKey = focused?.dataset.action && [focused.dataset.action, focused.dataset.order || focused.dataset.batch];
  activePackingId = next[0]?.id;
  renderedOrders = new Map(orders.map(order => [String(order.id), order]));
  renderedBatches = new Map(stock.batches.map(batch => [batch.id, batch]));
  $("shop-clock").textContent = time12(stock.shopTime);
  const soon = approvals[0]?.approvalExpiresAt;
  message("pending-count", soon ? `By ${alertDeadline(soon)}` : "");
  $("recent-section").hidden = !recent.length;
  $("policy-status").textContent = `Rush mode ${policy.rushMode ? "on" : "off"} · Orders above ${weight(policy.approvalAboveGrams)} need approval`;
  const updatedAt = policy.fetchedAt ? new Date(policy.fetchedAt) : null;
  const age = updatedAt ? Math.max(0, Math.floor((Date.now() - updatedAt.getTime()) / 1000)) : null;
  $("policy-summary").textContent = `${policy.source || "Policy"} · ${policy.version || "version unknown"}${age === null ? "" : ` · fetched ${age}s ago`}`;
  $("flagsmith").hidden = role !== "OWNER";
  $("staff-role").textContent = roleName[role] || "";
  $("staff-role").hidden = role === "OWNER";
  $("shop-settings").hidden = role !== "OWNER";
  $("spotlight").replaceChildren(spotlight(next[0], renderedBatches.get(next[0]?.batchId), stock.shopTime));
  list($("queue"), next.slice(1), order => orderRow(order, deliveries[order.id], false, stock.shopTime));
  list($("approvals"), approvals, order => orderRow(order, deliveries[order.id], true), "No requests are waiting.");
  list($("recent"), recent, order => orderRow(order, deliveries[order.id]));
  list($("batches"), stock.batches, batch => trayRow(batch, stock.shopTime), "No batches available.");
  renderProjection(projection, stock);
  renderActivity(orders, deliveries);
  if (actionKey) findAction(...actionKey)?.focus({ preventScroll: true });
}

function findAction(action, id) {
  return [...workspace.querySelectorAll("[data-action]")].find(
    button => button.dataset.action === action && (button.dataset.order || button.dataset.batch) === id
  );
}

async function readSnapshot() {
  try {
    return await api("/api/staff/snapshot");
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  const [orders, stock, policy] = await Promise.all([api("/api/orders"), api("/api/stock"), api("/api/policy")]);
  const sample = orders.slice(0, 24);
  const notices = await Promise.allSettled(sample.map(order => api(`/api/orders/${order.id}/messages`)));
  const deliveries = Object.fromEntries(sample.map((order, index) => [order.id, notices[index].value]));
  return { orders, stock, policy, deliveries };
}

async function refresh() {
  if (!token) return;
  if (refreshing) {
    refreshAgain = true;
    return;
  }
  const access = token;
  refreshing = true;
  try {
    const [result, projection] = await Promise.all([
      readSnapshot(),
      api("/api/availability/next?sku=kaju-katli&grams=500").catch(error => {
        if (error.status === 404) return null;
        throw error;
      })
    ]);
    if (access !== token) return;
    const nextSnapshot = JSON.stringify([result, projection]);
    if (nextSnapshot !== snapshot) {
      render(result, projection);
      snapshot = nextSnapshot;
    }
    if (liveMode === "stream" || liveMode === "poll") connection("live");
  } catch (error) {
    if (access !== token) return;
    if (error.status === 401 || error.status === 403) {
      lockDesk();
      message("login-error", "Your desk access was rejected. Enter a valid access token.");
    } else {
      connection("reconnecting", "Connection interrupted. Status may be out of date.");
    }
  } finally {
    refreshing = false;
    if (refreshAgain) {
      refreshAgain = false;
      void refresh();
    }
  }
}

async function subscribe() {
  if (!token || streamController) return;
  const access = token;
  const controller = new AbortController();
  streamController = controller;
  let reader, heartbeat;
  const expectHeartbeat = () => {
    clearTimeout(heartbeat);
    heartbeat = setTimeout(() => controller.abort(), 12000);
  };
  try {
    expectHeartbeat();
    const response = await fetch("/api/events", {
      headers: { "X-Staff-Token": access, Accept: "text/event-stream" },
      cache: "no-store",
      signal: controller.signal
    });
    if (response.status === 404) {
      liveMode = "poll";
      void refresh();
      refreshTimer = setInterval(() => { if (!document.hidden) void refresh(); }, 4200);
      return;
    }
    if (!response.ok || !response.body) throw new Error("Desk updates interrupted");
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (access === token) {
      const {value, done} = await reader.read();
      if (done) break;
      expectHeartbeat();
      buffer += decoder.decode(value, {stream: true});
      const messages = buffer.split(/\r?\n\r?\n/);
      buffer = messages.pop() || "";
      for (const event of messages) {
        if (/^event:\s*heartbeat\s*$/m.test(event)) renderNotice();
        if (/^event:\s*changed\s*$/m.test(event)) {
          liveMode = "stream";
          void refresh();
        }
      }
    }
  } catch {
    // Keep the last complete snapshot and reconnect to request a fresh one.
  } finally {
    clearTimeout(heartbeat);
    if (reader) void reader.cancel().catch(() => {});
    if (streamController === controller) streamController = null;
    if (access === token && !refreshTimer) {
      liveMode = "down";
      connection("reconnecting", "Live updates interrupted. Reconnecting…");
      streamRetry = setTimeout(() => void subscribe(), 1800);
    }
  }
}

function lockDesk() {
  clearInterval(refreshTimer);
  clearTimeout(streamRetry);
  const controller = streamController;
  streamController = null;
  refreshTimer = null;
  liveMode = "starting";
  token = "";
  role = "";
  if (dialog.open) dialog.close();
  if (profileDialog.open) profileDialog.close();
  shopProfile = null;
  snapshot = "";
  controller?.abort();
  workspace.hidden = true;
  login.hidden = false;
  nav.hidden = true;
  $("desk-tools").hidden = true;
  $("staff-token").value = "";
  $("staff-token").focus();
}

login.addEventListener("submit", async event => {
  event.preventDefault();
  if (busy) return;
  busy = true;
  message("login-error", "");
  try {
    token = $("staff-token").value.trim();
    const session = await api("/api/staff/session");
    role = session.role;
    if (!Object.hasOwn(roleName, role)) throw new Error("Unrecognized access role.");
    $("staff-token").value = "";
    login.hidden = true;
    workspace.hidden = false;
    nav.hidden = false;
    $("desk-tools").hidden = false;
    connection("connecting");
    await refresh();
    if (token) void subscribe();
  } catch (error) {
    lockDesk();
    message("login-error", [401, 403].includes(error.status) ? "That access token was rejected." : "Can’t unlock the desk right now.");
  } finally {
    busy = false;
  }
});

function explain(error) {
  const domain = {
    BATCH_NOT_READY: "That tray isn’t ready yet.",
    BATCH_BLOCKED: "The tray is blocked. An owner needs to recount it.",
    INVALID_SHORTAGE: "Enter a shortage between 1 g and the ordered amount.",
    COUNT_BELOW_HOLDS: "That count is lower than the stock already promised. The tray is still blocked.",
    APPROVAL_EXPIRED: "This approval expired. The request will disappear from the queue."
  };
  return error.status === 403 ? "That action needs owner access."
    : domain[error.message] || "The shop couldn’t apply that action. Check the order before trying again.";
}

function showAction(button) {
  const {action, order: orderId, batch: batchId} = button.dataset;
  const order = renderedOrders.get(orderId);
  const batch = renderedBatches.get(batchId);
  if (!order && !batch) return;
  selectedAction = { action, orderId, batchId };
  returnFocus = [action, orderId || batchId];
  const fields = {
    review: { title: `Review request #${orderId}`, detail: order
      ? `${item(order)}, ${money.format(order.pricePaise / 100)} at pickup, ${time12(order.pickupTime)}. Decide by ${alertDeadline(order.approvalExpiresAt)}.`
      : "", submit: "Approve request" },
    short: { title: `Report a shortage for #${orderId}`, detail: order
      ? `Enter the missing weight. This will flag Tray ${order.batchId} for a stock check.`
      : "", submit: "Report shortage" },
    unblock: { title: `Recount Tray ${batchId}`, detail: "Enter the physical tray weight after recounting.",
      submit: "Save tray count" }
  }[action];
  if (!fields) return;
  $("dialog-title").textContent = fields.title;
  $("dialog-detail").textContent = fields.detail;
  $("dialog-submit").textContent = fields.submit;
  $("dialog-decline").hidden = action !== "review";
  $("dialog-weight").hidden = action === "review";
  const grams = $("action-grams");
  grams.value = "";
  grams.required = action !== "review";
  grams.min = action === "short" ? "1" : "0";
  grams.max = action === "short" ? String(order.grams) : "100000";
  message("dialog-error", "");
  dialog.showModal();
}

async function commitAction(path, body, button) {
  if (!token || busy) return;
  busy = true;
  if (button) button.disabled = true;
  $("dialog-submit").disabled = $("dialog-decline").disabled = true;
  message(dialog.open ? "dialog-error" : "desk-error", "");
  try {
    await api(path, "POST", body);
    if (dialog.open) dialog.close();
  } catch (error) {
    message(dialog.open ? "dialog-error" : "desk-error", explain(error));
  } finally {
    if (button) button.disabled = false;
    $("dialog-submit").disabled = $("dialog-decline").disabled = false;
    busy = false;
    await refresh();
  }
}

workspace.addEventListener("click", event => {
  const button = event.target.closest("button[data-action]");
  if (!button || button.disabled || busy) return;
  const {action, order: id, batch} = button.dataset;
  if (["review", "short", "unblock"].includes(action)) {
    showAction(button);
  } else if (action === "ready") {
    void commitAction(`/api/batches/${encodeURIComponent(batch)}/ready`, null, button);
  } else {
    void commitAction(`/api/orders/${id}/${action}`, null, button);
  }
});

actionForm.addEventListener("submit", event => {
  event.preventDefault();
  if (!selectedAction || busy) return;
  const {action, orderId, batchId} = selectedAction;
  const grams = $("action-grams").valueAsNumber;
  if (action === "review") void commitAction(`/api/orders/${orderId}/approve`);
  else if (action === "short") void commitAction(`/api/orders/${orderId}/short`, { grams });
  else if (action === "unblock") void commitAction(`/api/batches/${encodeURIComponent(batchId)}/unblock`, { grams });
});
$("dialog-decline").addEventListener("click", () => {
  if (selectedAction?.action === "review") void commitAction(`/api/orders/${selectedAction.orderId}/reject`);
});
$("dialog-close").addEventListener("click", () => dialog.close());
dialog.addEventListener("close", () => {
  if (returnFocus) (findAction(...returnFocus) || $("desk-heading")).focus();
  returnFocus = null;
  selectedAction = null;
});

function previewGreeting() {
  const name = $("shop-name").value.trim();
  $("greeting-text").textContent = name ? `नमस्ते, मैं ${name} से बात कर रहा हूँ। बताइए, आपको क्या चाहिए?`
    : "Enter a shop name to preview the greeting.";
  $("profile-save").disabled = profileSaving || name.length === 0 || name === shopProfile?.displayName;
}

$("shop-settings").addEventListener("click", async () => {
  if (role !== "OWNER" || profileSaving) return;
  message("profile-error", "");
  try {
    if (!shopProfile) shopProfile = await api("/api/shop/profile");
    $("shop-name").value = shopProfile.displayName;
    previewGreeting();
    profileDialog.showModal();
    $("shop-name").focus();
  } catch {
    message("desk-error", "The saved shop name is temporarily unavailable.");
  }
});

$("shop-name").addEventListener("input", previewGreeting);
$("profile-cancel").addEventListener("click", () => profileDialog.close());
profileDialog.addEventListener("close", () => {
  if (role === "OWNER") $("shop-settings").focus();
});
profileForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (role !== "OWNER" || profileSaving) return;
  profileSaving = true;
  previewGreeting();
  message("profile-error", "");
  try {
    shopProfile = await api("/api/shop/profile", "POST", { displayName: $("shop-name").value.trim() });
    profileDialog.close();
    void refresh();
  } catch (error) {
    message("profile-error", error.status === 403 ? "Shop settings need owner access." : "The shop name could not be saved.");
  } finally {
    profileSaving = false;
    previewGreeting();
  }
});

nav.addEventListener("click", event => {
  const target = event.target.closest("a[href^='#']");
  if (!target) return;
  for (const link of nav.querySelectorAll("a")) link.removeAttribute("aria-current");
  target.setAttribute("aria-current", "location");
});
$("refresh").addEventListener("click", () => void refresh());
$("logout").addEventListener("click", lockDesk);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void refresh();
});
