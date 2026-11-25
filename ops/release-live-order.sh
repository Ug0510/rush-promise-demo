#!/usr/bin/env bash
set -euo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.cache/rush-promise-tooling/kubeconfig}"
[[ "$(kubectl config current-context)" == kind-rush-promise-preflight ]]
order="${1:-}"
approval="${2:-}"
if [[ ! "$order" =~ ^[1-9][0-9]{0,15}$ || ! "$approval" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]{2,100}$ ]]; then
    echo "Usage: release-live-order.sh <actual-order-number> <recorded-approval-reference>" >&2
    exit 2
fi
mode="$(kubectl -n dev-data get deployment rush-promise-worker -o json |
    jq -r '.spec.template.spec.containers[].env[] | select(.name=="NOTIFIER") | .value')"
[[ "$mode" == twilio ]]
enrolled="$(kubectl -n dev-data get secret rush-live-demo -o jsonpath='{.data.enrolled-recipient}' | base64 -d)"
[[ "$enrolled" =~ ^\+[1-9][0-9]{7,14}$ ]]
result="$(
    {
        printf '%s\n' "$enrolled"
        cat <<'SQL'
\getenv enrolled RUSH_ENROLLED
WITH grant_order AS (
  INSERT INTO notification_live_grant (run_id, order_id, approval_ref)
  SELECT o.run_id, o.id, :'approval'
    FROM orders o JOIN shop_state s USING (run_id)
   WHERE o.id=:'order'::bigint AND o.phone=:'enrolled'
     AND o.status='CONFIRMED'
     AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.run_id=o.run_id AND m.order_id=o.id
                AND m.kind='CONFIRMED' AND m.status='QUEUED' AND m.recipient=:'enrolled')
  ON CONFLICT (singleton) DO NOTHING
  RETURNING order_id
)
SELECT order_id FROM grant_order;
SQL
    } | kubectl -n dev-data exec -i statefulset/postgres -- sh -ceu '
      IFS= read -r RUSH_ENROLLED
      export RUSH_ENROLLED
      PGPASSWORD="$SHOP_APP_PASSWORD" psql -XwqAt -v ON_ERROR_STOP=1 -v order="$1" -v approval="$2" \
        -h postgres.dev-data.svc.cluster.local -U shop_app -d shop
      ' sh "$order" "$approval"
)"
unset enrolled
if [[ "$result" != "$order" ]]; then
    echo "No matching queued order, or the one live-order release was already used." >&2
    exit 1
fi
printf 'Live release recorded for order #%s. Only its confirmation and pickup-ready notice are eligible.\n' "$order"
