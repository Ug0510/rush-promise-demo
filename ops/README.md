# Delivery and event operations

The web process commits orders and outbox jobs together. The worker leases due jobs with PostgreSQL row locking and persists an attempt before it contacts Twilio. Status callbacks verify the Twilio signature, account and opaque attempt ID. The dashboard can distinguish provider acceptance, reported delivery and simulation. An uncertain transport result waits for callback evidence and eventually needs review. Only confirmed rate limiting is retried automatically.

## Tests

With Java 21, Maven, PostgreSQL 16 command-line tools and Python on the path, run `ops/test-delivery.sh`. It starts a disposable local PostgreSQL instance and uses a restricted database role. The HTTP tests use a local fake Twilio endpoint and synthetic numbers.

## Deploy to the team's isolated kind cluster

Run `ops/deploy-kind.sh` on the dedicated Codespace after selecting Java 21 and its prepared kubeconfig. The preflight PostgreSQL, provider, NudgeBee and monitoring resources are the prerequisites. Kubernetes secrets are supplied privately by the operator. The script builds the jar and loads the commit-tagged image into kind. Console delivery is the default and records `SIMULATED`.

The web and worker have separate deployments and private management ports. Forward only the gateway Service to the approved public HTTPS port. Its allowlist is the caller page, its two assets, two voice POST endpoints and the signed Twilio status POST. Staff uses a separate authenticated private web forward, with the relevant role credential. PostgreSQL, cluster controls and metrics have no public forward.

## One live order

Set `RUSH_PUBLIC_BASE_URL` to the approved gateway origin and `RUSH_DEPLOY_NOTIFIER=twilio` when deploying the live run. Before the operator releases an actual order, Twilio has no eligible rows. Confirm the user placed the order with the enrolled Sandbox handset and run `ops/release-live-order.sh <actual-order-number> <approval-reference>`. The order, active run and exact enrolled recipient must match. This enables the confirmation and pickup-ready notices for that one order. An old grant persists across database demo resets to keep it from authorizing a second order.

Verify the CONFIRMED attempt and actual handset receipt before the outage. Run `ops/worker-revision.sh break`, then mark the same order packed through staff. The private web scrape continues to report the READY backlog while the worker scrape is down. The real `RushPromiseReadyNoticeBacklog` rule fires after 15 seconds and Alertmanager forwards it to the NudgeBee Cloud agent. Record the alert and the original READY message ID. After operator approval, run `ops/worker-revision.sh restore <approval-reference>`. This manual operation restores the earlier immutable config to the isolated worker deployment. Capture the original READY message’s new status and confirm the same notice once on the enrolled handset. Treat provider status and handset readback as separate evidence.

Use console mode for simulated-order recordings. Avoid recording or publishing contacts, raw callback bodies, tokens or unredacted administrative screens.
