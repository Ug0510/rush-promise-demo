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
const policyNames = { NORMAL: "Normal", RUSH: "Rush", BUSY_SLOT: "Busy slot", FALLBACK: "Safe default" };
const policySources = { LIVE: "Live", CACHED: "Cached", FALLBACK: "Safe default" };
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
let ownerSession = null;
let tenantProducts = new Map();
let projectedProduct;
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

function activeAccess() {
  return token || ownerSession?.csrfToken;
}

function accessHeaders(method, extra = {}) {
  if (ownerSession) return method === "POST" ? { ...extra, "X-CSRF-Token": ownerSession.csrfToken } : extra;
  return token ? { ...extra, "X-Staff-Token": token } : extra;
}

async function api(path, method = "GET", body) {
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: accessHeaders(method, body ? { "Content-Type": "application/json" } : {}),
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
  const piece = (order.unit || tenantProducts.get(order.sku)?.unit) === "PIECE";
  const quantity = piece ? `${order.grams} ${order.grams === 1 ? "piece" : "pieces"}` : weight(order.grams);
  return `${quantity} ${order.itemName}`;
}

function badge(order) {
  const result = element("span", statusName[order.status] || order.status, "status");
  result.dataset.state = order.status;
  return result;
}

function fetchLabel(value) {
  if (!value) return "No successful fetch";
  return new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", hour: "numeric", minute: "2-digit", second: "2-digit" })
    .format(new Date(value)) + " IST";
}

function policyEvidence(order) {
  const policy = order.policy;
  const slot = policy?.slot;
  if (!slot?.why) return [];
  const why = element("p", slot.why, "policy-why");
  if (role !== "OWNER") return [why];
  const audit = element("details", undefined, "policy-audit");
  audit.dataset.audit = String(order.id);
  const definitions = element("dl");
  for (const [name, value] of [
    ["Saved profile", slot.profile], ["Rule", slot.reasonCode], ["Source", policy.source],
    ["Policy digest", slot.digest], ["Fetched", fetchLabel(policy.fetchedAt)],
    ["Places used before booking", `${slot.used}/${slot.capacity}`],
    ["Approval above", weight(policy.approvalAboveGrams)],
    ["Booking lead", `${policy.leadMinutes} min`], ["Quote lifetime", `${policy.ttlSeconds} s`],
    ["Pickup grace", `${policy.pickupGraceMinutes} min`], ["Approval hold", `${policy.approvalHoldMinutes} min`]
  ]) if (value !== null && value !== undefined) definitions.append(element("dt", name), element("dd", String(value)));
  audit.append(element("summary", "Saved policy details"), definitions);
  return [why, audit];
}

function slotChoice(option) {
  const card = element("article", undefined, "policy-option");
  card.append(element("h4", time12(option.pickupTime)));
  if (option.requiresApproval == null || !option.policy?.slot) {
    card.append(element("p", "Unavailable", "muted"));
    return card;
  }
  const slot = option.policy.slot;
  const profile = policyNames[slot.profile] || slot.profile;
  const load = slot.capacity > 0 ? ` · ${slot.used}/${slot.capacity} used (${Math.floor(100 * slot.used / slot.capacity)}%)` : "";
  card.append(element("p", `${profile}${load}`, "muted"),
    element("p", option.requiresApproval ? "Approval needed" : "No approval needed", "policy-status"),
    element("p", slot.why, "policy-why"),
    element("p", [policySources[option.policy.source] || option.policy.source, fetchLabel(option.policy.fetchedAt)]
      .filter(Boolean).join(" · "), "policy-compare-note muted"));
  return card;
}

function renderSavedComparisons(records, orders) {
  const container = $("policy-comparisons");
  const comparisons = records.filter(({digest, options}) => digest && Array.isArray(options) && options.length === 2
    && options.every(option => option.policy?.slot?.digest === digest)
    && options[0].pickupTime !== options[1].pickupTime && options[0].requiresApproval !== options[1].requiresApproval);
  container.hidden = role !== "OWNER" || comparisons.length === 0;
  if (container.hidden) return container.replaceChildren();
  const contents = comparisons.slice(0, 2).map(group => {
    const section = element("section");
    const grid = element("div", undefined, "policy-compare-grid");
    grid.append(...[...group.options].sort((left, right) => left.pickupTime.localeCompare(right.pickupTime)).map(slotChoice));
    section.append(element("h3", `Same order · same policy ${group.digest.slice(0, 12)} · different slot`),
      element("p", `${weight(group.grams)} ${group.itemName} · ${money.format(group.pricePaise / 100)}`, "policy-compare-note"), grid);
    const earliest = group.options.map(option => option.pickupTime).sort()[0];
    const prior = orders.find(order => ["CONFIRMED", "PACKED"].includes(order.status) && order.pickupTime < earliest
      && order.policy?.slot?.digest && order.policy.slot.digest !== group.digest);
    if (prior) section.append(element("p", `#${prior.id} · ${time12(prior.pickupTime)} pickup · original policy retained (${prior.policy.slot.digest.slice(0, 12)})`, "policy-compare-note"));
    return section;
  });
  container.replaceChildren(...contents);
}

function noticeText(row) {
  const value = messageName[row.status] || row.status.toLowerCase().replaceAll("_", " ");
  return `${messageKind[row.kind] || "WhatsApp"} ${value}`;
}

function delivery(rows, order) {
  const selected = (role === "OWNER" && rows?.find(row => row.manualAction))
    || rows?.find(row => row.kind === "READY") || rows?.at(-1);
  if (!selected) return null;
  const status = element("p", noticeText(selected), "delivery");
  status.dataset.state = selected.status === "DELIVERED" ? "delivered" : "pending";
  if (role !== "OWNER" || !selected.manualAction) return status;
  const details = element("div");
  details.append(status);
  if (selected.manualAction === "CHECK_RECIPIENT") {
    details.append(element("p", "Check recipient", "muted"));
  } else {
    const review = selected.manualAction === "REVIEW";
    const button = actionButton(review ? "Review delivery" : "Retry", review ? "review-message" : "retry-message", order);
    button.dataset.message = selected.id;
    details.append(button);
  }
  return details;
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

function spotlight(order, batch, shopTime, rows) {
  if (!order) return element("p", "No confirmed pickups in the queue.", "empty");
  const card = element("article", undefined, "spotlight");
  const label = element("div", undefined, "order-reference");
  label.append(element("span", `#${order.id}`), badge(order));
  const facts = element("dl", undefined, "counter-details");
  facts.append(orderFact("Pickup", time12(order.pickupTime), "◫"),
    orderFact("Pay at pickup", money.format(order.pricePaise / 100), "▤"));
  card.append(label, element("h3", item(order)), facts, ...policyEvidence(order));
  if (role === "OWNER" && rows?.some(row => row.manualAction)) card.append(delivery(rows, order));
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
  const notice = delivery(rows, order);
  if (notice) text.append(notice);
  text.append(...policyEvidence(order));
  const actions = element("div", undefined, "actions");
  if (approval && role === "OWNER") {
    actions.append(actionButton("Review request", "review", order));
  } else if (order.status === "SHORT" && role === "OWNER") {
    actions.append(actionButton("Cancel and notify", "cancel-short", order));
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
  const target = projectedProduct || (!ownerSession ? { sku: "kaju-katli", itemName: "Kaju katli", unit: "GRAMS" } : null);
  if (!target) {
    $("next-title").textContent = "Next pickup to offer";
    $("next-pickup").textContent = "—";
    $("next-description").textContent = "Add items in Menu & stock to see pickup options.";
    $("next-tray").hidden = true;
    return;
  }
  const portion = target.unit === "PIECE" ? "1 piece" : "500 g";
  const nextTray = stock.batches.filter(batch => batch.sku === target.sku && batch.status === "PLANNED"
      && batch.phoneGramsFree > 0 && !batch.blocked).sort((a, b) => a.readyTime.localeCompare(b.readyTime))[0];
  $("next-title").textContent = projection ? "Next pickup to offer" : "Next tray";
  $("next-pickup").textContent = option ? time12(option.pickupTime) : !projection && nextTray ? time12(nextTray.readyTime) : "—";
  $("next-description").textContent = option ? `For ${portion} ${option.itemName.toLowerCase()}`
    : projection ? `No pickup currently available for ${portion} of ${target.itemName.toLowerCase()}.`
    : nextTray ? `Batch ${nextTray.id} is planned. Check pickup availability before promising a time.`
    : "Pickup availability is currently unavailable.";
  const tray = option || (!projection && nextTray);
  $("next-tray").hidden = !tray;
  if (tray) {
    const free = option ? option.batchFreeGrams : nextTray.phoneGramsFree;
    const ready = option ? option.batchReadyTime : nextTray.readyTime;
    const name = option ? option.batchId : nextTray.id;
    const readyNow = option?.batchStatus === "READY" && ready <= stock.shopTime;
    const stockAmount = target.unit === "PIECE" ? `${free} ${free === 1 ? "piece" : "pieces"}` : weight(free);
    $("next-tray-grams").textContent = `Tray ${name} · ${stockAmount} ${readyNow ? "free" : "set aside"}`;
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
  const product = tenantProducts.get(batch.sku);
  const name = product?.itemName || { "kaju-katli": "Kaju katli", "barfi": "Barfi" }[batch.sku] || batch.sku;
  const stockAmount = product?.unit === "PIECE" ? `${batch.phoneGramsFree} ${batch.phoneGramsFree === 1 ? "piece" : "pieces"}` : weight(batch.phoneGramsFree);
  const readyNow = batch.status === "READY" && batch.readyTime <= shopTime;
  const quantity = batch.blocked ? "Blocked" : `${stockAmount} ${readyNow ? "free" : "set aside"}`;
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

function renderStockIssues(batches, orders, deliveries, shopTime) {
  const issues = batches.filter(batch => batch.blocked).map(batch => {
    const item = element("section", undefined, "stock-issue");
    item.append(element("h4", `Tray ${batch.id} needs a recount`, "pack-warning"));
    const affected = orders.filter(order => order.batchId === batch.id && order.checkStock
      && ["CONFIRMED", "AWAITING_APPROVAL", "SHORT"].includes(order.status));
    if (affected.length) {
      item.append(element("p", `Check ${affected.map(order => `#${order.id}`).join(", ")} before promising their pickups.`, "muted"));
    }
    if (role === "OWNER") {
      const recount = element("button", "Recount tray", "button quiet");
      recount.type = "button";
      recount.dataset.action = "unblock";
      recount.dataset.batch = batch.id;
      recount.setAttribute("aria-label", `Recount flagged tray ${batch.id}`);
      item.append(recount);
    }
    for (const order of affected.filter(row => row.status === "SHORT")) {
      item.append(orderRow(order, deliveries[order.id], false, shopTime));
    }
    return item;
  });
  $("stock-exceptions").hidden = issues.length === 0;
  $("stock-issues").replaceChildren(...issues);
}

function render({orders, stock, policy, deliveries, shop}, projection, comparisons) {
  if (shop) shopProfile = shop;
  message("desk-shop", ownerSession ? shopProfile?.displayName || ownerSession.shop.displayName : "");
  const next = orders.filter(order => order.status === "CONFIRMED").sort((a, b) => a.pickupTime.localeCompare(b.pickupTime));
  const approvals = orders.filter(order => order.status === "AWAITING_APPROVAL")
    .sort((a, b) => String(a.approvalExpiresAt).localeCompare(String(b.approvalExpiresAt)));
  const blockedTrays = new Set(stock.batches.filter(batch => batch.blocked).map(batch => batch.id));
  const recent = orders.filter(order => !["CONFIRMED", "AWAITING_APPROVAL"].includes(order.status)
    && !(order.status === "SHORT" && order.checkStock && blockedTrays.has(order.batchId))).slice(0, 12);
  const focused = document.activeElement;
  const actionKey = focused?.dataset.action && [focused.dataset.action, focused.dataset.order || focused.dataset.batch];
  const openAudits = new Set([...workspace.querySelectorAll("details[data-audit][open]")].map(detail => detail.dataset.audit));
  activePackingId = next[0]?.id;
  renderedOrders = new Map(orders.map(order => [String(order.id), order]));
  renderedBatches = new Map(stock.batches.map(batch => [batch.id, batch]));
  $("shop-clock").textContent = time12(stock.shopTime);
  const soon = approvals[0]?.approvalExpiresAt;
  message("pending-count", soon ? `By ${alertDeadline(soon)}` : "");
  $("recent-section").hidden = !recent.length;
  const threshold = weight(policy.approvalAboveGrams);
  const approvalLabel = policy.digest ? `Default approval above ${threshold}` : `Orders above ${threshold} need approval`;
  $("policy-status").textContent = `Rush mode ${policy.rushMode ? "on" : "off"} · ${approvalLabel}`;
  const pickupLoad = policy.digest ? stock.slots.filter(slot => ["19:30", "20:00"].includes(slot.pickupTime))
    .map(slot => `${time12(slot.pickupTime)} · ${slot.used}/${slot.capacity} used`).join("  /  ") : "";
  message("pickup-load", pickupLoad);
  const updatedAt = policy.fetchedAt ? new Date(policy.fetchedAt) : null;
  const age = updatedAt ? Math.max(0, Math.floor((Date.now() - updatedAt.getTime()) / 1000)) : null;
  const summary = [policySources[policy.source] || policy.source || "Policy", policy.digest?.slice(0, 12) || policy.version,
    age === null ? null : `fetched ${age}s ago`];
  $("policy-summary").textContent = summary.filter(Boolean).join(" · ");
  $("flagsmith").hidden = role !== "OPERATOR" || Boolean(ownerSession);
  $("staff-role").textContent = roleName[role] || "";
  $("staff-role").hidden = role === "OWNER";
  $("shop-settings").hidden = role !== "OWNER";
  $("owner-menu-entry").hidden = !ownerSession;
  $("logout").setAttribute("aria-label", ownerSession ? "Return to owner workspace" : "Lock desk");
  $("spotlight").replaceChildren(spotlight(next[0], renderedBatches.get(next[0]?.batchId), stock.shopTime, deliveries[next[0]?.id]));
  list($("queue"), next.slice(1), order => orderRow(order, deliveries[order.id], false, stock.shopTime));
  list($("approvals"), approvals, order => orderRow(order, deliveries[order.id], true), "No requests are waiting.");
  renderStockIssues(stock.batches, orders, deliveries, stock.shopTime);
  renderSavedComparisons(comparisons, orders);
  list($("recent"), recent, order => orderRow(order, deliveries[order.id]));
  list($("batches"), stock.batches, batch => trayRow(batch, stock.shopTime), "No batches available.");
  renderProjection(projection, stock);
  renderActivity(orders, deliveries);
  for (const audit of workspace.querySelectorAll("details[data-audit]")) audit.open = openAudits.has(audit.dataset.audit);
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
  if (!activeAccess()) return;
  if (refreshing) {
    refreshAgain = true;
    return;
  }
  const access = activeAccess();
  refreshing = true;
  try {
    const products = ownerSession ? await api("/api/owner/products") : [];
    if (access !== activeAccess()) return;
    tenantProducts = new Map(products.map(product => [product.sku, product]));
    projectedProduct = products.find(product => product.freeQuantity > 0) || products[0];
    const sku = projectedProduct?.sku || "kaju-katli";
    const quantity = projectedProduct?.unit === "PIECE" ? 1 : 500;
    const [result, projection, comparisons] = await Promise.all([
      readSnapshot(),
      ownerSession && !projectedProduct ? null : api(`/api/availability/next?sku=${encodeURIComponent(sku)}&grams=${quantity}`).catch(error => {
        if (error.status === 404 || error.status === 422) return null;
        throw error;
      }),
      role === "OWNER" ? api("/api/policy/saved-comparisons").catch(() => []) : []
    ]);
    if (access !== activeAccess()) return;
    const nextSnapshot = JSON.stringify([result, projection, products, comparisons]);
    if (nextSnapshot !== snapshot) {
      render(result, projection, comparisons);
      snapshot = nextSnapshot;
    }
    if (liveMode === "stream" || liveMode === "poll") connection("live");
  } catch (error) {
    if (access !== activeAccess()) return;
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
  if (!activeAccess() || streamController) return;
  const access = activeAccess();
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
      credentials: "same-origin",
      headers: accessHeaders("GET", { Accept: "text/event-stream" }),
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
    while (access === activeAccess()) {
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
    if (access === activeAccess() && !refreshTimer) {
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
  ownerSession = null;
  tenantProducts = new Map();
  projectedProduct = null;
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
    INVALID_SHORTAGE: "Enter a shortage between one unit and the ordered amount.",
    COUNT_BELOW_HOLDS: "That count is lower than the stock already promised. The tray is still blocked.",
    APPROVAL_EXPIRED: "This approval expired. The request will disappear from the queue.",
    PROVIDER_EVIDENCE_FOUND: "A provider receipt was found. No duplicate notice was queued.",
    NOTICE_NOT_REPLAYABLE: "This notice has changed. Check its current delivery status.",
    NOTICE_UNAVAILABLE: "This notice is unavailable for another attempt."
  };
  return error.status === 403 ? "That action needs owner access."
    : domain[error.message] || "The shop couldn’t apply that action. Check the order before trying again.";
}

function showAction(button) {
  const {action, order: orderId, batch: batchId, message: messageId} = button.dataset;
  const order = renderedOrders.get(orderId);
  const batch = renderedBatches.get(batchId);
  if (!order && !batch) return;
  selectedAction = { action, orderId, batchId, messageId };
  returnFocus = [action, orderId || batchId];
  const piece = (order?.unit || tenantProducts.get(batch?.sku)?.unit) === "PIECE";
  $("dialog-unit").textContent = piece ? "Pieces" : "Grams";
  $("dialog-options").hidden = action !== "review" || role !== "OWNER" || piece || !order?.policy?.slot;
  $("compare-options").disabled = false;
  $("compare-results").replaceChildren();
  message("compare-status", "");
  const fields = {
    review: { title: `Review request #${orderId}`, detail: order
      ? `${item(order)}, ${money.format(order.pricePaise / 100)} at pickup, ${time12(order.pickupTime)}. Decide by ${alertDeadline(order.approvalExpiresAt)}.`
      : "", submit: "Approve request" },
    short: { title: `Report a shortage for #${orderId}`, detail: order
      ? `Enter the ${piece ? "missing pieces" : "missing weight"}. This will flag Tray ${order.batchId} for a stock check.`
      : "", submit: "Report shortage" },
    "cancel-short": { title: `Cancel order #${orderId}?`, detail: "Cancelling queues a notice about the pickup.",
      submit: "Cancel and notify" },
    "review-message": { title: `Review delivery for #${orderId}`,
      detail: "The previous result is uncertain. Check with the customer before resending. We’ll check recorded receipts again.", submit: "Resend" },
    "retry-message": { title: `Retry delivery for #${orderId}`,
      detail: "The previous attempts were rate limited. Queue this notice again?", submit: "Retry" },
    unblock: { title: `Recount Tray ${batchId}`, detail: piece ? "Enter the number of pieces after recounting." : "Enter the physical tray weight after recounting.",
      submit: "Save tray count" }
  }[action];
  if (!fields) return;
  $("dialog-title").textContent = fields.title;
  $("dialog-detail").textContent = fields.detail;
  $("dialog-submit").textContent = fields.submit;
  $("dialog-decline").hidden = action !== "review";
  const needsCount = action === "short" || action === "unblock";
  $("dialog-weight").hidden = !needsCount;
  const grams = $("action-grams");
  grams.value = "";
  grams.required = needsCount;
  grams.min = action === "short" ? "1" : "0";
  grams.max = action === "short" ? String(order.grams) : "100000";
  message("dialog-error", "");
  dialog.showModal();
}

async function commitAction(path, body, button) {
  if (!activeAccess() || busy) return;
  busy = true;
  if (button) button.disabled = true;
  $("dialog-submit").disabled = $("dialog-decline").disabled = true;
  message(dialog.open ? "dialog-error" : "desk-error", "");
  message("desk-success", "");
  try {
    await api(path, "POST", body);
    const packed = /^\/api\/orders\/(\d+)\/pack$/.exec(path);
    if (packed) message("desk-success", `Packed #${packed[1]}. Pickup notice queued.`);
    if (path.startsWith("/api/messages/")) message("desk-success", "Notice queued.");
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
  if (["review", "short", "unblock", "cancel-short", "retry-message", "review-message"].includes(action)) {
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
  const {action, orderId, batchId, messageId} = selectedAction;
  const grams = $("action-grams").valueAsNumber;
  if (action === "review") void commitAction(`/api/orders/${orderId}/approve`);
  else if (action === "short") void commitAction(`/api/orders/${orderId}/short`, { grams });
  else if (action === "unblock") void commitAction(`/api/batches/${encodeURIComponent(batchId)}/unblock`, { grams });
  else if (action === "cancel-short") void commitAction(`/api/orders/${orderId}/cancel`);
  else if (action === "review-message" || action === "retry-message") {
    const verb = action === "review-message" ? "resend" : "retry";
    void commitAction(`/api/messages/${encodeURIComponent(messageId)}/${verb}`, { reviewed: true });
  }
});
$("dialog-decline").addEventListener("click", () => {
  if (selectedAction?.action === "review") void commitAction(`/api/orders/${selectedAction.orderId}/reject`);
});
$("compare-options").addEventListener("click", async () => {
  if (role !== "OWNER" || selectedAction?.action !== "review") return;
  const current = selectedAction;
  const order = renderedOrders.get(current.orderId);
  if (!order) return;
  $("compare-options").disabled = true;
  message("compare-status", "Checking the current slots…");
  try {
    const results = await api(`/api/policy/pickup-options?sku=${encodeURIComponent(order.sku)}&grams=${order.grams}`);
    if (selectedAction !== current) return;
    const slots = element("div", undefined, "policy-compare-grid");
    slots.append(...results.options.map(slotChoice));
    $("compare-results").replaceChildren(slots);
    message("compare-status", results.options.length ? "Current options. The customer still needs a new quote to choose a different pickup."
      : "No other slots are available for this amount.");
  } catch {
    if (selectedAction === current) message("compare-status", "Current pickup options are temporarily unavailable.");
  } finally {
    if (selectedAction === current) $("compare-options").disabled = false;
  }
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
    const displayName = $("shop-name").value.trim();
    if (ownerSession?.shop) {
      const { slug, area, hours, pickupInstructions } = ownerSession.shop;
      ownerSession.shop = await api("/api/owner/shop/details", "POST", { displayName, slug, area, hours, pickupInstructions });
      shopProfile = { displayName: ownerSession.shop.displayName };
    } else {
      shopProfile = await api("/api/shop/profile", "POST", { displayName });
    }
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
$("logout").addEventListener("click", () => {
  if (ownerSession) location.assign("/owner");
  else lockDesk();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void refresh();
});

async function openOwnerDesk() {
  try {
    const established = await api("/api/auth/session");
    if (!established?.shop || token || busy) return;
    ownerSession = established;
    const identity = await api("/api/staff/session");
    if (identity.role !== "OWNER") {
      ownerSession = null;
      return;
    }
    role = identity.role;
    login.hidden = true;
    workspace.hidden = false;
    nav.hidden = false;
    $("desk-tools").hidden = false;
    connection("connecting");
    await refresh();
    if (ownerSession) void subscribe();
  } catch {
    ownerSession = null;
  }
}
void openOwnerDesk();
