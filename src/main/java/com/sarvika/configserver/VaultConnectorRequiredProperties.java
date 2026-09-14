package com.sarvika.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Forces eager resolution of VAULT_HOST/VAULT_SCHEME so a missing value fails fast at
 * startup, not on the first Vault call - see GitConnectorRequiredProperties for the same
 * issue on the Git side.
 */
@Configuration
@Profile("vault")
class VaultConnectorRequiredProperties {

	VaultConnectorRequiredProperties(@Value("${VAULT_HOST}") String vaultHost, @Value("${VAULT_SCHEME}") String vaultScheme) {
	}
}
