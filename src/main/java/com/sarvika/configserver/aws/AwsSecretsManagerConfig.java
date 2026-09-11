package com.sarvika.configserver.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.time.Duration;

@Configuration
@Profile("awssecrets")
public class AwsSecretsManagerConfig {

	@Bean
	public SecretsManagerClient secretsManagerClient(
			@Value("${aws.secretsmanager.endpoint:}") String endpoint,
			@Value("${aws.secretsmanager.region}") String region,
			@Value("${aws.secretsmanager.credentials-provider:default}") String credentialsProviderType,
			@Value("${aws.secretsmanager.access-key:}") String accessKey,
			@Value("${aws.secretsmanager.secret-key:}") String secretKey,
			@Value("${aws.secretsmanager.api-call-attempt-timeout:2s}") Duration apiCallAttemptTimeout,
			@Value("${aws.secretsmanager.api-call-timeout:3s}") Duration apiCallTimeout) {
		AwsCredentialsProvider credentialsProvider = AwsCredentialsProviders.resolve("AWS_SECRETSMANAGER",
				credentialsProviderType, accessKey, secretKey);

		// Bounded so a slow/unreachable Secrets Manager endpoint can't hang the whole
		// merged /{application}/{profile} response indefinitely - see AwsSecretsManagerEnvironmentRepository,
		// which also catches the resulting timeout and degrades gracefully instead of failing the request.
		ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
				.apiCallAttemptTimeout(apiCallAttemptTimeout)
				.apiCallTimeout(apiCallTimeout)
				.build();

		var builder = SecretsManagerClient.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider)
				.overrideConfiguration(overrideConfiguration);

		if (!endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint));
		}

		return builder.build();
	}

	@Bean
	public AwsSecretsManagerEnvironmentRepository awsSecretsManagerEnvironmentRepository(
			SecretsManagerClient secretsManagerClient,
			@Value("${spring.cloud.config.server.awssecrets.order:2}") int order) {
		return new AwsSecretsManagerEnvironmentRepository(secretsManagerClient, order);
	}
}
