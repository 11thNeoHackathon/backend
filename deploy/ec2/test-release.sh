#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(mktemp -d)
cleanup() {
  rm -f -- "$test_dir/bin/"{sudo,systemctl,curl} "$test_dir/app/"{app.jar,.app.jar.previous,.hackathon-deploy.lock} \
    "$test_dir/incoming/"{app.jar,app.jar.sha256} "$test_dir/calls"
  rmdir -- "$test_dir/"{bin,app,incoming} "$test_dir"
}
trap cleanup EXIT

mkdir "$test_dir/"{bin,app,incoming}
export DEPLOY_APP_DIR="$test_dir/app" DEPLOY_HEALTH_ATTEMPTS=1 TEST_CALLS="$test_dir/calls"
export PATH="$test_dir/bin:$PATH"
incoming="$test_dir/incoming"
revision=0123456789abcdef0123456789abcdef01234567

cat > "$test_dir/bin/sudo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ $* == '-n /usr/bin/systemctl restart hackathon.service' ]]
printf '%s\n' restart >> "$TEST_CALLS"
STUB
cat > "$test_dir/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
[[ $* == 'is-active --quiet hackathon.service' ]]
STUB
cat > "$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
url=${!#}
printf '%s\n' "$url" >> "$TEST_CALLS"
if [[ $url == http://127.0.0.1/openapi/server1.yaml && $(cat "$DEPLOY_APP_DIR/app.jar") == unhealthy ]]; then
  printf '%s' 502
else
  printf '%s' 200
fi
STUB
chmod +x "$test_dir/bin/"{sudo,systemctl,curl}

stage_jar() {
  printf '%s\n' "$1" > "$incoming/app.jar"
  (cd "$incoming" && sha256sum app.jar > app.jar.sha256)
}

printf '%s\n' old > "$DEPLOY_APP_DIR/app.jar"
stage_jar healthy
bash "$script_dir/release.sh" "$incoming" "$revision"
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == healthy ]]
[[ $(cat "$DEPLOY_APP_DIR/.app.jar.previous") == old ]]
[[ $(cat "$TEST_CALLS") == $'restart\nhttp://127.0.0.1:8080/openapi/server1.yaml\nhttp://127.0.0.1/openapi/server1.yaml' ]]
echo 'Verified JAR replacement and both HTTP checks.'

: > "$TEST_CALLS"
stage_jar unhealthy
if bash "$script_dir/release.sh" "$incoming" "$revision"; then
  echo 'Expected an unhealthy release to fail.' >&2
  exit 1
fi
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == healthy ]]
[[ $(grep -cx restart "$TEST_CALLS") == 2 ]]
echo 'Verified rollback when the Nginx check fails.'

: > "$TEST_CALLS"
stage_jar healthy
printf '%s\n' corrupted > "$incoming/app.jar"
if bash "$script_dir/release.sh" "$incoming" "$revision"; then
  echo 'Expected an invalid checksum to fail.' >&2
  exit 1
fi
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == healthy && ! -s $TEST_CALLS ]]
echo 'Verified that a mismatched checksum leaves the current JAR running.'
