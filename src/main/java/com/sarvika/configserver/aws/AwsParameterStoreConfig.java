package com.sarvika.configserver.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.time.Duration;

@Configuration
@Profile("awsparameterstore")
public class AwsParameterStoreConfig {

	@Bean
	public SsmClient ssmClient(
			@Value("${aws.parameterstore.endpoint:}") String endpoint,
			@Value("${aws.parameterstore.region}") String region,
			@Value("${aws.parameterstore.credentials-provider:default}") String credentialsProviderType,
			@Value("${aws.parameterstore.access-key:}") String accessKey,
			@Value("${aws.parameterstore.secret-key:}") String secretKey,
			@Value("${aws.parameterstore.api-call-attempt-timeout:2s}") Duration apiCallAttemptTimeout,
			@Value("${aws.parameterstore.api-call-timeout:3s}") Duration apiCallTimeout) {
		AwsCredentialsProvider credentialsProvider = AwsCredentialsProviders.resolve("AWS_PARAMETERSTORE",
				credentialsProviderType, accessKey, secretKey);

		// Bounded so a slow/unreachable Parameter Store endpoint can't hang the whole
		// merged /{application}/{profile} response indefinitely - see
		// AwsParameterStoreEnvironmentRepository, which also catches the resulting
		// timeout and degrades gracefully instead of failing the request.
		ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
				.apiCallAttemptTimeout(apiCallAttemptTimeout)
				.apiCallTimeout(apiCallTimeout)
				.build();

		var builder = SsmClient.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider)
				.overrideConfiguration(overrideConfiguration);

		if (!endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint));
		}

		return builder.build();
	}

	@Bean
	public AwsParameterStoreEnvironmentRepository awsParameterStoreEnvironmentRepository(
			SsmClient ssmClient,
			@Value("${spring.cloud.config.server.awsparameterstore.order:3}") int order) {
		return new AwsParameterStoreEnvironmentRepository(ssmClient, order);
	}
}
