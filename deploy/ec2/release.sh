#!/usr/bin/env bash
set -euo pipefail

incoming=${1:?usage: release.sh <artifact directory> <commit SHA>}
revision=${2:?commit SHA required}
app_dir=${DEPLOY_APP_DIR:-/home/ubuntu}
app_jar="$app_dir/app.jar"
backup_jar="$app_dir/.app.jar.previous"
attempts=${DEPLOY_HEALTH_ATTEMPTS:-45}
service=hackathon.service

[[ $revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA.' >&2; exit 1; }
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
  rm -f -- "$staging/next.jar" "$staging/previous.jar"
  rmdir -- "$staging"
}
trap cleanup EXIT

cp -- "$app_jar" "$staging/previous.jar"
cp -- "$incoming/app.jar" "$staging/next.jar"
chmod --reference="$app_jar" "$staging/previous.jar" "$staging/next.jar"
if [[ $(stat -c '%u:%g' "$app_jar") != $(stat -c '%u:%g' "$staging/next.jar") ]]; then
  sudo -n /usr/bin/chown --reference="$app_jar" "$staging/previous.jar" "$staging/next.jar"
fi

wait_for_health() {
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if systemctl is-active --quiet "$service" \
        && [[ $(curl --silent --max-time 3 --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8080/openapi/server1.yaml) == 200 ]] \
        && [[ $(curl --silent --max-time 3 --output /dev/null --write-out '%{http_code}' http://127.0.0.1/openapi/server1.yaml) == 200 ]]; then
      return 0
    fi
    if (( attempt < attempts )); then sleep 2; fi
  done
  return 1
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
