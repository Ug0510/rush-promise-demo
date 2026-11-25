"use strict";

const $ = id => document.getElementById(id);
const form = $("caller-form");
const speak = $("speak");
const replay = $("replay");
const restart = $("restart");
const retry = $("retry");
const player = $("readback");
const wave = document.querySelector(".voice-wave");
const amount = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0 });
let session, sequence, quote, playedQuote, speakingQuote, recorder, pending, audioUrl, sealed, working;
let microphoneTrack;
let timer, receiptTimer;
let checkingReceipt = false;
let receiptChecks = 0;

function beginSession() {
  session = globalThis.crypto?.randomUUID?.();
  sequence = 0;
  quote = null;
  playedQuote = null;
  pending = null;
  checkingReceipt = false;
  receiptChecks = 0;
  clearTimeout(receiptTimer);
  sealed = false;
  working = false;
  form.hidden = false;
  restart.hidden = true;
  $("heard").hidden = true;
  $("details").hidden = true;
  $("reply").hidden = true;
  retry.hidden = true;
  retry.textContent = "Retry this turn";
  speak.hidden = false;
  speak.disabled = false;
  speak.textContent = "Start speaking";
  clearAudio();
  showError("");
  showPhase("idle", "Microphone off");
}

function showError(text) {
  $("error").textContent = text;
  $("error").hidden = text.length === 0;
}

function showPhase(phase, text) {
  wave.dataset.phase = phase;
  $("mic-state").textContent = text;
}

function clearAudio() {
  player.pause();
  if (audioUrl) URL.revokeObjectURL(audioUrl);
  audioUrl = null;
  speakingQuote = null;
  player.removeAttribute("src");
  replay.hidden = true;
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

function formatTime(value) {
  const [hour, minute] = value.split(":").map(Number);
  return `${hour % 12 || 12}:${String(minute).padStart(2, "0")} ${hour >= 12 ? "PM" : "AM"}`;
}

function loadAudio(encoded, promptQuote = null) {
  clearAudio();
  const wav = Uint8Array.from(atob(encoded), character => character.charCodeAt(0));
  audioUrl = URL.createObjectURL(new Blob([wav], { type: "audio/wav" }));
  speakingQuote = promptQuote;
  player.src = audioUrl;
  replay.hidden = false;
  void playReply();
}

function render(result) {
  const turn = result.turn;
  const facts = turn.order || turn.quote;
  quote = turn.quote?.id || null;
  playedQuote = null;
  sealed = Boolean(turn.order) || turn.state === "ENDED";
  $("heard").hidden = !turn.transcript;
  $("transcript").textContent = turn.transcript || "";
  $("details").hidden = !facts;
  if (facts) {
    $("item").textContent = `${facts.grams >= 1000 ? facts.grams / 1000 + " kg" : facts.grams + " g"} ${facts.itemName}`;
    $("pickup").textContent = formatTime(facts.pickupTime);
    $("amount").textContent = amount.format(facts.pricePaise / 100);
  }
  const reply = $("reply");
  reply.hidden = !turn.replyText;
  reply.lang = turn.language || "hi-IN";
  reply.textContent = turn.replyText || "";
  restart.hidden = !sealed;
  speak.hidden = sealed;
  speak.textContent = quote && !result.audioWavBase64 ? "Say ‘repeat’ or change your order"
    : quote ? "Confirm by voice" : "Speak again";
  showPhase("idle", quote ? "Read-back ready" : "Microphone off");
  clearAudio();
  if (result.audioWavBase64) loadAudio(result.audioWavBase64, result.audioFallback ? null : quote);
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
  showError("");
  if (!receiptOnly) showPhase("processing", "Transcribing your order…");
  try {
    const response = await fetch("/api/voice/browser/turn", {
      method: "POST",
      body: data,
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
      speak.textContent = quote ? "Confirm by voice" : "Speak again";
      void sendTurn();
    }, { once: true });
    recorder.start();
    speak.textContent = "Done speaking";
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
    speak.disabled = false;
  }
}

form.addEventListener("submit", event => {
  event.preventDefault();
  if (sealed || working || pending) return;
  if (recorder?.state === "recording") {
    speak.disabled = true;
    recorder.stop();
  } else {
    void recordTurn();
  }
});
replay.addEventListener("click", () => void playReply());
retry.addEventListener("click", () => void sendTurn());
restart.addEventListener("click", beginSession);
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
