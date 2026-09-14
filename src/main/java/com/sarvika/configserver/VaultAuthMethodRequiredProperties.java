package com.sarvika.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.config.server.environment.VaultEnvironmentProperties.AuthenticationMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Fail-fast check for whichever Vault authentication method is actually configured -
 * VAULT_TOKEN/VAULT_KUBERNETES_ROLE default to empty so picking one method doesn't
 * require the other's property. Also rejects any method besides TOKEN/KUBERNETES,
 * since only those two have their required properties wired up in application.yml.
 */
@Configuration
@Profile("vault")
class VaultAuthMethodRequiredProperties {

	VaultAuthMethodRequiredProperties(
			@Value("${spring.cloud.config.server.vault.authentication:TOKEN}") String configuredMethod,
			@Value("${VAULT_TOKEN:}") String vaultToken,
			@Value("${VAULT_KUBERNETES_ROLE:}") String kubernetesRole) {
		AuthenticationMethod method = parse(configuredMethod);
		switch (method) {
			case TOKEN -> {
				if (!StringUtils.hasText(vaultToken)) {
					throw new IllegalStateException(
							"VAULT_TOKEN must be set when using TOKEN authentication (VAULT_AUTHENTICATION_METHOD=TOKEN, "
									+ "the default). Set VAULT_AUTHENTICATION_METHOD=KUBERNETES and VAULT_KUBERNETES_ROLE "
									+ "instead if this server should authenticate as its own pod ServiceAccount.");
				}
			}
			case KUBERNETES -> {
				if (!StringUtils.hasText(kubernetesRole)) {
					throw new IllegalStateException(
							"VAULT_KUBERNETES_ROLE must be set when VAULT_AUTHENTICATION_METHOD=KUBERNETES - this is the "
									+ "Vault/OpenBao Kubernetes auth role bound to this pod's ServiceAccount and namespace.");
				}
			}
			default -> throw new IllegalStateException(
					"VAULT_AUTHENTICATION_METHOD=" + method + " is not supported by this project - only TOKEN "
							+ "(the default) and KUBERNETES have their required properties wired up in application.yml. "
							+ "Adding support for another method needs its own properties exposed there first, not just "
							+ "this value.");
		}
	}

	private static AuthenticationMethod parse(String configuredMethod) {
		try {
			return AuthenticationMethod.valueOf(configuredMethod.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"VAULT_AUTHENTICATION_METHOD=" + configuredMethod + " is not a recognized Vault authentication "
							+ "method. Supported values: TOKEN (default), KUBERNETES.", ex);
		}
	}
}
