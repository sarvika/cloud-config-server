package com.sarvika.configserver.aws;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Shared "default vs static" credentials-provider resolution used by both custom AWS
 * connectors: "default" is the AWS SDK's standard credential chain (IAM role, instance
 * metadata, ...) and the only safe choice for a real deployment; "static" requires both
 * an access key and secret key to be supplied. Anything else - a typo, in particular -
 * fails loudly at startup rather than silently falling back to either one.
 */
final class AwsCredentialsProviders {

	private AwsCredentialsProviders() {
	}

	static AwsCredentialsProvider resolve(String envVarPrefix, String credentialsProviderType, String accessKey,
			String secretKey) {
		if ("default".equalsIgnoreCase(credentialsProviderType)) {
			return DefaultCredentialsProvider.create();
		}
		if ("static".equalsIgnoreCase(credentialsProviderType)) {
			if (accessKey.isBlank() || secretKey.isBlank()) {
				throw new IllegalArgumentException(envVarPrefix + "_CREDENTIALS_PROVIDER is 'static' but "
						+ envVarPrefix + "_ACCESS_KEY/" + envVarPrefix + "_SECRET_KEY were not both supplied - "
						+ "set both, or use 'default' instead");
			}
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
		}
		throw new IllegalArgumentException(envVarPrefix + "_CREDENTIALS_PROVIDER must be 'default' or 'static', "
				+ "was: '" + credentialsProviderType + "'");
	}
}
