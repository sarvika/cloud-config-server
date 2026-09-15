# Adding another connector

- **Built-in type** (Redis, CredHub, another Git remote, etc.): pure configuration — add it to `spring.profiles.active` and its own `spring.cloud.config.server.<type>` block in `application.yml`, with an `order`.
- **Not built-in** (Azure Key Vault, Consul, GCP Secret Manager, ...): follow the pattern in `src/main/java/com/sarvika/configserver/aws/` — implement `EnvironmentRepository` + `Ordered`, register it as a `@Bean` under its own Spring profile. No SPI factory registration needed; this is the officially documented extension point.
