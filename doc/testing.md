# Testing

Two tiers, run at different points:

- **`./gradlew test`** — fast, mocked unit tests (`*Test.java`). Mocks the AWS SDK clients directly; no Docker, no network. Runs on every push (`test` job in CI).
- **`./gradlew integrationTest`** — `ConfigServerIntegrationIT` (`*IT.java`, a dedicated Gradle task — see `build.gradle.kts`): boots the real Spring application against **real** OpenBao and Moto containers via [Testcontainers](https://testcontainers.com/), seeds them exactly like the manual walkthrough in [running-locally.md](running-locally.md), and asserts the full 4-connector merge resolves with correct precedence — then stops the Vault container mid-test and asserts the response still succeeds with just Vault's contribution missing, proving the resiliency behavior (see `ResilientEnvironmentRepositoryConfig`) against a real backend outage, not a mock. Needs Docker; runs in its own `integration-test` CI job and gates the GHCR publish alongside `test`.

Both are required to pass before `build-and-push` runs, on every push to `main`.
