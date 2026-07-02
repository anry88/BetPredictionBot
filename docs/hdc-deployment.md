# HDC bot container deployment

This is the canonical procedure for updating the test and production
BetPredictionBot containers on the Windows HDC host.

## Topology

- SSH host alias: `hdc`.
- Compose project: `football-predictions`.
- Compose file:
  `D:\HomeDataCenter\compose\docker-compose.betprediction.yml`.
- Shared bot image: `hdc/betprediction-bot:latest`.
- Test container: `hdc-betprediction-test`, metrics on port `7111`.
- Production container: `hdc-betprediction-prod`, metrics on port `7222`.
- Model container: `hdc-football-model`. A bot-only deployment must not recreate it.
- Test SQLite and logs:
  `D:\Apps\BetPredictionBot\BetPredictionBot`.
- Production SQLite and logs:
  `D:\BetPredictionBot_PROD\BetPredictionBot`.

The databases, configuration files, and logs are bind-mounted from the host.
Recreating the bot containers does not replace them.

## Standard deployment

Prerequisites:

- local Docker is running;
- the `hdc` SSH alias works;
- the intended commit is checked out;
- the worktree is clean.

From the repository root, run:

```bash
./scripts/deploy-hdc-bots.sh
```

The script:

1. runs `./gradlew test`;
2. builds the current commit locally for `linux/amd64`;
3. tags it as both `hdc/betprediction-bot:<git-sha>` and `latest`;
4. streams the image over SSH with `docker save | docker load`;
5. recreates only `betprediction-test` and `betprediction-prod` with
   `--no-deps --pull never`;
6. waits for both Docker healthchecks;
7. requires HTTP 200 from both metrics endpoints.

Use a different SSH alias only when required:

```bash
REMOTE_SSH_HOST=my-hdc ./scripts/deploy-hdc-bots.sh
```

The script rejects a dirty worktree because it updates production and records
the deployed Git SHA as an image tag.

## Why the image is built locally

Do not use the current
`D:\HomeDataCenter\scripts\hdc-betprediction-build.sh` from an SSH session.
On Docker Desktop 29, the HDC Docker config uses `credsStore: desktop`.
Windows SSH sessions do not have the Docker Desktop logon session, so base
image resolution fails with:

```text
error getting credentials: A specified logon session does not exist
```

The existing `prepare-docker-config.ps1` workaround writes an empty `Og==`
basic-auth value. Docker 29 rejects that auth configuration, and BuildKit
still falls back to the inaccessible credential helper.

Building locally with an explicit `linux/amd64` platform and loading the image
directly avoids the registry and credential helper entirely. The platform is
mandatory when deploying from an Apple Silicon machine because HDC runs
`amd64`.

## Manual equivalent

Use this only to diagnose or recover the scripted path:

```bash
git_sha="$(git rev-parse --short=12 HEAD)"

./gradlew test
docker build \
  --platform linux/amd64 \
  --label "org.opencontainers.image.revision=${git_sha}" \
  -t "hdc/betprediction-bot:${git_sha}" .
docker tag \
  "hdc/betprediction-bot:${git_sha}" \
  hdc/betprediction-bot:latest

docker save \
  "hdc/betprediction-bot:${git_sha}" \
  hdc/betprediction-bot:latest |
  ssh hdc "docker load"

ssh hdc 'cmd /c "cd /d D:\HomeDataCenter\compose && docker compose -f docker-compose.betprediction.yml up -d --no-deps --force-recreate --pull never betprediction-test betprediction-prod"'
```

Verify:

```bash
ssh hdc 'docker inspect hdc-betprediction-test hdc-betprediction-prod --format "{{.Name}} status={{.State.Status}} health={{.State.Health.Status}} image={{.Image}}"'
```

Both metrics endpoints must return HTTP 200:

- `http://localhost:7111/metrics`
- `http://localhost:7222/metrics`

## Rollback

Each scripted deployment retains a Git-SHA image tag on HDC. To roll back,
replace `<previous-sha>` and run:

```bash
ssh hdc 'docker tag hdc/betprediction-bot:<previous-sha> hdc/betprediction-bot:latest'
ssh hdc 'cmd /c "cd /d D:\HomeDataCenter\compose && docker compose -f docker-compose.betprediction.yml up -d --no-deps --force-recreate --pull never betprediction-test betprediction-prod"'
```

Then repeat the healthcheck and metrics verification.

## Database changes

For a normal code or resource update, do not stop or copy the databases:
container recreation preserves the bind mounts.

Before a manual database migration or historical match backfill:

1. stop both bot containers;
2. copy both `predictions.db` files to a timestamped directory under
   `D:\HomeDataCenter\backups`;
3. make the database change transactionally;
4. run SQLite `PRAGMA integrity_check`;
5. restart both containers and verify healthchecks and metrics.
