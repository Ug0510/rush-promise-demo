"use strict";

const publicSite = document.getElementById("demo-phone");
const create = document.getElementById("create-store");
const search = document.getElementById("shop-search");
const searchStatus = document.getElementById("shop-status");
const results = document.getElementById("shop-results");
const slugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
let searchRequest;
let sharedLine;

async function read(path, requestSignal) {
  const signal = requestSignal ?? AbortSignal.timeout(6000);
  const reply = await fetch(path, { headers: { Accept: "application/json" }, signal });
  if (!reply.ok) throw new Error("Could not load");
  return reply.json();
}

async function loadPhone() {
  try {
    const site = await read("/api/public/site");
    if (site.voiceAvailable === false || typeof site.phone !== "string" || !/^\+91[0-9]{10}$/.test(site.phone)) {
      document.getElementById("home-voice-status").hidden = false;
      return;
    }
    sharedLine = site.phone;
    publicSite.href = `tel:${sharedLine}`;
    publicSite.textContent = `${sharedLine.slice(0, 3)} ${sharedLine.slice(3, 8)} ${sharedLine.slice(8)}`;
    publicSite.hidden = false;
    document.getElementById("shared-line-instruction").hidden = false;
    document.querySelectorAll("[data-shop-call]").forEach(enableCalling);
  } catch {
    document.getElementById("home-voice-status").hidden = false;
  }
}

function enableCalling(link) {
  if (!sharedLine) return;
  link.href = `tel:${sharedLine}`;
  link.querySelector(".store-action").hidden = false;
}

async function checkSignup() {
  try {
    const options = await read("/api/auth/options");
    if (options.signupEnabled !== false) return;
    create.href = "/owner";
    create.firstElementChild.textContent = "Sign in to your store";
  } catch {
    // The owner page reads signup availability before it enables its form.
  }
}

function shopRow(shop) {
  const item = document.createElement("li");
  item.className = "store-card search-result";
  const link = document.createElement("a");
  link.className = "store-call";
  link.dataset.shopCall = "";
  link.setAttribute("aria-label", `Call ${shop.displayName}`);
  const description = document.createElement("span");
  description.className = "store-detail";
  const name = document.createElement("strong");
  name.textContent = shop.displayName;
  description.append(name);
  if (shop.area) {
    const area = document.createElement("span");
    area.textContent = shop.area;
    description.append(area);
  }
  const arrow = document.createElement("span");
  arrow.className = "store-action";
  arrow.setAttribute("aria-hidden", "true");
  arrow.textContent = "Call shop →";
  arrow.hidden = true;
  link.append(description, arrow);
  enableCalling(link);
  const typed = document.createElement("a");
  typed.className = "store-type";
  typed.href = `/caller?shop=${encodeURIComponent(shop.slug)}&mode=type`;
  typed.textContent = "Type an order";
  typed.setAttribute("aria-label", `Type an order for ${shop.displayName}`);
  item.append(link, typed);
  return item;
}

search.addEventListener("submit", async event => {
  event.preventDefault();
  const query = document.getElementById("shop-query").value.trim();
  if (!query) return;

  searchRequest?.abort();
  const current = new AbortController();
  searchRequest = current;
  results.replaceChildren();
  searchStatus.textContent = "Searching…";
  try {
    const data = await read(`/api/public/shops?${new URLSearchParams({ query })}`,
       AbortSignal.any([current.signal, AbortSignal.timeout(6000)]));
    if (current !== searchRequest) return;
    const shops = (data.shops || []).filter(shop => slugPattern.test(shop.slug) && typeof shop.displayName === "string");
    results.replaceChildren(...shops.map(shopRow));
    searchStatus.textContent = !shops.length ? "No matching shops found."
      : data.truncated ? "Showing the first matches. Refine the shop name or area." : `${shops.length} ${shops.length === 1 ? "shop" : "shops"} found.`;
  } catch {
    if (current === searchRequest) searchStatus.textContent = "Could not search for shops. Try again.";
  }
});

void loadPhone();
void checkSignup();
