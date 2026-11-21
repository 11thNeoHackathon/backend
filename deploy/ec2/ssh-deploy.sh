#!/usr/bin/env bash
set -euo pipefail

artifact_dir=${1:?usage: ssh-deploy.sh <artifact directory> <commit SHA>}
revision=${2:?commit SHA required}
: "${EC2_HOST:?EC2_HOST is required}"
: "${EC2_USER:?EC2_USER is required}"
: "${EC2_SSH_PRIVATE_KEY:?EC2_SSH_PRIVATE_KEY is required}"
: "${EC2_KNOWN_HOSTS:?EC2_KNOWN_HOSTS is required}"
[[ $EC2_HOST =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ && $EC2_USER =~ ^[a-z_][a-z0-9_-]*$ && $revision =~ ^[0-9a-f]{40}$ ]] || {
  echo 'Invalid EC2 host, user or commit SHA.' >&2
  exit 1
}
(cd "$artifact_dir" && sha256sum --check --status app.jar.sha256)

umask 077
ssh_dir=$(mktemp -d)
target="$EC2_USER@$EC2_HOST"
options=(-i "$ssh_dir/key" -o "UserKnownHostsFile=$ssh_dir/known_hosts" -o StrictHostKeyChecking=yes
         -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=10)
remote_dir=''
cleanup() {
  if [[ $remote_dir =~ ^/tmp/hackathon-deploy\.[a-zA-Z0-9]{8}$ ]]; then
    ssh -p 22 "${options[@]}" "$target" \
      "rm -f -- '$remote_dir/app.jar' '$remote_dir/app.jar.sha256' '$remote_dir/release.sh'; rmdir -- '$remote_dir'" \
      >/dev/null 2>&1 || true
  fi
  rm -f -- "$ssh_dir/key" "$ssh_dir/known_hosts"
  rmdir -- "$ssh_dir"
}
trap cleanup EXIT

printf '%s\n' "$EC2_SSH_PRIVATE_KEY" | tr -d '\r' > "$ssh_dir/key"
printf '%s\n' "$EC2_KNOWN_HOSTS" | tr -d '\r' > "$ssh_dir/known_hosts"
unset EC2_SSH_PRIVATE_KEY EC2_KNOWN_HOSTS

remote_dir=$(ssh -p 22 "${options[@]}" "$target" 'mktemp -d /tmp/hackathon-deploy.XXXXXXXX')
[[ $remote_dir =~ ^/tmp/hackathon-deploy\.[a-zA-Z0-9]{8}$ ]] || { echo 'Invalid EC2 staging directory.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
scp -P 22 "${options[@]}" "$artifact_dir/app.jar" "$artifact_dir/app.jar.sha256" "$script_dir/release.sh" "$target:$remote_dir/"
ssh -p 22 "${options[@]}" "$target" "bash '$remote_dir/release.sh' '$remote_dir' '$revision'"
