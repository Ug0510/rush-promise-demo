#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export KUBECONFIG="${KUBECONFIG:-$HOME/.cache/rush-promise-tooling/kubeconfig}"
[[ "$(kubectl config current-context)" == kind-rush-promise-preflight ]]
base="${RUSH_PUBLIC_BASE_URL:-}"
notifier="${RUSH_DEPLOY_NOTIFIER:-console}"
[[ "$notifier" == console || "$notifier" == twilio ]]
[[ -z "$base" || "$base" =~ ^https://[a-z0-9-]+\.app\.github\.dev$ ]]
if [[ "$notifier" == twilio && -z "$base" ]]; then
    echo "Twilio needs the verified public gateway HTTPS origin." >&2
    exit 1
fi
kubectl -n dev-data get secret shop-db-auth shop-provider-auth rush-live-demo -o name
mvn -B -ntp -DskipTests package
image="rush-promise:$(git rev-parse --short HEAD)"
docker build -t "$image" .
kind load docker-image "$image" --name rush-promise-preflight
sed -e "s|image: rush-promise:dev|image: $image|" \
    -e "s|RUSH_PUBLIC_BASE_URL: \"\"|RUSH_PUBLIC_BASE_URL: \"$base\"|" \
    -e "s|name: NOTIFIER, value: console|name: NOTIFIER, value: $notifier|" \
    ops/k8s/runtime.yaml | kubectl apply -f -
kubectl apply -f ops/k8s/gateway.yaml
kubectl -n dev-data rollout status deployment/rush-promise-web --timeout=180s
kubectl -n dev-data rollout status deployment/rush-promise-worker --timeout=150s
kubectl -n dev-data rollout status deployment/rush-promise-gateway --timeout=150s
kubectl apply -f ops/k8s/monitoring.yaml
kubectl -n telemetry-preflight rollout restart deployment/prometheus deployment/alertmanager
kubectl -n telemetry-preflight rollout status deployment/prometheus --timeout=90s
kubectl -n telemetry-preflight rollout status deployment/alertmanager --timeout=90s
kubectl -n telemetry-preflight exec deploy/prometheus -- promtool check config /etc/preflight/prometheus.yml
kubectl -n telemetry-preflight exec deploy/alertmanager -- amtool check-config /etc/preflight/alertmanager.yml
