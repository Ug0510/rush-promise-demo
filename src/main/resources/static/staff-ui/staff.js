"use strict";

const $ = id => document.getElementById(id);
const login = $("staff-login");
const workspace = $("workspace");
const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0 });
const roleName = { STAFF: "Staff", OWNER: "Owner", OPERATOR: "Operator" };
const statusName = {
  CONFIRMED: "Confirmed", PACKED: "Packed", AWAITING_APPROVAL: "Needs approval",
  REJECTED: "Declined", CANCELLED: "Cancelled", SHORT: "Stock short", EXPIRED: "Expired"
};
const messageName = {
  QUEUED: "queued", PENDING: "queued", SENDING: "sending", SUBMITTED: "submitted",
  DELIVERED: "delivered", FAILED_RETRYABLE: "retry queued", NEEDS_REVIEW: "needs review",
  FAILED_PERMANENT: "failed", UNKNOWN: "needs review"
};
let token = "";
let role = "";
let refreshTimer;
let refreshing = false;
let busy = false;
let snapshot = "";

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

async function api(path, method = "GET") {
  const response = await fetch(path, {
    method,
    headers: { "X-Staff-Token": token },
    cache: "no-store",
    signal: AbortSignal.timeout(6500)
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(body?.reason || body?.code || "The desk could not reach the shop.");
    error.status = response.status;
    throw error;
  }
  return body;
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

function delivery(order, rows) {
  const selected = rows?.find(row => row.kind === "READY") || rows?.at(-1);
  if (!selected) return null;
  const kind = {
    READY: "WhatsApp pickup notice", CONFIRMED: "WhatsApp confirmation", AWAITING_APPROVAL: "WhatsApp approval notice",
    CANCELLED: "WhatsApp cancellation", REJECTED: "WhatsApp decline notice"
  }[selected.kind] || "WhatsApp";
  const value = messageName[selected.status] || selected.status.toLowerCase().replaceAll("_", " ");
  const status = element("p", `${kind} ${value}`, "delivery");
  status.dataset.state = selected.status === "DELIVERED" ? "delivered" : "pending";
  return status;
}

function actionButton(label, action, order, primary = false) {
  const button = element("button", label, primary ? "button primary" : "button quiet");
  button.type = "button";
  button.dataset.action = action;
  button.dataset.order = order.id;
  button.setAttribute("aria-label", `${label} #${order.id}`);
  return button;
}

function spotlight(order, rows) {
  if (!order) return element("p", "No confirmed pickups in the queue.", "empty");
  const card = element("article", undefined, "spotlight");
  if (order.sku === "kaju-katli") card.classList.add("has-kaju");
  const time = element("time", order.pickupTime);
  time.dateTime = order.pickupTime;
  card.append(element("span", "#" + order.id), time, element("h3", item(order)),
    element("p", money.format(order.pricePaise / 100), "price"), badge(order));
  const deliveryStatus = delivery(order, rows);
  if (deliveryStatus) card.append(deliveryStatus);
  if (role === "STAFF" || role === "OWNER") card.append(actionButton("Pack order", "pack", order, true));
  return card;
}

function orderRow(order, rows, approval = false) {
  const row = element("article", undefined, approval ? "order-row approval-row" : "order-row");
  const text = element("div");
  text.append(element("h3", `#${order.id} · ${item(order)}`));
  const time = element("p", order.pickupTime, "muted");
  if (approval && order.approvalExpiresAt) {
    const expires = new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .format(new Date(order.approvalExpiresAt));
    time.textContent += ` · approve by ${expires}`;
  }
  text.append(time);
  const deliveryStatus = delivery(order, rows);
  if (deliveryStatus) text.append(deliveryStatus);
  const actions = element("div", undefined, "actions");
  if (approval && role === "OWNER") {
    actions.append(actionButton("Approve", "approve", order), actionButton("Decline", "reject", order));
  } else if (order.status === "CONFIRMED" && (role === "STAFF" || role === "OWNER")) {
    actions.append(actionButton("Pack order", "pack", order));
  } else if (order.returnToTray && (role === "STAFF" || role === "OWNER")) {
    actions.append(actionButton("Returned to tray", "return", order));
  } else {
    actions.append(badge(order));
  }
  row.append(text, actions);
  return row;
}

function list(container, values, create, empty) {
  container.replaceChildren(...(values.length ? values.map(create) : [element("p", empty, "empty")]));
}

function render(orders, stock, policy, deliveries) {
  const next = orders.filter(order => order.status === "CONFIRMED").sort((a, b) => a.pickupTime.localeCompare(b.pickupTime));
  const approvals = orders.filter(order => order.status === "AWAITING_APPROVAL");
  const recent = orders.filter(order => !["CONFIRMED", "AWAITING_APPROVAL"].includes(order.status)).slice(0, 12);
  $("next-pickup").textContent = next[0]?.pickupTime || "—";
  const kajuReady = stock.batches.filter(batch => batch.sku === "kaju-katli" && batch.status === "READY" && !batch.blocked);
  $("tray-stock").textContent = weight(kajuReady.reduce((total, batch) => total + batch.phoneGramsFree, 0));
  $("tray-stock").title = "Kaju katli available from ready phone-tray batches";
  $("pending-count").textContent = `${approvals.length} pending`;
  $("pending-count").dataset.warning = String(approvals.length > 0);
  $("shop-clock").textContent = stock.shopTime ? `Shop time ${stock.shopTime}` : "";
  $("policy-summary").textContent = `${policy.source || "Policy"} · ${policy.version || "version unknown"} · above ${weight(policy.approvalAboveGrams)} needs approval`;
  const policyStatus = $("policy-status");
  const updatedAt = policy.fetchedAt ? new Date(policy.fetchedAt) : null;
  const age = updatedAt ? Math.max(0, Math.floor((Date.now() - updatedAt.getTime()) / 1000)) : null;
  policyStatus.textContent = `Rush mode ${policy.rushMode ? "on" : "off"}`;
  policyStatus.dataset.rush = String(policy.rushMode);
  policyStatus.title = `${policy.source || "Policy"} · ${policy.version || "version unknown"}${age === null ? "" : ` · fetched ${age}s ago`}`;
  $("flagsmith").hidden = role !== "OWNER";
  $("staff-role").textContent = roleName[role] || "";
  const focused = document.activeElement;
  const actionKey = focused?.dataset.action && [focused.dataset.action, focused.dataset.order];
  $("spotlight").replaceChildren(spotlight(next[0], deliveries[next[0]?.id]));
  list($("queue"), next.slice(1), order => orderRow(order, deliveries[order.id]), "");
  list($("approvals"), approvals, order => orderRow(order, deliveries[order.id], true), "No orders awaiting approval.");
  list($("recent"), recent, order => orderRow(order, deliveries[order.id]), "No orders completed yet.");
  list($("batches"), stock.batches, batch => {
    const row = element("div", undefined, "batch");
    row.append(element("span", `${batch.sku === "kaju-katli" ? "Kaju katli" : "Barfi"} · ${batch.status === "READY" ? "Ready" : `Batch ${batch.id} · ${batch.readyTime}`}`),
      element("b", batch.blocked ? "Blocked" : weight(batch.phoneGramsFree)));
    return row;
  }, "No batches available.");
  if (actionKey) workspace.querySelector(`[data-action="${actionKey[0]}"][data-order="${actionKey[1]}"]`)?.focus({ preventScroll: true });
}

async function refresh() {
  if (!token || refreshing) return;
  const access = token;
  refreshing = true;
  try {
    const [orders, stock, policy] = await Promise.all([api("/api/orders"), api("/api/stock"), api("/api/policy")]);
    const sample = orders.slice(0, 24);
    const status = await Promise.allSettled(sample.map(order => api(`/api/orders/${order.id}/messages`)));
    if (access !== token) return;
    const deliveries = Object.fromEntries(sample.map((order, index) => [order.id, status[index].value]));
    const nextSnapshot = JSON.stringify([orders, stock, policy, deliveries]);
    if (snapshot !== nextSnapshot) {
      render(orders, stock, policy, deliveries);
      snapshot = nextSnapshot;
    }
    message("connection", "");
  } catch (error) {
    if (access !== token) return;
    if (error.status === 401 || error.status === 403) {
      lockDesk();
      message("login-error", "Your desk access was rejected. Enter a valid access token.");
    } else {
      message("connection", "Connection interrupted. Status may be out of date. Reconnecting…");
    }
  } finally {
    refreshing = false;
  }
}

function lockDesk() {
  clearInterval(refreshTimer);
  token = "";
  role = "";
  snapshot = "";
  workspace.hidden = true;
  login.hidden = false;
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
    $("desk-tools").hidden = false;
    await refresh();
    if (token) refreshTimer = setInterval(() => {
      if (!document.hidden) void refresh();
    }, 4200);
  } catch (error) {
    lockDesk();
    message("login-error", [401, 403].includes(error.status) ? "That access token was rejected." : "Can’t unlock the desk right now.");
  } finally {
    busy = false;
  }
});

workspace.addEventListener("click", async event => {
  const button = event.target.closest("button[data-action]");
  if (!button || button.disabled || busy) return;
  busy = true;
  button.disabled = true;
  message("desk-error", "");
  try {
    await api(`/api/orders/${button.dataset.order}/${button.dataset.action}`, "POST");
  } catch (error) {
    message("desk-error", error.status === 403 ? "That action needs owner access."
      : error.message === "BATCH_NOT_READY" ? "That batch isn’t ready to pack."
      : "The shop couldn’t apply that action. Check the refreshed order before retrying.");
  } finally {
    button.disabled = false;
    busy = false;
    await refresh();
  }
});
$("refresh").addEventListener("click", () => void refresh());
$("logout").addEventListener("click", lockDesk);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void refresh();
});
