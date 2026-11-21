#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(mktemp -d)
cleanup() {
  rm -f -- "$test_dir/bin/"{sudo,systemctl,curl,stat} "$test_dir/app/"{app.jar,.app.jar.previous,.hackathon-deploy.lock} \
    "$test_dir/incoming/"{app.jar,app.jar.sha256} "$test_dir/calls"
  rmdir -- "$test_dir/"{bin,app,incoming} "$test_dir"
}
trap cleanup EXIT

mkdir "$test_dir/"{bin,app,incoming}
export DEPLOY_APP_DIR="$test_dir/app" DEPLOY_HEALTH_TIMEOUT_SECONDS=0 TEST_CALLS="$test_dir/calls"
REAL_STAT=$(command -v stat)
export REAL_STAT
export PATH="$test_dir/bin:$PATH"
incoming="$test_dir/incoming"
revision=0123456789abcdef0123456789abcdef01234567

cat > "$test_dir/bin/sudo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case $2 in
  /usr/bin/systemctl)
    [[ $* == '-n /usr/bin/systemctl restart hackathon.service' ]]
    printf '%s\n' restart >> "$TEST_CALLS"
    [[ $(cat "$DEPLOY_APP_DIR/app.jar") != restart-fails ]]
    ;;
  /usr/bin/chown)
    [[ $1 == -n && $3 == "--reference=$DEPLOY_APP_DIR/app.jar" && $4 == */previous.jar && $5 == */next.jar ]]
    printf '%s\n' chown >> "$TEST_CALLS"
    ;;
  *) exit 1 ;;
esac
STUB
cat > "$test_dir/bin/stat" <<'STUB'
#!/usr/bin/env bash
if [[ ${TEST_JAR_OWNER:-} == different && ${!#} == "$DEPLOY_APP_DIR/app.jar" ]]; then
  owner=$("$REAL_STAT" "$@")
  printf '%d:%s\n' "$(( ${owner%:*} + 1 ))" "${owner#*:}"
else
  "$REAL_STAT" "$@"
fi
STUB
cat > "$test_dir/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
[[ $* == 'is-active --quiet hackathon.service' ]]
STUB
cat > "$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
url=${!#}
jar=$(cat "$DEPLOY_APP_DIR/app.jar")
printf '%s\n' "$url" >> "$TEST_CALLS"
if [[ $url != http://127.0.0.1:8080/* ]]; then
  if [[ " $* " != *' --location '* || " $* " != *' --proto-redir =https '* || " $* " != *' --connect-to ::127.0.0.1: '* ]]; then
    printf '%s' 301
    exit 0
  fi
fi
if [[ $url == */api/v1/participants/me ]]; then
  while [[ $1 != --output ]]; do shift; done
  if [[ $jar == proxy-auth ]]; then
    printf '%s' 'Unauthorized' > "$2"
  else
    printf '%s' '{"error":{"code":"SESSION_REQUIRED"}}' > "$2"
  fi
  printf '%s' 401
elif [[ $url == http://127.0.0.1/openapi/server1.yaml && $jar == unhealthy ]]; then
  printf '%s' 502
else
  printf '%s' 200
fi
STUB
chmod +x "$test_dir/bin/"{sudo,systemctl,curl,stat}

stage_jar() {
  printf '%s\n' "$1" > "$incoming/app.jar"
  (cd "$incoming" && sha256sum app.jar > app.jar.sha256)
}

printf '%s\n' old > "$DEPLOY_APP_DIR/app.jar"
stage_jar healthy
bash "$script_dir/release.sh" "$incoming" "$revision"
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == healthy ]]
[[ $(cat "$DEPLOY_APP_DIR/.app.jar.previous") == old ]]
[[ $(cat "$TEST_CALLS") == $'restart\nhttp://127.0.0.1:8080/openapi/server1.yaml\nhttp://127.0.0.1/openapi/server1.yaml\nhttp://127.0.0.1/api/v1/participants/me' ]]
echo 'Verified JAR replacement and Spring Boot, Nginx, and session endpoint checks.'

: > "$TEST_CALLS"
stage_jar tls-healthy
TEST_JAR_OWNER=different bash "$script_dir/release.sh" "$incoming" "$revision" https://api.example.com
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == tls-healthy ]]
[[ $(sed -n '1p' "$TEST_CALLS") == chown ]]
grep -Fxq 'https://api.example.com/api/v1/participants/me' "$TEST_CALLS"
echo 'Verified the ownership preservation command and HTTPS probe arguments.'

: > "$TEST_CALLS"
stage_jar unhealthy
if bash "$script_dir/release.sh" "$incoming" "$revision"; then
  echo 'Expected an unhealthy release to fail.' >&2
  exit 1
fi
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == tls-healthy ]]
[[ $(grep -cx restart "$TEST_CALLS") == 2 ]]
echo 'Verified rollback when the Nginx check fails.'

for bad_jar in proxy-auth restart-fails; do
  : > "$TEST_CALLS"
  stage_jar "$bad_jar"
  if bash "$script_dir/release.sh" "$incoming" "$revision"; then
    echo "Expected $bad_jar to fail." >&2
    exit 1
  fi
  [[ $(cat "$DEPLOY_APP_DIR/app.jar") == tls-healthy ]]
  [[ $(grep -cx restart "$TEST_CALLS") == 2 ]]
done
echo 'Verified rollback after a proxy-generated 401 or a failed service restart.'

: > "$TEST_CALLS"
stage_jar healthy
printf '%s\n' corrupted > "$incoming/app.jar"
if bash "$script_dir/release.sh" "$incoming" "$revision"; then
  echo 'Expected an invalid checksum to fail.' >&2
  exit 1
fi
[[ $(cat "$DEPLOY_APP_DIR/app.jar") == tls-healthy && ! -s $TEST_CALLS ]]
echo 'Verified that a mismatched checksum leaves the current JAR running.'
