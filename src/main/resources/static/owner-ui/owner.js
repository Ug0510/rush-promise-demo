"use strict";

const $ = id => document.getElementById(id);
const authForm = $("auth-form");
const shopForm = $("shop-form");
const picker = bindPickupHours($("owner-pickup-hours"));
const productEditor = $("product-editor");
const ownerPhone = typeof bindOwnerPhoneSettings === "function"
  ? bindOwnerPhoneSettings($("owner-phone-settings"), (path, method = "GET", body) => api(path, { method, body })) : null;
const currency = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });
const number = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 3 });
let session = null;
let signupEnabled = false;
let authMode = "login";
let optionsLoaded = false;
let products = [];
let selected = null;
let operation = false;
let fetching = false;
let slugEdited = false;
let hoursReady = false;
let demoKey;

function setText(id, text) {
  const slot = $(id);
  slot.textContent = text;
  slot.hidden = !text;
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.method === "POST" && session?.csrfToken) headers["X-CSRF-Token"] = session.csrfToken;
  const response = await fetch(path, {
    credentials: "same-origin",
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: "no-store",
    signal: AbortSignal.timeout(8500)
  });
  const reply = response.status === 204 ? null : await response.json().catch(() => null);
  if (response.ok) return reply;
  const failure = new Error(reply?.detail || reply?.message || "Request failed");
  failure.status = response.status;
  throw failure;
}

function status(isLive) {
  const badge = $("owner-online");
  badge.dataset.status = isLive ? "live" : "stale";
  badge.textContent = isLive ? "Live" : "Reconnecting";
}

function authView(mode = "login") {
  authMode = mode === "signup" && signupEnabled ? "signup" : "login";
  const create = authMode === "signup";
  $("owner-auth").hidden = false;
  if ($("demo-owner-banner")) $("demo-owner-banner").hidden = true;
  $("shop-panel").hidden = $("menu-panel").hidden = $("owner-nav").hidden = $("owner-tools").hidden = true;
  $("brand-shop").hidden = true;
  $("loading").hidden = true;
  $("auth-title").textContent = create ? "Create an owner account" : "Sign in to your shop";
  $("auth-instructions").textContent = create ? "Use this account to manage your shop."
    : "Use your owner account to manage your shop.";
  $("auth-submit").textContent = create ? "Create account" : "Sign in";
  $("setup-guide").hidden = !create;
  $("password-note").hidden = !create;
  if ($("username-note")) $("username-note").hidden = !create;
  $("owner-password").autocomplete = create ? "new-password" : "current-password";
  $("owner-password").minLength = create ? 12 : 1;
  $("owner-password").value = "";
  if ($("owner-password").type !== "password") togglePassword();
  $("auth-alternative").hidden = !signupEnabled;
  $("auth-switch-hint").textContent = create ? "Already have an account?" : "New to Rush Promise?";
  $("auth-switch").textContent = create ? "Sign in" : "Create an account";
  setText("auth-error", "");
  clearCredentialErrors();
}

function togglePassword() {
  const password = $("owner-password");
  const action = $("show-password");
  const revealing = password.type === "password";
  password.type = revealing ? "text" : "password";
  action.setAttribute("aria-pressed", String(revealing));
  action.setAttribute("aria-label", revealing ? "Hide password" : "Show password");
  action.textContent = revealing ? "Hide" : "Show";
}

function signedOut(text) {
  ownerPhone?.close();
  session = null;
  products = [];
  selected = null;
  authView();
  if (text) setText("auth-error", text);
}

function show(view) {
  const shop = session?.shop;
  $("owner-auth").hidden = true;
  if ($("demo-owner-banner")) $("demo-owner-banner").hidden = session?.demoPreview !== true;
  $("loading").hidden = true;
  $("owner-tools").hidden = false;
  $("brand-shop").textContent = shop?.displayName || "";
  $("brand-shop").hidden = !shop;
  $("owner-nav").hidden = !shop;
  const route = shop && view !== "settings" ? "menu" : "settings";
  for (const link of $("owner-nav").querySelectorAll("[data-view]")) {
    if (link.dataset.view === route) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  $("shop-panel").hidden = route !== "settings";
  $("menu-panel").hidden = route !== "menu";
  if (route === "settings" && shop && session?.demoPreview !== true) ownerPhone?.open();
  else ownerPhone?.close();
  if (route === "settings") loadShop(shop);
  else void refreshProducts(!products.length);
}

function loadShop(shop, savedHours) {
  $("shop-title").textContent = shop ? "Shop settings" : "Set up your shop";
  const slug = $("shop-slug");
  slug.readOnly = Boolean(shop);
  slugEdited = Boolean(shop);
  $("slug-help").hidden = Boolean(shop);
  const checkout = $("customer-checkout");
  checkout.hidden = !shop || session?.demoPreview === true;
  if (shop && !checkout.hidden) checkout.href = `/caller?shop=${encodeURIComponent(shop.slug)}`;
  else checkout.removeAttribute("href");
  for (const field of ["displayName", "slug", "area", "hours", "pickupInstructions"]) {
    shopForm.elements.namedItem(field).value = shop?.[field] || "";
  }
  setText("shop-error", "");
  setText("shop-saved", "");
  hoursReady = !shop || Boolean(savedHours);
  picker.load(shop ? savedHours : { allDay: false, opensAt: "09:00", closesAt: "21:00" });
  $("shop-submit").disabled = !hoursReady;
  $("shop-submit").textContent = shop ? "Save settings" : "Save shop";
  if (shop && !savedHours) void loadHours(shop.slug);
}

async function loadHours(slug) {
  try {
    const hours = await api("/api/owner/pickup-hours");
    if (session?.shop?.slug !== slug || $("shop-panel").hidden) return;
    picker.load(hours);
    hoursReady = true;
    $("shop-submit").disabled = false;
  } catch (error) {
    if (error.status === 401) signedOut("Your session ended. Sign in again.");
    else if (session?.shop?.slug === slug && !$("shop-panel").hidden) setText("shop-error", "Pickup hours could not be loaded. Reopen Shop settings to retry.");
  }
}

function selectProduct(product, focus = false) {
  selected = product?.sku || null;
  $("product-title").textContent = product ? "Edit item" : "Add item";
  productEditor.elements.itemName.value = product?.itemName || "";
  productEditor.elements.unit.value = product?.unit || "GRAMS";
  productEditor.elements.price.value = product ? (product.pricePerUnitPaise / 100).toString() : "";
  productEditor.elements.stock.value = product ? product.stockQuantity : "";
  setText("product-error", "");
  setText("product-saved", "");
  labelUnits();
  renderProducts();
  if (focus) productEditor.elements.itemName.focus();
}

function labelUnits() {
  const piece = $("unit").value === "PIECE";
  $("product-price-label").textContent = piece ? "Price per piece" : "Price per kg";
  $("product-stock-label").textContent = piece ? "Pieces set aside for phone pickup" : "Grams set aside for phone pickup";
  showStockMeaning();
}

function showStockMeaning() {
  if (!$("stock-meaning")) return;
  const grams = $("unit").value === "GRAMS";
  const quantity = productEditor.elements.stock.valueAsNumber;
  if (!Number.isInteger(quantity) || quantity < 0) {
    setText("stock-meaning", "");
    return;
  }
  const current = products.find(item => item.sku === selected);
  const saved = current && current.unit === $("unit").value && current.stockQuantity === quantity;
  const reserved = saved && current.freeQuantity !== quantity;
  const display = grams
    ? `${number.format(quantity)} g${quantity >= 1000 ? ` = ${number.format(quantity / 1000)} kg` : ""}`
    : `${number.format(quantity)} ${quantity === 1 ? "piece" : "pieces"}`;
  setText("stock-meaning", reserved ? `${display} set aside · ${qtyLabel(current.freeQuantity, current.unit)} currently available.`
    : `${display} ${saved ? "available" : "set aside"} for pickup.`);
}

function qtyLabel(quantity, unit) {
  return unit === "PIECE" ? `${quantity} ${quantity === 1 ? "piece" : "pieces"}`
    : quantity >= 1000 ? `${quantity / 1000} kg` : `${quantity} g`;
}

function renderProducts() {
  const focusedSku = document.activeElement?.dataset.sku;
  const rows = products.map(item => {
    const row = document.createElement("tr");
    row.dataset.selected = String(item.sku === selected);
    const itemCell = document.createElement("td");
    const priceCell = document.createElement("td");
    const stockCell = document.createElement("td");
    const pick = document.createElement("button");
    pick.className = "item-select";
    pick.type = "button";
    pick.textContent = item.itemName;
    pick.dataset.sku = item.sku;
    pick.setAttribute("aria-pressed", String(item.sku === selected));
    itemCell.append(pick);
    priceCell.textContent = `${currency.format(item.pricePerUnitPaise / 100)} / ${item.unit === "PIECE" ? "piece" : "kg"}`;
    stockCell.textContent = qtyLabel(item.freeQuantity, item.unit);
    if (item.freeQuantity !== item.stockQuantity) {
      stockCell.title = `${qtyLabel(item.stockQuantity, item.unit)} counted, ${qtyLabel(item.freeQuantity, item.unit)} available to reserve`;
    }
    row.append(itemCell, priceCell, stockCell);
    return row;
  });
  $("product-rows").replaceChildren(...rows);
  $("menu-empty").hidden = products.length > 0;
  const menuLinks = $("menu-links");
  if (menuLinks) {
    menuLinks.hidden = !session?.shop || products.length === 0;
    $("menu-customer-checkout").hidden = session?.demoPreview === true;
    if (session?.shop && session?.demoPreview !== true) {
      $("menu-customer-checkout").href = `/caller?shop=${encodeURIComponent(session.shop.slug)}`;
    } else $("menu-customer-checkout").removeAttribute("href");
  }
  if (focusedSku) [...$("product-rows").querySelectorAll("button")].find(item => item.dataset.sku === focusedSku)?.focus();
}

async function refreshProducts(chooseFirst = false) {
  if (!session?.shop || fetching) return;
  fetching = true;
  try {
    products = await api("/api/owner/products");
    if (chooseFirst) selectProduct(products[0]);
    else {
      renderProducts();
      showStockMeaning();
    }
    setText("menu-error", "");
    status(true);
  } catch (error) {
    if (error.status === 401) signedOut("Your session ended. Sign in again.");
    else {
      setText("menu-error", "Menu and stock could not be refreshed.");
      status(false);
    }
  } finally {
    fetching = false;
  }
}

function paise(raw) {
  const original = raw.trim();
  if (!/^\d{1,8}(?:\.\d{1,2})?$/.test(original)) return null;
  const [whole, fractional = ""] = original.split(".");
  return Number(whole) * 100 + Number(fractional.padEnd(2, "0"));
}

function clearCredentialErrors() {
  for (const field of ["username", "password"]) {
    if ($(field + "-error")) setText(field + "-error", "");
    $("owner-" + field).removeAttribute("aria-invalid");
  }
}

function credentialError(field, message) {
  const input = $("owner-" + field);
  input.setAttribute("aria-invalid", "true");
  setText($(field + "-error") ? field + "-error" : "auth-error", message);
  input.focus();
}

authForm.noValidate = true;
authForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (operation || (authMode === "signup" && !signupEnabled)) return;
  const username = $("owner-username").value.trim();
  const password = $("owner-password").value;
  clearCredentialErrors();
  setText("auth-error", "");
  if (!username) {
    credentialError("username", "Enter a username.");
    return;
  }
  if (authMode === "signup" && !/^[a-z0-9][a-z0-9._-]{2,39}$/i.test(username)) {
    credentialError("username", "Use a username with 3–40 characters. Start with a letter or number and leave out spaces and @.");
    return;
  }
  if (!password || (authMode === "signup" && (password.length < 12 || password.length > 128))) {
    credentialError("password", authMode === "signup" ? "Use 12 to 128 characters for your password." : "Enter your password.");
    return;
  }
  operation = true;
  $("auth-submit").disabled = true;
  setText("auth-error", "");
  try {
    session = await api(`/api/auth/${authMode}`, { method: "POST", body: {
      username, password
    } });
    $("owner-password").value = "";
    show(location.hash === "#settings" ? "settings" : "menu");
    status(true);
  } catch (error) {
    const text = error.status === 401 ? "Username or password is incorrect."
      : error.status === 409 ? "That username is already in use."
      : error.status === 429 ? "Too many attempts. Try again in a moment."
      : error.status === 404 ? "New accounts aren't available here. Sign in with your owner account."
      : error.status === 400 ? "Check your username and password." : "Sign-in is temporarily unavailable.";
    if (error.status === 404) authView("login");
    setText("auth-error", text);
  } finally {
    operation = false;
    $("auth-submit").disabled = false;
  }
});

$("start-owner-demo")?.addEventListener("click", async () => {
  if (operation || session) return;
  const button = $("start-owner-demo");
  operation = true;
  button.disabled = true;
  button.textContent = "Opening sample shop…";
  setText("demo-entry-error", "");
  try {
    try {
      demoKey ||= sessionStorage.getItem("rush-owner-preview") || crypto.randomUUID();
      sessionStorage.setItem("rush-owner-preview", demoKey);
    } catch {
      demoKey ||= crypto.randomUUID();
    }
    session = await api("/api/auth/demo", { method: "POST", body: { sessionKey: demoKey } });
    history.replaceState(null, "", "#menu");
    $("owner-password").value = "";
    show("menu");
    status(true);
  } catch (error) {
    setText("demo-entry-error", error.status === 409 ? "An owner is already signed in. Reload to continue in that shop."
      : "The sample shop could not open. Try again in a moment.");
  } finally {
    operation = false;
    button.disabled = false;
    button.textContent = "Try Deepak’s demo shop";
  }
});

$("show-password").addEventListener("click", togglePassword);
$("auth-switch").addEventListener("click", () => authView(authMode === "login" ? "signup" : "login"));
$("shop-slug").addEventListener("input", () => { slugEdited = true; });
$("public-name").addEventListener("input", () => {
  const slug = $("shop-slug");
  if (slugEdited) return;
  slug.value = $("public-name").value.toLowerCase().replace(/['’]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 48);
});

shopForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (!session || operation || !hoursReady) return;
  operation = true;
  $("shop-submit").disabled = true;
  setText("shop-error", "");
  const existing = Boolean(session.shop);
  const data = new FormData(shopForm);
  const body = Object.fromEntries(["displayName", "slug", "area", "hours", "pickupInstructions"].map(field => [field, data.get(field)]));
  try {
    try { body.pickupHours = picker.read(); }
    catch (error) { setText("shop-error", error.message); return; }
    if (existing) {
      const saved = await api("/api/owner/shop/settings", { method: "POST", body });
      session.shop = saved.shop;
      $("brand-shop").textContent = saved.shop.displayName;
      loadShop(saved.shop, saved.pickupHours);
      setText("shop-saved", "Shop settings saved.");
    } else {
      session.shop = await api("/api/owner/shop", { method: "POST", body });
      show("menu");
      if ($("new-owner-phone")) $("new-owner-phone").hidden = false;
    }
    status(true);
  } catch (error) {
    if (error.status === 401) signedOut("Your session ended. Sign in again.");
    else setText("shop-error", error.status === 409 ? "That shop link is already in use." : "Check the shop details and try again.");
  } finally {
    operation = false;
    $("shop-submit").disabled = !hoursReady;
  }
});

productEditor.addEventListener("submit", async event => {
  event.preventDefault();
  if (!session?.shop || operation) return;
  const controls = productEditor.elements;
  const pricePerUnitPaise = paise(controls.price.value);
  const stockQuantity = controls.stock.valueAsNumber;
  if (pricePerUnitPaise === null || pricePerUnitPaise <= 0 || !Number.isInteger(stockQuantity) || stockQuantity < 0) {
    setText("product-error", "Enter a price in rupees and a whole stock quantity.");
    return;
  }
  operation = true;
  $("product-save").disabled = true;
  setText("product-error", "");
  setText("product-saved", "");
  try {
    const saved = await api("/api/owner/products", { method: "POST", body: {
      sku: selected || undefined, itemName: controls.itemName.value.trim(),
      unit: controls.unit.value, pricePerUnitPaise, stockQuantity
    } });
    products = [...products.filter(item => item.sku !== saved.sku), saved].sort((a, b) => a.itemName.localeCompare(b.itemName));
    selectProduct(saved);
    setText("product-saved", "Item saved.");
    status(true);
  } catch (error) {
    if (error.status === 401) signedOut("Your session ended. Sign in again.");
    else setText("product-error", error.status === 409
      ? "That change conflicts with reserved stock or an existing item."
      : "The item could not be saved. Check the entered price and stock.");
  } finally {
    operation = false;
    $("product-save").disabled = false;
  }
});

$("add-item").addEventListener("click", () => selectProduct(null, true));
$("unit").addEventListener("change", labelUnits);
productEditor.elements.stock.addEventListener("input", showStockMeaning);
$("product-rows").addEventListener("click", event => {
  const button = event.target.closest("button[data-sku]");
  if (button) selectProduct(products.find(item => item.sku === button.dataset.sku));
});
$("owner-nav").addEventListener("click", event => {
  const tab = event.target.closest("a[data-view]");
  if (!tab) return;
  event.preventDefault();
  history.replaceState(null, "", tab.hash);
  show(tab.dataset.view);
});
$("open-new-owner-phone")?.addEventListener("click", event => {
  event.preventDefault();
  history.replaceState(null, "", "#settings");
  show("settings");
  $("owner-phone-settings")?.scrollIntoView({block: "nearest"});
});
$("owner-logout").addEventListener("click", async () => {
  if (!session || operation) return;
  operation = true;
  try {
    await api("/api/auth/logout", { method: "POST" });
    if (session.demoPreview === true) {
      demoKey = null;
      try { sessionStorage.removeItem("rush-owner-preview"); } catch { }
    }
    history.replaceState(null, "", location.pathname);
    signedOut();
  } catch (error) {
    if (error.status === 401) signedOut();
    else status(false);
  } finally {
    operation = false;
  }
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && !$("menu-panel").hidden) void refreshProducts();
});

async function load() {
  const [options, owner] = await Promise.allSettled([api("/api/auth/options"), api("/api/auth/session")]);
  optionsLoaded = options.status === "fulfilled";
  signupEnabled = optionsLoaded && options.value?.signupEnabled === true;
  if (owner.status === "fulfilled") {
    session = owner.value;
    show(location.hash === "#settings" ? "settings" : "menu");
    return;
  }
  authView(location.hash === "#signup" ? "signup" : "login");
  if (owner.reason.status !== 401 && owner.reason.status !== 404) setText("auth-error", "The owner workspace could not connect. Try again.");
}
void load();
