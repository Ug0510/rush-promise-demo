#!/usr/bin/env bash
set -euo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.cache/rush-promise-tooling/kubeconfig}"
[[ "$(kubectl config current-context)" == kind-rush-promise-preflight ]]
namespace=dev-data
deployment=rush-promise-worker
ready=rush-worker-config-v1
blocked=rush-worker-config-v2-blocked
template_key=rush-promise.io/worker-config
current="$(kubectl -n "$namespace" get deploy "$deployment" -o json | jq -r --arg k "$template_key" '.spec.template.metadata.annotations[$k]')"
case "${1:-}" in
    break)
        [[ "$current" == "$ready" ]]
        revision="$blocked"
        ;;
    restore)
        [[ "$current" == "$blocked" ]]
        approval="${2:-}"
        [[ "$approval" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]{2,100}$ ]] || {
            echo "Usage: worker-revision.sh restore <recorded-operator-approval-reference>" >&2
            exit 2
        }
        revision="$ready"
        ;;
    *)
        echo "Usage: worker-revision.sh break | restore <recorded-operator-approval-reference>" >&2
        exit 2
        ;;
esac
patch="$(jq -cn --arg cfg "$revision" --arg key "$template_key" --arg approval "${approval:-}" '
    {spec:{template:{
      metadata:{annotations:{($key):$cfg}},
      spec:{containers:[{name:"worker",env:[{name:"RUSH_WORKER_BOOT",valueFrom:{configMapKeyRef:{name:$cfg,key:"boot"}}}]}]}
    }}} | if $approval == "" then . else .metadata.annotations["rush-promise.io/restore-approval"] = $approval end')"
kubectl -n "$namespace" patch deploy "$deployment" --type=strategic -p "$patch"
if [[ "$revision" == "$ready" ]]; then
    kubectl -n "$namespace" rollout status "deployment/$deployment" --timeout=120s
    printf 'Worker Ready on %s. Operator approval reference %s.\n' "$ready" "$approval"
    exit 0
fi
for ((i=0; i<60; i++)); do
    if kubectl -n "$namespace" get pods -l "app=$deployment" -o json | jq -e --arg key "$template_key" --arg cfg "$blocked" '
      all(.items[]; .metadata.annotations[$key] == $cfg and
          all(.status.conditions[]?; .type != "Ready" or .status != "True"))' >/dev/null; then
        printf 'No healthy or previous-revision worker pod remains. Config %s.\n' "$blocked"
        exit 0
    fi
    sleep 2
done
echo "Previous or Ready worker still present after the bounded wait." >&2
exit 1
