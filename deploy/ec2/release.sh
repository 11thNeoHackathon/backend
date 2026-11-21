#!/usr/bin/env bash
set -euo pipefail

incoming=${1:?usage: release.sh <artifact directory> <commit SHA> [Nginx base URL]}
revision=${2:?commit SHA required}
nginx_url=${3:-http://127.0.0.1}
app_dir=${DEPLOY_APP_DIR:-/home/ubuntu}
app_jar="$app_dir/app.jar"
backup_jar="$app_dir/.app.jar.previous"
health_timeout=${DEPLOY_HEALTH_TIMEOUT_SECONDS:-90}
service=hackathon.service

[[ $revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA.' >&2; exit 1; }
[[ $nginx_url =~ ^https?://[a-zA-Z0-9][a-zA-Z0-9.-]*(:[0-9]{1,5})?$ ]] || {
  echo 'Expected a valid EC2_HEALTH_BASE_URL origin without a path or trailing slash.' >&2
  exit 1
}
(cd "$incoming" && sha256sum --check --status app.jar.sha256) || { echo 'Artifact checksum did not match.' >&2; exit 1; }

exec 9>"$app_dir/.hackathon-deploy.lock"
flock -w 60 9
[[ -f $app_jar && ! -L $app_jar ]] || { echo "Expected the existing application file at $app_jar." >&2; exit 1; }
if [[ -L $backup_jar || ( -e $backup_jar && ! -f $backup_jar ) ]]; then
  echo "Unexpected backup path: $backup_jar" >&2
  exit 1
fi

staging=$(mktemp -d "$app_dir/.hackathon-deploy.XXXXXXXX")
cleanup() {
  rm -f -- "$staging/next.jar" "$staging/previous.jar" "$staging/session-response.json"
  rmdir -- "$staging"
}
trap cleanup EXIT

cp -- "$app_jar" "$staging/previous.jar"
cp -- "$incoming/app.jar" "$staging/next.jar"
chmod --reference="$app_jar" "$staging/previous.jar" "$staging/next.jar"
if [[ $(stat -c '%u:%g' "$app_jar") != $(stat -c '%u:%g' "$staging/next.jar") ]]; then
  sudo -n /usr/bin/chown --reference="$app_jar" "$staging/previous.jar" "$staging/next.jar"
fi

http_status_is() {
  local expected=$1 status
  shift
  status=$(curl -q --silent --noproxy '*' --max-time 3 --write-out '%{http_code}' "$@") && [[ $status == "$expected" ]]
}

wait_for_health() {
  local deadline=$((SECONDS + health_timeout))
  local -a nginx_options=(--location --max-redirs 3 --proto-redir '=https' --connect-to '::127.0.0.1:')
  while :; do
    if systemctl is-active --quiet "$service" \
        && http_status_is 200 --output /dev/null http://127.0.0.1:8080/openapi/server1.yaml \
        && http_status_is 200 "${nginx_options[@]}" --output /dev/null "$nginx_url/openapi/server1.yaml" \
        && http_status_is 401 "${nginx_options[@]}" --output "$staging/session-response.json" "$nginx_url/api/v1/participants/me" \
        && grep -Eq '"code"[[:space:]]*:[[:space:]]*"SESSION_REQUIRED"' "$staging/session-response.json"; then
      return 0
    fi
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 2
  done
}

mv -fT -- "$staging/previous.jar" "$backup_jar"
mv -fT -- "$staging/next.jar" "$app_jar"
if sudo -n /usr/bin/systemctl restart "$service" && wait_for_health; then
  echo "Deployed and verified $revision."
  exit 0
fi

echo 'The new JAR did not become healthy; restoring the previous JAR.' >&2
mv -fT -- "$backup_jar" "$app_jar"
if sudo -n /usr/bin/systemctl restart "$service" && wait_for_health; then
  echo 'Previous JAR restored and healthy.' >&2
else
  echo 'Previous JAR restored, but service health did not recover.' >&2
fi
exit 1
