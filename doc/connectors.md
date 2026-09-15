# Connectors

One request (`GET /demo-app/staging`) merges config from up to **four** sources at once, each contributing part of the final response:

| Connector | Type | Precedence (`order`) | What it contributes |
|---|---|---|---|
| OpenBao | built-in Vault backend | 1 (highest) | `demo.source`, `demo.greeting`, `demo.db.password` |
| AWS Secrets Manager | **custom** `EnvironmentRepository` (not built into Spring Cloud Config) | 2 | `external.token`, `external.api-base` |
| AWS Parameter Store | **custom** `EnvironmentRepository` (not built into Spring Cloud Config) | 3 | whatever parameters exist under `/demo-app/staging` (or a less-specific fallback path) |
| Git | built-in Git backend | 4 (lowest) | whatever `demo-app.yml`/`demo-app-staging.yml` your own git remote defines |

This project ships no bundled demo git repo (see [environment-variables.md](environment-variables.md) — `GIT_REPO_URI` has no default), so out of the box only OpenBao, AWS Secrets Manager, and AWS Parameter Store actually contribute anything; the merged response will simply have no Git-sourced properties until you point `GIT_REPO_URI` at a real repo. The pattern this is meant to prove: if your real Git remote still carries a legacy plaintext secret (e.g. `demo.db.password: some-plaintext-value` in `demo-app-staging.yml`), giving Vault/OpenBao a lower `order` number lets it override that one key at read time — without needing to touch or remove Git as the source for everything else non-secret.

AWS Secrets Manager and AWS Parameter Store are deliberately separate connectors, not one — same AWS account, different service, different natural use (Secrets Manager: one JSON blob per secret, often with automatic rotation; Parameter Store: many individual hierarchical parameters, often a mix of plain `String` and KMS-encrypted `SecureString` values). Both follow the identical "most specific path/name wins, falls back to less specific" convention (see `ConnectorNaming`), just applied to a JSON-blob-per-secret-name model for one and a hierarchical-path model for the other.
