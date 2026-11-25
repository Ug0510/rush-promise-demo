"use strict";

function bindOwnerPhoneSettings(panel, request) {
  if (!panel) return { open() {}, close() {} };
  panel.innerHTML = `
    <h3>Phone and notifications</h3>
    <div class="phone-setting">
      <div><h4>Inventory by phone</h4><p>Use a one-time code to verify you’re the shop owner on a call.</p></div>
      <button type="button" class="button primary" data-owner-call-code>Generate one-time phone code</button>
      <p class="phone-result" data-call-result role="status" hidden></p>
    </div>
    <div class="phone-setting">
      <div><h4>WhatsApp for new orders</h4>
        <p data-wa-state role="status">Checking link status…</p>
        <p data-wa-explanation>Send a one-time code from your WhatsApp account to opt in.</p></div>
      <div class="phone-buttons"><button type="button" class="button primary" data-wa-link disabled>Link WhatsApp</button>
        <button type="button" class="button quiet" data-wa-unlink hidden>Unlink</button></div>
      <p class="phone-result" data-wa-result hidden></p>
      <a class="button quiet" data-wa-open rel="noopener noreferrer" target="_blank" hidden>Open WhatsApp</a>
    </div>
    <p class="phone-result" data-wa-delivery hidden></p>
    <p class="form-error" data-phone-error role="alert" hidden></p>`;
  const find = name => panel.querySelector(`[data-${name}]`);
  const generate = find("owner-call-code");
  const link = find("wa-link");
  const unlink = find("wa-unlink");
  const whatsapp = find("wa-open");
  const labels = {
    LINKED: "Linked",
    WINDOW_EXPIRED: "Linked · messaging window closed",
    AWAITING_MESSAGE: "Waiting for your message",
    NOT_LINKED: "Not linked",
    UNAVAILABLE: "Not available"
  };
  const deliveries = {
    QUEUED: "queued", SENDING: "sending", SUBMITTED: "sent to WhatsApp",
    DELIVERED: "delivered", FAILED: "failed", UNKNOWN: "delivery unverified", SUPPRESSED: "suppressed"
  };
  let version = 0;
  let timer;
  let open = false;
  let waiting = false;
  let refreshing = false;
  let state = "UNAVAILABLE";

  function show(node, value) {
    node.textContent = value;
    node.hidden = !value;
  }

  function time(value) {
    return new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", hour: "numeric", minute: "2-digit" })
      .format(new Date(value)) + " IST";
  }

  async function refresh() {
    if (!open || refreshing || document.hidden) return;
    const current = version;
    refreshing = true;
    try {
      const result = await request("/api/owner/whatsapp");
      if (current !== version || !open) return;
      state = result.state;
      const linked = state === "LINKED" || state === "WINDOW_EXPIRED";
      const description = linked ? (state === "LINKED" ? "Send PIN on WhatsApp for a fresh one-time phone code."
        : "Send PIN from your linked WhatsApp account to reopen the messaging window.")
        : state === "UNAVAILABLE" ? "WhatsApp linking is unavailable here."
        : "Join the WhatsApp Sandbox first, then send the link code from your account.";
      show(find("wa-state"), labels[state] || "Status unavailable");
      show(find("wa-explanation"), description);
      unlink.hidden = !linked && state !== "AWAITING_MESSAGE";
      link.hidden = linked;
      link.disabled = state === "UNAVAILABLE" || waiting;
      if (linked || state === "NOT_LINKED" || state === "UNAVAILABLE") {
        whatsapp.hidden = true;
        whatsapp.removeAttribute("href");
        show(find("wa-result"), "");
      }
      const latest = deliveries[result.latestDelivery];
      show(find("wa-delivery"), latest ? `Order #${result.latestOrder} WhatsApp: ${latest}.` : "");
    } catch {
      if (current === version) show(find("wa-state"), "Could not check WhatsApp status.");
    } finally {
      refreshing = false;
    }
  }

  function perform(button, action) {
    button.addEventListener("click", async () => {
      if (button.disabled || waiting || !open) return;
      const current = version;
      waiting = true;
      button.disabled = true;
      show(find("phone-error"), "");
      try {
        const output = await action.request();
        if (current === version && open) action.received(output);
      } catch (error) {
        if (current === version) show(find("phone-error"), error.status === 429
          ? "Wait a few minutes before requesting another code."
          : "This action is temporarily unavailable. Try again.");
      } finally {
        waiting = false;
        button.disabled = button === link && state === "UNAVAILABLE";
        if (current === version) void refresh();
      }
    });
  }

  perform(generate, {
    request: () => request("/api/owner/phone/access", "POST", {}),
    received: result => show(find("call-result"), `Owner code ${result.accessCode} · expires at ${time(result.expiresAt)}.`)
  });
  perform(link, {
    request: () => request("/api/owner/whatsapp/link", "POST", {}),
    received: result => {
      if (!/^\+[1-9][0-9]{7,14}$/.test(result.sandboxNumber)) return;
      show(find("wa-result"), `Send ${result.command} before ${time(result.expiresAt)}.`);
      whatsapp.href = `https://wa.me/${result.sandboxNumber.slice(1)}?text=${encodeURIComponent(result.command)}`;
      whatsapp.hidden = false;
    }
  });
  perform(unlink, {
    request: () => request("/api/owner/whatsapp/unlink", "POST", {}),
    received: () => {
      whatsapp.hidden = true;
      whatsapp.removeAttribute("href");
      show(find("wa-result"), "");
    }
  });

  return {
    open() {
      if (open) return;
      open = true;
      panel.hidden = false;
      void refresh();
      timer = setInterval(() => void refresh(), 5000);
    },
    close() {
      version++;
      open = false;
      clearInterval(timer);
      panel.hidden = true;
      whatsapp.hidden = true;
      whatsapp.removeAttribute("href");
      show(find("call-result"), "");
      show(find("wa-result"), "");
      show(find("phone-error"), "");
    }
  };
}
