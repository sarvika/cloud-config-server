# config-server

A config server that integrates with multiple configuration sources with ease. Not deployed anywhere, not wired into the real `config-server` repo — this proves the pluggable-connector pattern so it can be ported into production with confidence.

## Connectors

One request merges config from up to **four** sources at once: OpenBao (Vault), AWS Secrets Manager, AWS Parameter Store, and Git. See [doc/connectors.md](doc/connectors.md) for precedence order and how each connector contributes.

## Quick start

```
docker compose up -d   # OpenBao (dev mode, :18200) + Moto (AWS mock, :5000)

curl --header "X-Vault-Token: root" --request POST \
  --data '{"data":{"demo.source":"vault"}}' \
  "http://localhost:18200/v1/secret/data/demo-app,staging"

SECURITY_PASSWORD=labpassword VAULT_HOST=127.0.0.1 VAULT_PORT=18200 VAULT_SCHEME=http VAULT_TOKEN=root \
SPRING_PROFILES_ACTIVE=vault ./gradlew bootRun

curl -u root:labpassword http://localhost:8889/demo-app/staging
```

Full walkthrough, including Git and both AWS connectors: [doc/running-locally.md](doc/running-locally.md)

## Environment variables

Every backend connection detail is externalized via env vars — nothing environment-specific is baked into the image, and required variables have no insecure/demo fallback. Full reference: [doc/environment-variables.md](doc/environment-variables.md)

## Resiliency

If one connector's backend is unreachable, its contribution is dropped from the merged response and logged as a `WARN` — the request still succeeds with whatever the other connectors returned. Applies uniformly to all four connectors, including the built-in Git/Vault ones (see `ResilientEnvironmentRepositoryConfig`).

## Example requests

```
curl -u root:labpassword http://localhost:8889/demo-app/staging       # merged JSON
curl -u root:labpassword http://localhost:8889/demo-app-staging.yml   # flattened YAML
curl http://localhost:8889/actuator/health                            # health, no auth needed
```

## Extending

Adding another connector, built-in or custom: [doc/extending-connectors.md](doc/extending-connectors.md)

## Testing

`./gradlew test` (fast, mocked) and `./gradlew integrationTest` (real OpenBao/Moto via Testcontainers) both gate the GHCR publish on every push to `main`. Full details: [doc/testing.md](doc/testing.md)

## License

[MIT](LICENSE)
