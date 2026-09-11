package com.sarvika.configserver.aws;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.config.environment.Environment;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the candidate path precedence (most specific wins), the label and multi-profile
 * handling, pagination, and the graceful-degradation-on-failure behavior of
 * {@link AwsParameterStoreEnvironmentRepository}. Mirrors
 * AwsSecretsManagerEnvironmentRepositoryTest since both connectors share the same naming
 * convention (see ConnectorNaming).
 */
class AwsParameterStoreEnvironmentRepositoryTest {

	private final SsmClient client = mock(SsmClient.class);

	private final AwsParameterStoreEnvironmentRepository repository =
			new AwsParameterStoreEnvironmentRepository(client, 3);

	@Test
	void fallsBackFromProfileSpecificToBareApplicationPath() {
		stubEmpty("/demo-app/staging");
		stubParams("/demo-app", Map.of("greeting", "aws-app-default"));

		Environment environment = repository.findOne("demo-app", "staging", "master");

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("greeting", "aws-app-default");
		verify(client, times(1)).getParametersByPath(requestFor("/demo-app/staging"));
		verify(client, times(1)).getParametersByPath(requestFor("/demo-app"));
	}

	@Test
	void labelQualifiedPathTakesPrecedenceOverProfileOnly() {
		stubParams("/demo-app/staging/v2", Map.of("greeting", "aws-label-qualified"));

		Environment environment = repository.findOne("demo-app", "staging", "v2");

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("greeting", "aws-label-qualified");
		verify(client, never()).getParametersByPath(requestFor("/demo-app/staging"));
		verify(client, never()).getParametersByPath(requestFor("/demo-app"));
	}

	@Test
	void defaultMasterLabelIsTreatedAsNoLabel() {
		stubParams("/demo-app/staging", Map.of("greeting", "aws-profile-only"));

		repository.findOne("demo-app", "staging", "master");

		// "master" must never be turned into a literal "/master" path segment
		verify(client, never()).getParametersByPath(requestFor("/demo-app/staging/master"));
	}

	@Test
	void multipleProfilesAreTriedMostSpecificRightmostFirst() {
		stubEmpty("/demo-app/us-east");
		stubParams("/demo-app/staging", Map.of("greeting", "aws-staging"));

		Environment environment = repository.findOne("demo-app", "staging,us-east", null);

		assertThat(environment.getPropertySources()).hasSize(1);
		assertThat(sourceOf(environment)).containsEntry("greeting", "aws-staging");
		verify(client, times(1)).getParametersByPath(requestFor("/demo-app/us-east"));
		verify(client, times(1)).getParametersByPath(requestFor("/demo-app/staging"));
		verify(client, never()).getParametersByPath(requestFor("/demo-app"));
	}

	@Test
	void nestedParameterNamesBecomeDottedKeysWithPathPrefixStripped() {
		stubParams("/demo-app/staging", Map.of("db/password", "secret-value", "greeting", "hi"));

		Environment environment = repository.findOne("demo-app", "staging", null);

		assertThat(sourceOf(environment)).containsEntry("db.password", "secret-value").containsEntry("greeting", "hi");
	}

	@Test
	void followsPaginationUntilExhausted() {
		GetParametersByPathResponse page1 = GetParametersByPathResponse.builder()
				.parameters(Parameter.builder().name("/demo-app/staging/a").value("1").build())
				.nextToken("page-2")
				.build();
		GetParametersByPathResponse page2 = GetParametersByPathResponse.builder()
				.parameters(Parameter.builder().name("/demo-app/staging/b").value("2").build())
				.build();
		when(client.getParametersByPath(requestForFirstPage("/demo-app/staging"))).thenReturn(page1);
		when(client.getParametersByPath(requestForPage("/demo-app/staging", "page-2"))).thenReturn(page2);

		Environment environment = repository.findOne("demo-app", "staging", null);

		assertThat(sourceOf(environment)).containsEntry("a", "1").containsEntry("b", "2");
	}

	@Test
	void requestsAreRecursiveAndDecrypted() {
		stubParams("/demo-app/staging", Map.of("greeting", "hi"));

		repository.findOne("demo-app", "staging", null);

		verify(client, times(1)).getParametersByPath(recursiveDecryptedRequest());
	}

	@Test
	void aRealFailureDegradesToNoPropertySourceInsteadOfThrowing() {
		when(client.getParametersByPath(requestFor("/demo-app/staging")))
				.thenThrow(SdkClientException.create("simulated network failure"));

		Environment environment = repository.findOne("demo-app", "staging", null);

		assertThat(environment.getPropertySources()).isEmpty();
		// stops after the first real failure rather than hammering every remaining candidate
		verify(client, times(1)).getParametersByPath(requestFor("/demo-app/staging"));
		verify(client, never()).getParametersByPath(requestFor("/demo-app"));
	}

	private void stubParams(String path, Map<String, String> nameSuffixToValue) {
		var parameters = nameSuffixToValue.entrySet().stream()
				.map(e -> Parameter.builder().name(path + "/" + e.getKey()).value(e.getValue()).build())
				.toList();
		when(client.getParametersByPath(requestFor(path)))
				.thenReturn(GetParametersByPathResponse.builder().parameters(parameters).build());
	}

	private void stubEmpty(String path) {
		when(client.getParametersByPath(requestFor(path)))
				.thenReturn(GetParametersByPathResponse.builder().build());
	}

	private static GetParametersByPathRequest requestFor(String path) {
		return argThat(request -> request != null && path.equals(request.path()));
	}

	private static GetParametersByPathRequest requestForFirstPage(String path) {
		return argThat(request -> request != null && path.equals(request.path()) && request.nextToken() == null);
	}

	private static GetParametersByPathRequest requestForPage(String path, String nextToken) {
		return argThat(request -> request != null && path.equals(request.path())
				&& nextToken.equals(request.nextToken()));
	}

	private static GetParametersByPathRequest recursiveDecryptedRequest() {
		return argThat(request -> request != null && Boolean.TRUE.equals(request.recursive())
				&& Boolean.TRUE.equals(request.withDecryption()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> sourceOf(Environment environment) {
		return (Map<String, Object>) environment.getPropertySources().get(0).getSource();
	}
}
