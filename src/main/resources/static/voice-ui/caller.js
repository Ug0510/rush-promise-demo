"use strict";

const $ = id => document.getElementById(id);
const form = $("caller-form");
const speak = $("speak");
const replay = $("replay");
const restart = $("restart");
const retry = $("retry");
const player = $("readback");
const caller = document.querySelector(".caller");
const indicator = $("recording-indicator");
const wave = $("audio-meter");
const heading = $("caller-heading");
const speakLabel = $("speak-label");
const actionHint = $("action-hint");
const typedForm = $("typed-order");
const typedInput = $("typed-utterance");
const typedQuote = $("typed-quote");
const typedConfirm = $("typed-confirm");
const amount = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0 });
const policyProfiles = { NORMAL: "Normal", RUSH: "Rush", BUSY_SLOT: "Busy slot", FALLBACK: "Safe default" };
let session, sequence, quote, playedQuote, speakingQuote, recorder, pending, audioUrl, sealed, working;
let microphoneTrack;
const requestedShop = new URLSearchParams(location.search).get("shop");
const requestedMode = new URLSearchParams(location.search).get("mode");
let activeShop;
let shopAvailable = requestedShop === null;
let timer, receiptTimer;
let checkingReceipt = false;
let receiptChecks = 0;
let entryMode = "speak";
let voiceAvailable = true;
let typedPending;
let typedTicket;
let typedLastText = "";
let typedNeedsApproval = false;
let armingRecorder = false;

function beginSession() {
  session = globalThis.crypto?.randomUUID?.();
  sequence = 0;
  quote = null;
  playedQuote = null;
  pending = null;
  typedPending = null;
  checkingReceipt = false;
  receiptChecks = 0;
  clearTimeout(receiptTimer);
  sealed = false;
  working = false;
  form.hidden = entryMode === "type";
  caller.dataset.stage = "collecting";
  heading.textContent = entryMode === "type" ? "Type your order" : "Speak your order";
  $("intro").hidden = false;
  $("intro").textContent = entryMode === "type" ? "Enter the item, quantity and pickup time. Pay cash when you collect it."
    : "Say what you’d like, how much, and when you’ll pick it up.";
  actionHint.hidden = true;
  restart.hidden = true;
  $("heard").hidden = true;
  $("details").hidden = true;
  $("reply").hidden = true;
  retry.hidden = true;
  retry.textContent = "Retry this turn";
  speak.hidden = false;
  speak.disabled = !shopAvailable;
  speakLabel.textContent = "Start speaking";
  clearAudio();
  showError("");
  showPhase("idle", "Microphone off");
  syncEntry();
}

function showError(text) {
  $("error").textContent = text;
  $("error").hidden = text.length === 0;
}

function showPhase(phase, text) {
  wave.dataset.phase = phase;
  indicator.dataset.phase = phase;
  $("mic-state").textContent = text;
}

function clearAudio() {
  player.pause();
  if (audioUrl) URL.revokeObjectURL(audioUrl);
  audioUrl = null;
  speakingQuote = null;
  player.removeAttribute("src");
  replay.hidden = true;
  wave.hidden = true;
}

async function playReply() {
  if (!audioUrl) return;
  try {
    player.currentTime = 0;
    await player.play();
  } catch {
    showPhase("idle", "Tap Replay to hear the read-back");
  }
}

function formatTime(value, pickupAt) {
  const [hour, minute] = value.split(":").map(Number);
  const time = `${hour % 12 || 12}:${String(minute).padStart(2, "0")} ${hour >= 12 ? "PM" : "AM"}`;
  if (!pickupAt) return time;
  const calendar = new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", day: "numeric", month: "short" });
  const date = calendar.format(new Date(pickupAt));
  return date === calendar.format(new Date()) ? time : `${date}, ${time}`;
}

function displayQuantity(quantity, unit) {
  return unit === "PIECE" ? `${quantity} ${quantity === 1 ? "piece" : "pieces"}`
    : quantity >= 1000 ? `${quantity / 1000} kg` : `${quantity} g`;
}

function loadAudio(encoded, promptQuote = null) {
  clearAudio();
  const wav = Uint8Array.from(atob(encoded), character => character.charCodeAt(0));
  audioUrl = URL.createObjectURL(new Blob([wav], { type: "audio/wav" }));
  speakingQuote = promptQuote;
  player.src = audioUrl;
  replay.hidden = false;
  wave.hidden = false;
  $("heard").hidden = false;
  void playReply();
}

function renderPolicy(facts, quoted) {
  const policy = facts?.decision;
  $("quote-policy").hidden = !policy;
  if (!policy) return;
  const profile = policyProfiles[policy.profile] || "Shop policy";
  const source = { LIVE: "Live", CACHED: "Cached", FALLBACK: "Safe default" }[policy.source] || policy.source;
  const term = quoted ? (facts.requiresApproval ? "Approval needed" : "Confirmable") : "Saved policy";
  $("quote-policy-status").textContent = [profile, term].join(" · ");
  const fetched = policy.fetchedAt && new Date(policy.fetchedAt).toLocaleTimeString("en-IN", {
    hour: "numeric", minute: "2-digit", second: "2-digit", timeZone: "Asia/Kolkata"
  });
  $("quote-policy-version").textContent = [source, policy.digest?.slice(0, 12), fetched && `Fetched ${fetched} IST`]
    .filter(Boolean).join(" · ");
}

function render(result) {
  const turn = result.turn;
  const facts = turn.order || turn.quote;
  quote = turn.state === "AWAITING_CONFIRMATION" ? turn.quote?.id || null : null;
  typedNeedsApproval = Boolean(turn.quote?.requiresApproval);
  playedQuote = null;
  sealed = Boolean(turn.order) || turn.state === "ENDED";
  const pendingApproval = turn.order?.status === "AWAITING_APPROVAL";
  const awaiting = turn.state === "CHECKING_BOOKING";
  caller.dataset.stage = quote ? "quote" : sealed ? "complete" : awaiting ? "checking" : "collecting";
  heading.textContent = quote ? "Confirm your pickup" : pendingApproval ? "Request received"
    : turn.order ? "Pickup confirmed" : awaiting ? "Checking your booking"
    : turn.state === "ENDED" ? "Ordering session ended" : entryMode === "type" ? "Type your order" : "Speak your order";
  $("intro").hidden = Boolean(quote || sealed || awaiting);
  $("heard").hidden = !turn.transcript && !result.audioWavBase64;
  $("transcript-section").hidden = !turn.transcript;
  $("transcript").textContent = turn.transcript || "";
  $("details").hidden = !facts;
  $("approval-note").hidden = !turn.quote?.requiresApproval || Boolean(facts?.decision);
  renderPolicy(facts, Boolean(turn.quote));
  $("order-number").hidden = !turn.order;
  if (facts) {
    const kaju = facts.itemName.toLowerCase() === "kaju katli";
    $("quote-product").classList.toggle("has-kaju", kaju);
    $("sweet-art").hidden = !kaju;
    $("card-title").textContent = turn.order ? "Your pickup" : "Your quote";
    $("item").textContent = facts.itemName;
    const quantity = facts.quantity ?? facts.grams;
    $("grams").textContent = displayQuantity(quantity, facts.unit);
    $("pickup").textContent = formatTime(facts.pickupTime, facts.pickupAt);
    $("amount").textContent = amount.format(facts.pricePaise / 100);
    if (turn.order) {
      const code = turn.order.pickupCode;
      $("order-number").textContent = `Order #${turn.order.id}${code ? ` · ${pendingApproval ? "Reference" : "Pickup"} code ${code}` : ""}`;
    }
  }
  const reply = $("reply");
  reply.hidden = !turn.replyText;
  reply.lang = turn.language || "hi-IN";
  reply.dataset.audio = result.audioWavBase64 && !result.audioFallback ? "ready" : "unavailable";
  reply.textContent = turn.replyText || "";
  restart.hidden = !sealed;
  speak.hidden = sealed;
  speakLabel.textContent = quote && !result.audioWavBase64 ? "Say repeat or change your order"
    : quote ? "Confirm by voice" : "Speak again";
  actionHint.hidden = !quote;
  actionHint.textContent = turn.quote?.requiresApproval ? "Say yes to request shop approval." : "Say yes to reserve this pickup.";
  $("replay-label").textContent = quote ? "Replay quote" : "Replay response";
  showPhase("idle", quote ? "Read-back ready" : "Microphone off");
  clearAudio();
  if (result.audioWavBase64) loadAudio(result.audioWavBase64, result.audioFallback ? null : quote);
  syncEntry();
}

async function sendTurn() {
  if (!pending || working) return;
  const receiptOnly = checkingReceipt;
  const data = new FormData();
  for (const [key, value] of pending.entries()) {
    if (!receiptOnly || key !== "audio") data.set(key, value);
  }
  if (receiptOnly) data.set("receiptOnly", "true");
  working = true;
  speak.disabled = true;
  retry.hidden = true;
  syncEntry();
  showError("");
  if (!receiptOnly) showPhase("processing", "Transcribing your order…");
  try {
    const response = await fetch("/api/voice/browser/turn", {
      method: "POST",
      body: data,
      headers: activeShop ? { "X-Rush-Shop": activeShop } : undefined,
      signal: AbortSignal.timeout(22000)
    });
    if (!response.ok) {
      if (response.status === 429) {
        const busy = await response.json().catch(() => null);
        if (busy?.audioWavBase64) loadAudio(busy.audioWavBase64);
      }
      const known = {
        400: "That recording couldn’t be read. Try speaking again.",
        403: "Use the mobile number enrolled for this demo.",
        413: "The recording was too long. Try a shorter turn.",
        429: "Voice is busy. Try this turn again in a moment.",
        503: "Speech is temporarily unavailable. You can retry this turn."
      };
      if (response.status < 429 && !receiptOnly) {
        pending = null;
        if (sequence === 1) form.hidden = false;
      }
      throw new Error(known[response.status] || "Voice service could not respond. Retry this turn.");
    }
    const result = await response.json();
    checkingReceipt = result.turn.state === "CHECKING_BOOKING";
    if (!checkingReceipt) {
      pending = null;
      render(result);
    } else if (!receiptOnly) {
      render(result);
      receiptChecks = 0;
    }
  } catch (error) {
    showError(error instanceof TypeError || error.name === "TimeoutError"
      ? "Can’t reach the voice service. Check your connection and retry this turn." : error.message);
    retry.hidden = !pending;
    showPhase("idle", "Microphone off");
  } finally {
    working = false;
    speak.disabled = Boolean(pending);
    syncEntry();
    if (checkingReceipt && pending) {
      clearTimeout(receiptTimer);
      retry.textContent = "Check booking status";
      if (++receiptChecks <= 6) {
        receiptTimer = setTimeout(() => void sendTurn(), 2000);
      } else {
        retry.hidden = false;
      }
    } else {
      retry.textContent = "Retry this turn";
    }
  }
}

function syncEntry() {
  if (!typedForm) return;
  const isTyping = entryMode === "type";
  const locked = working || armingRecorder || Boolean(pending || typedPending) || recorder?.state === "recording";
  caller.dataset.entry = entryMode;
  $("checkout-method").hidden = !activeShop || sealed;
  $("mode-speak").disabled = Boolean(locked || !voiceAvailable);
  $("mode-type").disabled = Boolean(locked);
  $("mode-speak").setAttribute("aria-pressed", String(!isTyping));
  $("mode-type").setAttribute("aria-pressed", String(isTyping));
  $("voice-availability").hidden = voiceAvailable;
  typedForm.hidden = !isTyping || sealed;
  const example = $("typed-example");
  example.hidden = Boolean(quote) || !example.textContent;
  speak.hidden = isTyping || sealed || (Boolean(activeShop) && !voiceAvailable);
  if (isTyping) form.hidden = true;
  const changed = typedInput.value.trim() !== typedLastText;
  typedInput.disabled = Boolean(locked);
  typedQuote.hidden = Boolean(quote && !changed);
  typedQuote.disabled = Boolean(locked);
  typedQuote.textContent = quote ? "Update pickup quote" : "Get pickup quote";
  typedConfirm.hidden = !isTyping || !quote || changed || sealed;
  typedConfirm.disabled = Boolean(locked);
  typedConfirm.textContent = typedNeedsApproval ? "Request shop approval" : "Confirm pickup";
  if (isTyping) {
    actionHint.hidden = !quote || changed || sealed;
    actionHint.textContent = typedNeedsApproval ? "The shop needs to approve this pickup."
      : "Reserve this pickup and pay in cash when you collect it.";
  }
}

function guestState(operation) {
  try {
    const key = `rush-pickup-guest:${activeShop}`;
    if (operation === "read") return JSON.parse(sessionStorage.getItem(key));
    if (operation === "write") sessionStorage.setItem(key, JSON.stringify(typedTicket));
    else sessionStorage.removeItem(key);
  } catch {
    // Checkout remains usable without tab storage.
  }
}

async function typedApi(path, body, timeout = 14500) {
  const response = await fetch(path, {
    method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json", "X-Rush-Shop": activeShop },
    body: JSON.stringify(body), cache: "no-store", signal: AbortSignal.timeout(timeout)
  });
  if (!response.ok) {
    const failure = new Error("The typed ordering service could not respond.");
    failure.status = response.status;
    throw failure;
  }
  return response.json();
}

function chooseEntry(mode) {
  if (pending || typedPending || working || armingRecorder || recorder?.state === "recording") return;
  entryMode = mode;
  beginSession();
  if (mode !== "type") return;
  typedTicket = guestState("read");
  typedLastText = typedTicket?.text || "";
  typedInput.value = typedLastText;
  if (typedTicket?.lastTurnId && Number.isSafeInteger(typedTicket.sequence)) {
    typedPending = {
      request: { sessionId: typedTicket.sessionId, guestToken: typedTicket.guestToken, turnId: typedTicket.lastTurnId,
        sequence: typedTicket.sequence, language: typedTicket.language, receiptOnly: true },
      checking: true, checks: 0
    };
    void sendTyped();
  }
}

async function reserveTyped(confirm) {
  const draft = typedInput.value.trim();
  if (!activeShop || sealed || working || typedPending || !draft
      || (confirm && (!quote || draft !== typedLastText))) return;
  working = true;
  showError("");
  syncEntry();
  try {
    if (!typedTicket) {
      const ticket = await typedApi("/api/checkout/guest", {});
      if (!ticket.sessionId || !ticket.guestToken) throw new Error("Could not start a guest reservation.");
      typedTicket = { ...ticket, sequence: 0 };
    }
    const turnId = crypto.randomUUID();
    const language = /[\u0900-\u097f]/.test(draft) ? "hi-IN" : "en-IN";
    typedTicket = { ...typedTicket, sequence: typedTicket.sequence + 1, lastTurnId: turnId, language, text: draft };
    guestState("write");
    typedPending = {
      request: { sessionId: typedTicket.sessionId, guestToken: typedTicket.guestToken, turnId,
        sequence: typedTicket.sequence, language, ...(confirm ? { confirm: true, confirmationQuoteId: quote } : { utterance: draft }) },
      checking: false, checks: 0, draft
    };
  } catch (error) {
    showError(error.status === 429 ? "Too many requests. Wait a moment, then try again." : "Could not start a guest reservation. Try again.");
  } finally {
    working = false;
    syncEntry();
  }
  if (typedPending) void sendTyped();
}

async function sendTyped() {
  if (working || !typedPending) return;
  const current = typedPending;
  working = true;
  retry.hidden = true;
  showError("");
  syncEntry();
  try {
    const body = current.checking ? {
      sessionId: current.request.sessionId, guestToken: current.request.guestToken, turnId: current.request.turnId,
      sequence: current.request.sequence, language: current.request.language, receiptOnly: true
    } : current.request;
    const turn = await typedApi("/api/checkout/text", body);
    if (current.draft) typedLastText = current.draft;
    render({ turn });
    if (turn.state === "CHECKING_BOOKING") current.checking = true;
    else typedPending = null;
  } catch (error) {
    if ([400, 401, 403, 404, 409].includes(error.status)) {
      typedPending = null;
      if ([401, 403, 404].includes(error.status)) {
        guestState("clear");
        typedTicket = null;
      }
      quote = null;
      showError("That reservation could not be completed. Request a new pickup quote.");
    } else {
      if (current.request.confirm) current.checking = true;
      showError(current.checking ? "Checking whether your pickup was reserved." : "Could not reach typed ordering. Retry this turn.");
      retry.hidden = false;
    }
  } finally {
    working = false;
    syncEntry();
    if (typedPending?.checking) {
      retry.textContent = "Check booking status";
      if (++current.checks <= 6) receiptTimer = setTimeout(() => void sendTyped(), 1600);
      else retry.hidden = false;
    } else retry.textContent = "Retry this turn";
  }
}

async function showOrderExample(menu) {
  const item = menu?.items?.find(product => product.portions?.length);
  const portion = item?.portions?.find(part => part.readyPickup || part.futurePickup) || item?.portions?.[0];
  if (!portion) return;
  const quantity = portion.quantity ?? portion.grams;
  const params = new URLSearchParams({ sku: item.sku, quantity });
  try {
    const response = await fetch(`/api/storefront/${encodeURIComponent(activeShop)}?${params}`, {
      headers: { Accept: "application/json" }, cache: "no-store", signal: AbortSignal.timeout(6500)
    });
    if (!response.ok) return;
    const { availablePickups } = await response.json();
    const sample = availablePickups?.[0];
    const order = `${displayQuantity(quantity, item.unit)} ${item.itemName}`;
    const example = $("typed-example");
    example.textContent = sample ? `Example · ${order} · ${amount.format(sample.pricePaise / 100)} · ${formatTime(sample.pickupTime, sample.pickupAt)}`
      : `Example · ${order} · ${amount.format(portion.pricePaise / 100)}. Ask for an available pickup time.`;
    syncEntry();
    typedInput.placeholder = sample ? `${order} at ${formatTime(sample.pickupTime, sample.pickupAt)}` : order;
  } catch {
    // The quote itself still checks availability if a sample cannot load.
  }
}

async function recordTurn() {
  if (quote && !playedQuote && audioUrl) {
    showError("Play the read-back before confirming by voice.");
    void playReply();
    return;
  }
  if (!session || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
    showError("To send a voice order, open this page with HTTPS in a browser that supports microphone recording.");
    return;
  }
  speak.disabled = true;
  armingRecorder = true;
  syncEntry();
  showError("");
  try {
    if (!/^[+0-9 ()-]{8,22}$/.test($("phone").value.trim())) {
      throw new Error("Enter the mobile number for your order.");
    }
    player.pause();
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    microphoneTrack = stream.getAudioTracks()[0];
    const mimeType = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm", "audio/ogg;codecs=opus"]
      .find(type => MediaRecorder.isTypeSupported(type));
    recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    const chunks = [];
    const prompt = playedQuote;
    recorder.addEventListener("dataavailable", event => {
      if (event.data.size) chunks.push(event.data);
    });
    recorder.addEventListener("stop", () => {
      clearTimeout(timer);
      stream.getTracks().forEach(track => track.stop());
      microphoneTrack = null;
      const type = (recorder.mimeType || chunks[0]?.type || "audio/webm").split(";")[0];
      recorder = null;
      pending = new FormData();
      pending.set("sessionId", session);
      pending.set("turnId", crypto.randomUUID());
      pending.set("sequence", String(++sequence));
      pending.set("customerName", $("customer").value.trim());
      pending.set("phone", $("phone").value.trim());
      if (prompt) pending.set("confirmationQuoteId", prompt);
      const extension = type === "audio/mp4" ? "mp4" : type === "audio/ogg" ? "ogg" : "webm";
      pending.set("audio", new Blob(chunks, { type }), `turn.${extension}`);
      form.hidden = true;
      speakLabel.textContent = quote ? "Confirm by voice" : "Speak again";
      void sendTurn();
    }, { once: true });
    recorder.start();
    speakLabel.textContent = "Done speaking";
    showPhase("recording", "Listening…");
    timer = setTimeout(() => {
      if (recorder?.state === "recording") recorder.stop();
    }, 12000);
  } catch (error) {
    microphoneTrack?.stop();
    microphoneTrack = null;
    showError(error.name === "NotAllowedError"
      ? "Microphone permission was blocked. Allow it for this site, then try again."
      : error.name === "NotFoundError" ? "No microphone was found." : error.message || "Microphone unavailable.");
  } finally {
    armingRecorder = false;
    speak.disabled = false;
    syncEntry();
  }
}

form.addEventListener("submit", event => {
  event.preventDefault();
  if (!shopAvailable || sealed || working || pending || armingRecorder || entryMode !== "speak") return;
  if (recorder?.state === "recording") {
    speak.disabled = true;
    recorder.stop();
  } else {
    void recordTurn();
  }
});
replay.addEventListener("click", () => void playReply());
retry.addEventListener("click", () => {
  if (entryMode === "type") void sendTyped();
  else void sendTurn();
});
restart.addEventListener("click", () => {
  if (entryMode === "type") {
    guestState("clear");
    typedTicket = null;
    typedLastText = "";
    typedInput.value = "";
  }
  beginSession();
});
if (typedForm) {
  typedForm.addEventListener("submit", event => {
    event.preventDefault();
    void reserveTyped(false);
  });
  typedConfirm.addEventListener("click", () => void reserveTyped(true));
  typedInput.addEventListener("input", syncEntry);
  for (const mode of ["speak", "type"]) {
    $(`mode-${mode}`).addEventListener("click", () => {
      if (entryMode === mode) return;
      const next = new URL(location.href);
      next.searchParams.set("mode", mode);
      history.replaceState(null, "", next);
      chooseEntry(mode);
    });
  }
}
player.addEventListener("play", () => showPhase("playing", "Playing read-back…"));
player.addEventListener("ended", () => {
  if (speakingQuote && speakingQuote === quote) playedQuote = speakingQuote;
  showPhase("idle", quote ? "Ready for your answer" : "Microphone off");
});
window.addEventListener("pagehide", () => {
  microphoneTrack?.stop();
  clearTimeout(receiptTimer);
});
beginSession();
if (requestedShop !== null) void identifyShop();

async function identifyShop() {
  try {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(requestedShop) || requestedShop.length > 48) throw new Error();
    const response = await fetch(`/api/storefront/${encodeURIComponent(requestedShop)}`, {
      headers: { Accept: "application/json" }, cache: "no-store", signal: AbortSignal.timeout(8000)
    });
    if (!response.ok) throw new Error();
    const { shop, menu } = await response.json();
    if (shop?.slug !== requestedShop || typeof shop.displayName !== "string" || !shop.displayName.trim()) throw new Error();
    activeShop = shop.slug;
    shopAvailable = true;
    $("caller-shop").textContent = shop.displayName;
    $("caller-shop").hidden = false;
    document.title = `Reserve a pickup · ${shop.displayName}`;
    speak.disabled = false;
    if (typedForm) {
      if (requestedMode === "type") chooseEntry("type");
      else syncEntry();
      void showOrderExample(menu);
      void loadVoiceAvailability();
    }
  } catch {
    heading.textContent = "Shop unavailable";
    $("intro").hidden = true;
    form.hidden = true;
    speak.hidden = true;
    showError("This shop couldn’t be opened. Check the checkout link or try reloading.");
  }
}

async function loadVoiceAvailability() {
  try {
    const response = await fetch("/api/public/site", { cache: "no-store", signal: AbortSignal.timeout(4600) });
    if (!response.ok) return;
    const site = await response.json();
    if (site.voiceAvailable !== false) return;
    voiceAvailable = false;
    if (entryMode === "speak" && !sealed && !pending) chooseEntry("type");
    syncEntry();
  } catch {
    // Any provider error reached when speaking remains explicit.
  }
}
