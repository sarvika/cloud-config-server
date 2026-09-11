package com.sarvika.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the "required, no insecure/demo fallback" contract documented in application.yml
 * and the README's environment variable table. A missing required property must fail
 * context startup, not silently accept an unresolved placeholder and only fail later on
 * the first client request (as GIT_REPO_URI and VAULT_HOST/VAULT_SCHEME once did - see
 * GitConnectorRequiredProperties and VaultConnectorRequiredProperties).
 *
 * Boots the real application via SpringApplicationBuilder (the same path main() uses)
 * rather than ApplicationContextRunner: the latter doesn't fully wire up Boot's Duration
 * conversion support the way a real SpringApplication run does, which would otherwise
 * make every scenario here fail for the wrong reason.
 */
class RequiredEnvironmentVariablesTest {

	private static final List<String> BASELINE_ARGS = List.of(
			"--server.port=0",
			"--spring.profiles.active=git,vault,awssecrets,awsparameterstore",
			"--GIT_REPO_URI=file:///tmp/required-env-vars-test-does-not-need-to-exist",
			"--VAULT_HOST=127.0.0.1",
			"--VAULT_PORT=18200",
			"--VAULT_SCHEME=http",
			"--VAULT_TOKEN=root",
			"--SECURITY_PASSWORD=test",
			"--AWS_SECRETSMANAGER_REGION=us-east-1",
			"--AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER=static",
			"--AWS_SECRETSMANAGER_ACCESS_KEY=test",
			"--AWS_SECRETSMANAGER_SECRET_KEY=test",
			"--AWS_PARAMETERSTORE_REGION=us-east-1",
			"--AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER=static",
			"--AWS_PARAMETERSTORE_ACCESS_KEY=test",
			"--AWS_PARAMETERSTORE_SECRET_KEY=test");

	@Test
	void startsWithEveryRequiredVariableSupplied() {
		try (ConfigurableApplicationContext context = boot(argsWithout())) {
			// no exception during boot() is the assertion
		}
	}

	@Test
	void failsWithoutGitRepoUri() {
		assertThatThrownBy(() -> boot(argsWithout("--GIT_REPO_URI")));
	}

	@Test
	void failsWithoutVaultHost() {
		assertThatThrownBy(() -> boot(argsWithout("--VAULT_HOST")));
	}

	@Test
	void failsWithoutVaultPort() {
		assertThatThrownBy(() -> boot(argsWithout("--VAULT_PORT")));
	}

	@Test
	void failsWithoutVaultScheme() {
		assertThatThrownBy(() -> boot(argsWithout("--VAULT_SCHEME")));
	}

	@Test
	void failsWithoutVaultToken() {
		assertThatThrownBy(() -> boot(argsWithout("--VAULT_TOKEN")));
	}

	@Test
	void failsWithoutSecurityPassword() {
		assertThatThrownBy(() -> boot(argsWithout("--SECURITY_PASSWORD")));
	}

	@Test
	void failsWithoutAwsRegion() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_SECRETSMANAGER_REGION")));
	}

	@Test
	void failsWithUnrecognizedAwsCredentialsProvider() {
		List<String> args = new ArrayList<>(argsWithoutAsList("--AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER"));
		args.add("--AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER=Defualt");
		assertThatThrownBy(() -> boot(args.toArray(new String[0])));
	}

	@Test
	void failsWithStaticProviderButNoAccessKey() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_SECRETSMANAGER_ACCESS_KEY")));
	}

	@Test
	void failsWithStaticProviderButNoSecretKey() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_SECRETSMANAGER_SECRET_KEY")));
	}

	@Test
	void failsWithoutAwsParameterStoreRegion() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_PARAMETERSTORE_REGION")));
	}

	@Test
	void failsWithUnrecognizedAwsParameterStoreCredentialsProvider() {
		List<String> args = new ArrayList<>(argsWithoutAsList("--AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER"));
		args.add("--AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER=Defualt");
		assertThatThrownBy(() -> boot(args.toArray(new String[0])));
	}

	@Test
	void failsWithParameterStoreStaticProviderButNoAccessKey() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_PARAMETERSTORE_ACCESS_KEY")));
	}

	@Test
	void failsWithParameterStoreStaticProviderButNoSecretKey() {
		assertThatThrownBy(() -> boot(argsWithout("--AWS_PARAMETERSTORE_SECRET_KEY")));
	}

	@Test
	void startsWithOnlyAwsParameterStoreProfileActiveNoGitVaultOrSecretsManagerVarsNeeded() {
		String[] args = {
				"--server.port=0",
				"--spring.profiles.active=awsparameterstore",
				"--SECURITY_PASSWORD=test",
				"--AWS_PARAMETERSTORE_REGION=us-east-1",
				"--AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER=static",
				"--AWS_PARAMETERSTORE_ACCESS_KEY=test",
				"--AWS_PARAMETERSTORE_SECRET_KEY=test",
		};
		try (ConfigurableApplicationContext context = boot(args)) {
			// no GIT_REPO_URI/VAULT_*/AWS_SECRETSMANAGER_* supplied at all - no exception is the assertion
		}
	}

	/**
	 * Regression guard: Spring Cloud Config Server's own VaultConfiguration used to read
	 * spring.cloud.config.server.vault.token as part of an internal condition check
	 * regardless of whether "vault" was an active profile, so an environment that only
	 * wanted the AWS Secrets Manager connector was still forced to supply a Vault token
	 * for no reason. Fixed by scoping the whole vault (and git) property block to its own
	 * profile-activated YAML document in application.yml, so those properties don't exist
	 * in the environment at all unless that profile is active.
	 */
	@Test
	void startsWithOnlyAwsSecretsProfileActiveNoGitOrVaultVarsNeeded() {
		String[] args = {
				"--server.port=0",
				"--spring.profiles.active=awssecrets",
				"--SECURITY_PASSWORD=test",
				"--AWS_SECRETSMANAGER_REGION=us-east-1",
				"--AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER=static",
				"--AWS_SECRETSMANAGER_ACCESS_KEY=test",
				"--AWS_SECRETSMANAGER_SECRET_KEY=test",
		};
		try (ConfigurableApplicationContext context = boot(args)) {
			// no GIT_REPO_URI/VAULT_* supplied at all - no exception during boot() is the assertion
		}
	}

	@Test
	void startsWithOnlyVaultProfileActiveNoGitOrAwsVarsNeeded() {
		String[] args = {
				"--server.port=0",
				"--spring.profiles.active=vault",
				"--SECURITY_PASSWORD=test",
				"--VAULT_HOST=127.0.0.1",
				"--VAULT_PORT=18200",
				"--VAULT_SCHEME=http",
				"--VAULT_TOKEN=root",
		};
		try (ConfigurableApplicationContext context = boot(args)) {
			// no GIT_REPO_URI/AWS_* supplied at all - no exception during boot() is the assertion
		}
	}

	private static ConfigurableApplicationContext boot(String[] args) {
		return new SpringApplicationBuilder(ConfigServerApplication.class)
				.web(WebApplicationType.SERVLET)
				.run(args);
	}

	private static String[] argsWithout(String... keyPrefixesToOmit) {
		return argsWithoutAsList(keyPrefixesToOmit).toArray(new String[0]);
	}

	private static List<String> argsWithoutAsList(String... keyPrefixesToOmit) {
		List<String> filtered = new ArrayList<>();
		for (String arg : BASELINE_ARGS) {
			boolean omit = Arrays.stream(keyPrefixesToOmit).anyMatch(prefix -> arg.startsWith(prefix + "="));
			if (!omit) {
				filtered.add(arg);
			}
		}
		return filtered;
	}
}
