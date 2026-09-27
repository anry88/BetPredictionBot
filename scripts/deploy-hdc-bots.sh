#!/usr/bin/env bash
set -euo pipefail

REMOTE_SSH_HOST="${REMOTE_SSH_HOST:-hdc}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-hdc/betprediction-bot}"
DEPLOY_TARGET="${DEPLOY_TARGET:-all}"
COMPOSE_FILE="D:\\HomeDataCenter\\compose\\docker-compose.betprediction.yml"
COMPOSE_DIR="D:\\HomeDataCenter\\compose"

case "$DEPLOY_TARGET" in
    test)
        SERVICES=(betprediction-test)
        CONTAINERS=(hdc-betprediction-test)
        METRICS_PORTS=(7111)
        ;;
    prod)
        SERVICES=(betprediction-prod)
        CONTAINERS=(hdc-betprediction-prod)
        METRICS_PORTS=(7222)
        ;;
    all)
        SERVICES=(betprediction-test betprediction-prod)
        CONTAINERS=(hdc-betprediction-test hdc-betprediction-prod)
        METRICS_PORTS=(7111 7222)
        ;;
    *)
        echo "DEPLOY_TARGET must be one of: test, prod, all" >&2
        exit 1
        ;;
esac

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
    echo "The worktree is dirty. Commit the deployment before updating HDC." >&2
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

echo "Recreating bot services for target '${DEPLOY_TARGET}': ${SERVICES[*]}..."
ssh "$REMOTE_SSH_HOST" \
    "cmd /c \"cd /d ${COMPOSE_DIR} && docker compose -f ${COMPOSE_FILE} up -d --no-deps --force-recreate --pull never ${SERVICES[*]}\""

echo "Waiting for selected healthchecks..."
for container in "${CONTAINERS[@]}"; do
    ssh "$REMOTE_SSH_HOST" "powershell -NoProfile -Command \"\
\$ErrorActionPreference='Stop'; \
\$deadline=(Get-Date).AddSeconds(150); \
do { \
  \$state=docker inspect '${container}' --format '{{.State.Health.Status}}'; \
  Write-Output ('${container}=' + \$state); \
  if (\$state -eq 'healthy') { exit 0 }; \
  Start-Sleep -Seconds 3; \
} while ((Get-Date) -lt \$deadline); \
throw 'Timed out waiting for ${container} healthcheck'\""
done

echo "Checking metrics endpoints..."
for port in "${METRICS_PORTS[@]}"; do
    ssh "$REMOTE_SSH_HOST" "powershell -NoProfile -Command \"\
\$status=(Invoke-WebRequest -UseBasicParsing 'http://localhost:${port}/metrics').StatusCode; \
if (\$status -ne 200) { throw ('Metrics failed on port ${port}: ' + \$status) }; \
Write-Output ('metrics port ${port}=' + \$status)\""
done

echo "Deployment complete: ${git_sha}"
