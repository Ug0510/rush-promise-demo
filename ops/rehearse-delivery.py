#!/usr/bin/env python3
"""Exercise the real READY backlog with console transport and discard only its fixture."""
import base64
import datetime
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.request
import uuid

BASE = "http://127.0.0.1:18082"
NAMESPACE = ("kubectl", "-n", "dev-data")
RUNTIME = pathlib.Path.home() / ".cache/rush-promise-tooling/event-runtime"
EVIDENCE = RUNTIME / "d04-console-proof.json"

def cli(*arguments):
    return subprocess.check_output(arguments, text=True)

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")

def request(path, tokens, body=None, role="staff", extra=None):
    header = "X-Booking-Token" if role == "booking" else "X-Staff-Token"
    context = {"Content-Type": "application/json", header: tokens[role], **(extra or {})}
    request = urllib.request.Request(BASE + path, None if body is None else json.dumps(body).encode(), context)
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.load(response)

def alerts(service, port, endpoint):
    return json.loads(cli("kubectl", "-n", "telemetry-preflight", "exec", "deploy/" + service,
        "--", "wget", "-qO-", f"http://127.0.0.1:{port}{endpoint}"))

def transition(rows, order, kind, status):
    for _ in range(25):
        matches = [row for row in rows(order) if row["kind"] == kind]
        if len(matches) == 1 and matches[0]["status"] == status:
            return matches[0]
        time.sleep(1)
    raise RuntimeError(f"{kind} never reached {status}")

def start():
    assert not request("/api/orders", tokens), "Stop: isolated rehearsal requires no preexisting order."
    session = "d04-rehearsal-" + uuid.uuid4().hex
    quote = request("/api/quotes", tokens, {
        "sessionId": session, "customerName": "Rehearsal", "phone": "+12025550100",
        "sku": "kaju-katli", "grams": 500, "pickupTime": "18:30",
    }, "booking")["quote"]
    turn = "rehearsal-yes"
    saved = request(f"/api/quotes/{quote['id']}/accept", tokens, {
        "sessionId": session, "turnId": turn,
    }, "booking", {"Idempotency-Key": uuid.uuid4().hex})["order"]
    assert saved["status"] == "CONFIRMED"
    order = saved["id"]
    transition(messages, order, "CONFIRMED", "SIMULATED")
    print(now(), "CONFIRMED simulated", flush=True)

    subprocess.run(["ops/worker-revision.sh", "break"], check=True)
    packed = request(f"/api/orders/{order}/pack", tokens, {})["order"]
    assert packed["status"] == "PACKED"
    ready = transition(messages, order, "READY", "QUEUED")
    proof = {
        "order": order, "readyId": ready["id"], "queuedAt": now(),
        "readyStatus": "QUEUED", "transport": "SIMULATED",
    }
    record(proof)
    print(proof["queuedAt"], "READY queued", ready["id"], flush=True)
    for _ in range(45):
        prom = alerts("prometheus", 9090, "/api/v1/alerts")["data"]["alerts"]
        am = alerts("alertmanager", 9093, "/api/v2/alerts")
        if any(row["labels"]["alertname"] == "RushPromiseReadyNoticeBacklog" and row["state"] == "firing" for row in prom)                 and any(row["labels"]["alertname"] == "RushPromiseReadyNoticeBacklog" and row["status"]["state"] == "active" for row in am):
            proof["firingAt"] = now()
            record(proof)
            print(proof["firingAt"], "Prometheus Firing; Alertmanager Active", flush=True)
            return
        time.sleep(2)
    raise RuntimeError("The real READY queue alert did not fire")

def restore(approval):
    proof = json.loads(EVIDENCE.read_text())
    subprocess.run(["ops/worker-revision.sh", "restore", approval], check=True)
    ready = transition(messages, proof["order"], "READY", "SIMULATED")
    assert ready["id"] == proof["readyId"]
    for _ in range(25):
        prom = alerts("prometheus", 9090, "/api/v1/alerts")["data"]["alerts"]
        if not any(row["labels"]["alertname"] == "RushPromiseReadyNoticeBacklog" for row in prom):
            proof["restoredAt"] = now()
            proof["readyStatus"] = "SIMULATED"
            record(proof)
            print(proof["restoredAt"], "the same READY was simulated; alert resolved", flush=True)
            return
        time.sleep(2)
    raise RuntimeError("Prometheus still reports the READY queue alert")

def clear():
    proof = json.loads(EVIDENCE.read_text())
    current = request("/api/orders", tokens)
    assert len(current) == 1 and current[0]["id"] == proof["order"]
    assert current[0]["acceptedTurnId"] == "rehearsal-yes"
    assert current[0]["phone"] == "+12025550100"
    assert transition(messages, proof["order"], "READY", "SIMULATED")["id"] == proof["readyId"]
    request("/ops/demo/reset", tokens, {"preset": "default"}, "operator")
    assert not request("/api/orders", tokens)
    print(now(), "isolated console fixture reset; no external messages", flush=True)

def record(proof):
    RUNTIME.mkdir(0o700, exist_ok=True)
    EVIDENCE.write_text(json.dumps(proof, indent=2) + "\n")
    EVIDENCE.chmod(0o600)

def messages(order):
    return request(f"/api/orders/{order}/messages", tokens)

if __name__ == "__main__":
    os.umask(0o077)
    os.environ.setdefault("KUBECONFIG", str(pathlib.Path.home() / ".cache/rush-promise-tooling/kubeconfig"))
    assert cli("kubectl", "config", "current-context").strip() == "kind-rush-promise-preflight"
    deployment = json.loads(cli(*NAMESPACE, "get", "deploy", "rush-promise-worker", "-o", "json"))
    env = deployment["spec"]["template"]["spec"]["containers"][0]["env"]
    assert any(row["name"] == "NOTIFIER" and row.get("value") == "console" for row in env)
    secret = json.loads(cli(*NAMESPACE, "get", "secret", "rush-live-demo", "-o", "json"))["data"]
    tokens = {name: base64.b64decode(secret[f"{name}-token"]).decode() for name in ("staff", "operator", "booking")}
    mode = sys.argv[1:]
    if mode == ["start"]:
        start()
    elif mode == ["clear"]:
        clear()
    elif len(mode) == 2 and mode[0] == "restore":
        restore(mode[1])
    else:
        raise SystemExit("Usage: rehearse-delivery.py start | restore <approval-reference> | clear")
