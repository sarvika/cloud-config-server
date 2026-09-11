package com.sarvika.configserver.aws;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.config.environment.Environment;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the candidate secret-name precedence (most specific wins), the label and
 * multi-profile handling, and the graceful-degradation-on-failure behavior of
 * {@link AwsSecretsManagerEnvironmentRepository}.
 */
class AwsSecretsManagerEnvironmentRepositoryTest {

	private final SecretsManagerClient client = mock(SecretsManagerClient.class);

	private final AwsSecretsManagerEnvironmentRepository repository =
			new AwsSecretsManagerEnvironmentRepository(client, 2);

	@Test
	void fallsBackFromProfileSpecificToBareApplicationSecret() {
		stubNotFound("demo-app-staging");
		stubFound("demo-app", "{\"demo.source\":\"aws-app-default\"}");

		Environment environment = repository.findOne("demo-app", "staging", "master");

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("demo.source", "aws-app-default");
		verify(client, times(1)).getSecretValue(requestFor("demo-app-staging"));
		verify(client, times(1)).getSecretValue(requestFor("demo-app"));
	}

	@Test
	void labelQualifiedSecretTakesPrecedenceOverProfileOnly() {
		stubFound("demo-app-staging-v2", "{\"demo.source\":\"aws-label-qualified\"}");

		Environment environment = repository.findOne("demo-app", "staging", "v2");

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("demo.source", "aws-label-qualified");
		// most specific candidate hit first - no need to fall back further
		verify(client, never()).getSecretValue(requestFor("demo-app-staging"));
		verify(client, never()).getSecretValue(requestFor("demo-app"));
	}

	@Test
	void defaultMasterLabelIsTreatedAsNoLabel() {
		stubFound("demo-app-staging", "{\"demo.source\":\"aws-profile-only\"}");

		repository.findOne("demo-app", "staging", "master");

		// "master" must never be turned into a literal "-master" secret-name segment
		verify(client, never()).getSecretValue(requestFor("demo-app-staging-master"));
	}

	@Test
	void multipleProfilesAreTriedMostSpecificRightmostFirst() {
		stubNotFound("demo-app-us-east");
		stubFound("demo-app-staging", "{\"demo.source\":\"aws-staging\"}");

		Environment environment = repository.findOne("demo-app", "staging,us-east", null);

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("demo.source", "aws-staging");
		verify(client, times(1)).getSecretValue(requestFor("demo-app-us-east"));
		verify(client, times(1)).getSecretValue(requestFor("demo-app-staging"));
		verify(client, never()).getSecretValue(requestFor("demo-app"));
	}

	@Test
	void aRealFailureDegradesToNoPropertySourceInsteadOfThrowing() {
		when(client.getSecretValue(requestFor("demo-app-staging")))
				.thenThrow(SdkClientException.create("simulated network failure"));

		Environment environment = repository.findOne("demo-app", "staging", null);

		assertThat(environment.getPropertySources()).isEmpty();
		// stops after the first real failure rather than hammering every remaining candidate
		verify(client, times(1)).getSecretValue(requestFor("demo-app-staging"));
		verify(client, never()).getSecretValue(requestFor("demo-app"));
	}

	private void stubFound(String secretId, String json) {
		when(client.getSecretValue(requestFor(secretId)))
				.thenReturn(GetSecretValueResponse.builder().secretString(json).build());
	}

	private void stubNotFound(String secretId) {
		when(client.getSecretValue(requestFor(secretId)))
				.thenThrow(ResourceNotFoundException.builder().message("no such secret: " + secretId).build());
	}

	private static GetSecretValueRequest requestFor(String secretId) {
		return argThat(request -> request != null && secretId.equals(request.secretId()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> sourceOf(Environment environment) {
		return (Map<String, Object>) environment.getPropertySources().get(0).getSource();
	}
}
