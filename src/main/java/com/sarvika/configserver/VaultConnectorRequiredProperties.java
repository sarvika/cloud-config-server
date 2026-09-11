package com.sarvika.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * spring.cloud.config.server.vault.host/scheme silently tolerate an unresolved
 * placeholder until a client request first triggers a Vault call, then fail with a
 * confusing "Not enough variable values available to expand 'key'" error - not the
 * fail-fast startup error every other required connector setting gets. VAULT_PORT gets
 * fail-fast for free because it's bound as an Integer (type conversion fails eagerly);
 * VAULT_TOKEN gets it for free via Vault's own @ConditionalOnProperty check. Binding
 * VAULT_HOST/VAULT_SCHEME here via @Value forces the same eager placeholder resolution
 * for these two String properties, which would otherwise slip through silently - see
 * GitConnectorRequiredProperties for the identical issue on the Git side.
 */
@Configuration
@Profile("vault")
class VaultConnectorRequiredProperties {

	VaultConnectorRequiredProperties(@Value("${VAULT_HOST}") String vaultHost, @Value("${VAULT_SCHEME}") String vaultScheme) {
	}
}
