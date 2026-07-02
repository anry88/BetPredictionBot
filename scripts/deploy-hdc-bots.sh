#!/usr/bin/env bash
set -euo pipefail

REMOTE_SSH_HOST="${REMOTE_SSH_HOST:-hdc}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-hdc/betprediction-bot}"
COMPOSE_FILE="D:\\HomeDataCenter\\compose\\docker-compose.betprediction.yml"
COMPOSE_DIR="D:\\HomeDataCenter\\compose"
SERVICES=(betprediction-test betprediction-prod)
CONTAINERS=(hdc-betprediction-test hdc-betprediction-prod)

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Required command is not available: $1" >&2
        exit 1
    fi
}

require_command docker
require_command git
require_command ssh

if [[ -n "$(git status --porcelain)" ]]; then
    echo "The worktree is dirty. Commit the deployment before updating production." >&2
    exit 1
fi

git_sha="$(git rev-parse --short=12 HEAD)"
versioned_image="${IMAGE_REPOSITORY}:${git_sha}"
latest_image="${IMAGE_REPOSITORY}:latest"

echo "Running tests..."
./gradlew test

echo "Checking local Docker and HDC connectivity..."
docker version >/dev/null
ssh "$REMOTE_SSH_HOST" "docker version --format \"{{.Server.Version}}\""

echo "Building ${versioned_image} for linux/amd64..."
docker build \
    --platform linux/amd64 \
    --label "org.opencontainers.image.revision=${git_sha}" \
    --tag "$versioned_image" \
    .
docker tag "$versioned_image" "$latest_image"

platform="$(docker image inspect "$versioned_image" --format '{{.Architecture}}/{{.Os}}')"
if [[ "$platform" != "amd64/linux" ]]; then
    echo "Unexpected image platform: $platform" >&2
    exit 1
fi

echo "Loading the image directly into Docker on ${REMOTE_SSH_HOST}..."
docker save "$versioned_image" "$latest_image" | ssh "$REMOTE_SSH_HOST" "docker load"

remote_versioned="$(
    ssh "$REMOTE_SSH_HOST" \
        "docker image inspect ${versioned_image} --format \"{{.Id}} {{.Architecture}}/{{.Os}}\""
)"
remote_latest="$(
    ssh "$REMOTE_SSH_HOST" \
        "docker image inspect ${latest_image} --format \"{{.Id}} {{.Architecture}}/{{.Os}}\""
)"
remote_versioned="${remote_versioned//$'\r'/}"
remote_latest="${remote_latest//$'\r'/}"
if [[ "$remote_versioned" != "$remote_latest" || "$remote_latest" != *" amd64/linux" ]]; then
    echo "Unexpected images loaded on HDC:" >&2
    echo "  ${versioned_image}: ${remote_versioned}" >&2
    echo "  ${latest_image}: ${remote_latest}" >&2
    exit 1
fi

echo "Recreating only the test and production bot containers..."
ssh "$REMOTE_SSH_HOST" \
    "cmd /c \"cd /d ${COMPOSE_DIR} && docker compose -f ${COMPOSE_FILE} up -d --no-deps --force-recreate --pull never ${SERVICES[*]}\""

echo "Waiting for both healthchecks..."
ssh "$REMOTE_SSH_HOST" "powershell -NoProfile -Command \"\
\$ErrorActionPreference='Stop'; \
\$names=@('${CONTAINERS[0]}','${CONTAINERS[1]}'); \
\$deadline=(Get-Date).AddSeconds(150); \
do { \
  \$states=@(\$names | ForEach-Object { docker inspect \$_ --format '{{.State.Health.Status}}' }); \
  Write-Output ((\$names | ForEach-Object -Begin { \$i=0 } -Process { \$_ + '=' + \$states[\$i]; \$i++ }) -join ' '); \
  if (@(\$states | Where-Object { \$_ -ne 'healthy' }).Count -eq 0) { exit 0 }; \
  Start-Sleep -Seconds 3; \
} while ((Get-Date) -lt \$deadline); \
throw 'Timed out waiting for bot container healthchecks'\""

echo "Checking metrics endpoints..."
ssh "$REMOTE_SSH_HOST" "powershell -NoProfile -Command \"\
\$test=(Invoke-WebRequest -UseBasicParsing 'http://localhost:7111/metrics').StatusCode; \
\$prod=(Invoke-WebRequest -UseBasicParsing 'http://localhost:7222/metrics').StatusCode; \
if (\$test -ne 200 -or \$prod -ne 200) { throw ('Metrics failed: test=' + \$test + ' prod=' + \$prod) }; \
Write-Output ('metrics test=' + \$test + ' prod=' + \$prod)\""

echo "Deployment complete: ${git_sha}"
