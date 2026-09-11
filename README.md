# config-server

A config server that integrates with multiple configuration sources with ease. Not deployed anywhere, not wired into the real `config-server` repo — this proves the pluggable-connector pattern so it can be ported into production with confidence.

## Connectors demonstrated

One request (`GET /demo-app/staging`) merges config from up to **four** sources at once, each contributing part of the final response:

| Connector | Type | Precedence (`order`) | What it contributes |
|---|---|---|---|
| OpenBao | built-in Vault backend | 1 (highest) | `demo.source`, `demo.greeting`, `demo.db.password` |
| AWS Secrets Manager | **custom** `EnvironmentRepository` (not built into Spring Cloud Config) | 2 | `external.token`, `external.api-base` |
| AWS Parameter Store | **custom** `EnvironmentRepository` (not built into Spring Cloud Config) | 3 | whatever parameters exist under `/demo-app/staging` (or a less-specific fallback path) |
| Git | built-in Git backend | 4 (lowest) | whatever `demo-app.yml`/`demo-app-staging.yml` your own git remote defines |

This project ships no bundled demo git repo (see [Environment variables](#environment-variables) — `GIT_REPO_URI` has no default), so out of the box only OpenBao, AWS Secrets Manager, and AWS Parameter Store actually contribute anything; the merged response will simply have no Git-sourced properties until you point `GIT_REPO_URI` at a real repo. The pattern this is meant to prove: if your real Git remote still carries a legacy plaintext secret (e.g. `demo.db.password: some-plaintext-value` in `demo-app-staging.yml`), giving Vault/OpenBao a lower `order` number lets it override that one key at read time — without needing to touch or remove Git as the source for everything else non-secret.

AWS Secrets Manager and AWS Parameter Store are deliberately separate connectors, not one — same AWS account, different service, different natural use (Secrets Manager: one JSON blob per secret, often with automatic rotation; Parameter Store: many individual hierarchical parameters, often a mix of plain `String` and KMS-encrypted `SecureString` values). Both follow the identical "most specific path/name wins, falls back to less specific" convention (see `ConnectorNaming`), just applied to a JSON-blob-per-secret-name model for one and a hierarchical-path model for the other.

## Running it locally

1. **Start the backing services:**
   ```
   docker compose up -d
   ```
   This starts OpenBao (dev mode, root token `root`, host port `18200`) and [Moto](https://github.com/getmoto/moto) (open-source AWS mock, host port `5000` — used instead of LocalStack because LocalStack's `latest` image now requires a paid auth token for Secrets Manager).

   > If port `18200` or `5000` is already in use on your machine, change the mapping in `docker-compose.yml` and the matching `application.yml` values.

2. **Seed OpenBao** (KV v2, path convention `secret/{application},{profile}`):
   ```
   curl --header "X-Vault-Token: root" --request POST \
     --data '{"data":{"demo.source":"vault","demo.greeting":"hello-from-openbao","demo.db.password":"openbao-real-secret-p@ss"}}' \
     "http://localhost:18200/v1/secret/data/demo-app,staging"
   ```

3. **Seed Moto (AWS Secrets Manager)** — no real AWS credentials or SigV4 signing needed, Moto doesn't validate them:
   ```
   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: secretsmanager.CreateSecret" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/secretsmanager/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"demo-app-staging","SecretString":"{\"external.token\":\"moto-secret-token-123\",\"external.api-base\":\"https://mock.example.com\"}"}'
   ```

4. **Seed Moto (AWS Parameter Store)** — same Moto instance, different service (SSM). One call per parameter:
   ```
   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: AmazonSSM.PutParameter" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"/demo-app/staging/greeting","Value":"hello-from-parameter-store","Type":"String","Overwrite":true}'

   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: AmazonSSM.PutParameter" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"/demo-app/staging/db/password","Value":"ssm-secure-password","Type":"SecureString","Overwrite":true}'
   ```
   The second parameter becomes `db.password` in the merged response (nested path segments join with `.`).

5. **Git connector** — there's no bundled repo to point it at (see above), and `git` is one of the default active profiles, so `GIT_REPO_URI` is required (the app refuses to start without it, same as the other required variables below). Either point it at any git remote you control (`https://`, `ssh://`, or a local `file:` path to a repo you've initialized yourself) containing a `demo-app.yml` and/or `demo-app-staging.yml`, e.g. a quick scratch repo:
   ```
   mkdir -p /tmp/demo-git-repo && cd /tmp/demo-git-repo
   git init -b master
   echo 'demo: {source: git}' > demo-app.yml
   git add -A && git commit -m seed
   ```
   ...or skip it entirely by dropping `git` from `SPRING_PROFILES_ACTIVE` (see the table below) — then `GIT_REPO_URI` isn't required at all, and the merged response just has no Git-sourced property source.

6. **Run the server:**
   ```
   GIT_REPO_URI=file:///tmp/demo-git-repo \
   VAULT_HOST=127.0.0.1 VAULT_PORT=18200 VAULT_SCHEME=http \
   VAULT_TOKEN=root \
   SECURITY_PASSWORD=labpassword \
   AWS_SECRETSMANAGER_ENDPOINT=http://localhost:5000 \
   AWS_SECRETSMANAGER_REGION=us-east-1 \
   AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER=static \
   AWS_SECRETSMANAGER_ACCESS_KEY=test AWS_SECRETSMANAGER_SECRET_KEY=test \
   AWS_PARAMETERSTORE_ENDPOINT=http://localhost:5000 \
   AWS_PARAMETERSTORE_REGION=us-east-1 \
   AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER=static \
   AWS_PARAMETERSTORE_ACCESS_KEY=test AWS_PARAMETERSTORE_SECRET_KEY=test \
   mvn spring-boot:run
   ```
   Listens on `:8889`, Basic Auth `root` / `labpassword` (override the username via `SECURITY_USERNAME`). None of these has an insecure or demo-pointing default baked into `application.yml` on purpose — see [Environment variables](#environment-variables) below for why, and what a real deployment needs to set instead.

## Environment variables

Every backend connection detail is externalized so the same image is generic across environments and services — nothing environment-specific is baked in, only overridden via env vars, and **no default points at localhost, Moto, or the bundled demo git repo**. Variables marked **required** have *no* fallback: the app fails fast at startup with a clear "could not resolve placeholder" error rather than silently running against a local demo backend or a known credential.

**Connectors are independent of each other.** Only run `awssecrets`? Only need Vault, no Git or AWS at all? `SPRING_PROFILES_ACTIVE` controls which connectors are active (default `git,vault,awssecrets,awsparameterstore`) — drop whichever ones you don't want, and that connector's required variables aren't required at all. For example, `SPRING_PROFILES_ACTIVE=awssecrets` with none of the `GIT_REPO_URI`/`VAULT_*`/`AWS_PARAMETERSTORE_*` variables set starts up fine.

| Variable | Required? | Local demo value | What it controls |
|---|---|---|---|
| `SECURITY_USERNAME` | no (defaults to `root`) | `root` | Basic Auth username for every client of this server |
| `SECURITY_PASSWORD` | **yes** | `labpassword` | Basic Auth password — no default, ever |
| `GIT_REPO_URI` | **yes** (while `git` is an active profile) | `file:///tmp/demo-git-repo` | Git connector remote (any URI JGit supports: `https://`, `ssh://`, `file:`) — no default, and a dedicated startup check enforces this (see `GitConnectorRequiredProperties`); Spring's own `spring.cloud.config.server.git.uri` binding alone would otherwise silently accept the unresolved placeholder and only fail on the first client request with a confusing 404 |
| `VAULT_HOST` / `VAULT_SCHEME` | **yes** (while `vault` is an active profile) | `127.0.0.1` / `http` | Vault/OpenBao network location — no default, and a dedicated startup check enforces this (see `VaultConnectorRequiredProperties`), for the same reason `GIT_REPO_URI` needs one: these are String properties that would otherwise silently tolerate an unresolved placeholder until first use |
| `VAULT_PORT` | **yes** (while `vault` is an active profile) | `18200` | Vault/OpenBao port — no default; this one fails fast "for free" since it's bound as an `Integer` |
| `VAULT_BACKEND` / `VAULT_KV_VERSION` | no | `secret` / `2` | Vault backend mount, KV engine version — protocol conventions, not environment-specific, so a default is safe here. Auth method is fixed to `TOKEN` (not configurable): it's the only one actually wired up end to end today |
| `VAULT_TOKEN` | **yes** (while `vault` is an active profile) | `root` | Vault/OpenBao auth token — no default, ever. Scoped to its own profile-activated document in `application.yml` so it isn't required at all when `vault` isn't active - it used to be, due to a Spring Cloud Config Server quirk unrelated to our own code (see the comment above the vault document in `application.yml`) |
| `AWS_SECRETSMANAGER_ENDPOINT` | no (defaults to empty = real AWS) | `http://localhost:5000` | AWS Secrets Manager endpoint override — the SDK resolves the correct real endpoint on its own when this is unset; only set it for a local mock |
| `AWS_SECRETSMANAGER_REGION` | **yes** | `us-east-1` | AWS region — no default: guessing wrong wouldn't necessarily fail loudly, since a shared AWS account could have an unrelated, coincidentally-same-named secret sitting in whatever region got silently assumed |
| `AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER` | no (defaults to `default`) | `static` | `default` uses the AWS SDK's standard credential chain (IAM role, instance metadata, etc.) — unlike every other default on this page, this *is* the objectively correct choice for a real deployment, not a demo convenience, so it's fine to default. `static` + the two variables below exist only for local runs against Moto. Any other value (including a typo) fails startup rather than silently falling back to `static` |
| `AWS_SECRETSMANAGER_ACCESS_KEY` / `_SECRET_KEY` | **yes, if and only if** `_CREDENTIALS_PROVIDER=static` | `test` / `test` | Static AWS credentials — not read at all (and not required) when using `default`; if `static` is chosen, both must be set or startup fails |
| `AWS_SECRETSMANAGER_API_CALL_ATTEMPT_TIMEOUT` / `_API_CALL_TIMEOUT` | no (default `2s` / `3s`) | `2s` / `3s` | Per-attempt / total SDK call timeout, so a slow Secrets Manager can't hang the whole merged response |
| `AWS_PARAMETERSTORE_ENDPOINT` | no (defaults to empty = real AWS) | `http://localhost:5000` | AWS Parameter Store (SSM) endpoint override — leave unset for real AWS, same as its Secrets Manager equivalent |
| `AWS_PARAMETERSTORE_REGION` | **yes** | `us-east-1` | AWS region — same "no safe default" reasoning as `AWS_SECRETSMANAGER_REGION` |
| `AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER` | no (defaults to `default`) | `static` | Same semantics as `AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER` — `default` is the correct choice for a real deployment; `static` is for local Moto runs only, and any other value fails startup |
| `AWS_PARAMETERSTORE_ACCESS_KEY` / `_SECRET_KEY` | **yes, if and only if** `_CREDENTIALS_PROVIDER=static` | `test` / `test` | Static AWS credentials, same rules as the Secrets Manager pair |
| `AWS_PARAMETERSTORE_API_CALL_ATTEMPT_TIMEOUT` / `_API_CALL_TIMEOUT` | no (default `2s` / `3s`) | `2s` / `3s` | Per-attempt / total SDK call timeout for Parameter Store calls |
| `SERVER_PORT` | no (defaults to `8889`) | `8889` | HTTP port (the Dockerfile's `EXPOSE 8889` is documentation only — if you override this, update your port mapping/health probe to match) |
| `SPRING_PROFILES_ACTIVE` | no (defaults to `git,vault,awssecrets,awsparameterstore`) | `git,vault,awssecrets,awsparameterstore` | Standard Spring Boot variable — which connectors are active at all. Drop `awssecrets` and/or `awsparameterstore` from the list to disable either AWS connector independently, for example |

For a real deployment, TLS termination (this server has none built in) and per-client credential scoping (today it's one shared Basic Auth credential for every consumer) are still open questions to settle with whoever owns your security posture before other services depend on this — see the note in the project description below.

## Resiliency

If one connector's backend is unreachable, its contribution is dropped from the merged response and logged as a `WARN` — the request still succeeds with whatever the other (healthy) connectors returned, rather than the whole thing failing. This applies uniformly to all four connectors, including the built-in Git and Vault repositories (Spring Cloud Config doesn't do this by default for those — see `ResilientEnvironmentRepositoryConfig`), not just the custom AWS ones. Verified even for the worst case: all four backends unreachable at once still returns `200` with an empty `propertySources` list, never an error.

## Example requests

```
# Merged JSON, up to 4 sources
curl -u root:labpassword http://localhost:8889/demo-app/staging

# Flattened YAML — shows the final resolved value per key after precedence is applied
curl -u root:labpassword http://localhost:8889/demo-app-staging.yml

# Health - no credentials needed, so orchestrator liveness/readiness probes work unmodified
curl http://localhost:8889/actuator/health
```

## Adding another connector

- **Built-in type** (Redis, CredHub, another Git remote, etc.): pure configuration — add it to `spring.profiles.active` and its own `spring.cloud.config.server.<type>` block in `application.yml`, with an `order`.
- **Not built-in** (Azure Key Vault, Consul, GCP Secret Manager, ...): follow the pattern in `src/main/java/com/sarvika/configserver/aws/` — implement `EnvironmentRepository` + `Ordered`, register it as a `@Bean` under its own Spring profile. No SPI factory registration needed; this is the officially documented extension point.

## Testing

Two tiers, run at different points:

- **`mvn test`** — fast, mocked unit tests (`*Test.java`, run by Surefire). Mocks the AWS SDK clients directly; no Docker, no network. Runs on every push (`test` job in CI).
- **`mvn verify`** — the same unit tests, plus `ConfigServerIntegrationIT` (`*IT.java`, run by Failsafe): boots the real Spring application against **real** OpenBao and Moto containers via [Testcontainers](https://testcontainers.com/), seeds them exactly like the manual walkthrough above, and asserts the full 4-connector merge resolves with correct precedence — then stops the Vault container mid-test and asserts the response still succeeds with just Vault's contribution missing, proving the resiliency behavior (see `ResilientEnvironmentRepositoryConfig`) against a real backend outage, not a mock. Needs Docker; runs in its own `integration-test` CI job and gates the GHCR publish alongside `test`.

Both are required to pass before `build-and-push` runs, on every push to `main`.
