package com.sarvika.configserver.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.core.Ordered;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom EnvironmentRepository proving that a platform with no built-in Spring Cloud
 * Config support (AWS Secrets Manager) can be plugged in with nothing more than this
 * class + an {@code EnvironmentRepository} bean registration - no fork of the library,
 * no SPI factory needed.
 *
 * Secret name convention, most specific to least specific:
 * "{application}-{profile}-{label}", "{application}-{profile}", "{application}-{label}",
 * "{application}". The label segment is omitted when no label is requested or the label
 * is the default "master". When multiple comma-separated profiles are requested, each is
 * tried in turn from most specific (rightmost) to least specific, per Spring profile
 * precedence. The first secret found wins; its value is expected to be a flat JSON
 * object, which is flattened into dotted property keys.
 */
public class AwsSecretsManagerEnvironmentRepository implements EnvironmentRepository, Ordered {

	private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerEnvironmentRepository.class);

	private final SecretsManagerClient client;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final int order;

	public AwsSecretsManagerEnvironmentRepository(SecretsManagerClient client, int order) {
		this.client = client;
		this.order = order;
	}

	@Override
	public Environment findOne(String application, String profile, String label) {
		List<String> profiles = ConnectorNaming.splitProfiles(profile);
		String resolvedLabel = ConnectorNaming.normalizeLabel(label);

		Environment environment = new Environment(application, profiles.toArray(new String[0]), label, null, null);

		for (String candidate : candidateSecretNames(application, profiles, resolvedLabel)) {
			Map<String, Object> secretValues;
			try {
				secretValues = fetchSecret(candidate);
			}
			catch (RuntimeException e) {
				// A real failure (timeout, auth, throttling, malformed secret) rather than
				// "not found" - trying the remaining, less-specific candidates would just hit
				// the same broken client/endpoint again. Log loudly and let this connector's
				// contribution degrade to empty rather than blocking or failing the whole
				// merged environment response for the other (working) connectors.
				log.warn("AWS Secrets Manager lookup failed for secret '{}' (application={}, profile={}, label={}); "
						+ "skipping this connector for this request", candidate, application, profile, label, e);
				break;
			}
			if (!secretValues.isEmpty()) {
				environment.add(new PropertySource("aws-secrets-manager:" + candidate, secretValues));
				break;
			}
		}

		return environment;
	}

	private static List<String> candidateSecretNames(String application, List<String> profiles, String label) {
		List<String> mostSpecificFirst = ConnectorNaming.mostSpecificFirst(profiles);

		List<String> candidates = new ArrayList<>();
		if (label != null) {
			for (String p : mostSpecificFirst) {
				candidates.add(application + "-" + p + "-" + label);
			}
		}
		for (String p : mostSpecificFirst) {
			candidates.add(application + "-" + p);
		}
		if (label != null) {
			candidates.add(application + "-" + label);
		}
		candidates.add(application);
		return candidates;
	}

	private Map<String, Object> fetchSecret(String secretName) {
		try {
			var response = client.getSecretValue(GetSecretValueRequest.builder()
					.secretId(secretName)
					.build());
			Map<String, Object> raw = objectMapper.readValue(response.secretString(), Map.class);
			Map<String, Object> flattened = new LinkedHashMap<>();
			flatten("", raw, flattened);
			return flattened;
		}
		catch (ResourceNotFoundException e) {
			return Map.of();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read secret '" + secretName + "' from AWS Secrets Manager", e);
		}
	}

	@SuppressWarnings("unchecked")
	private void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			if (entry.getValue() instanceof Map<?, ?>) {
				flatten(key, (Map<String, Object>) entry.getValue(), target);
			}
			else {
				target.put(key, entry.getValue());
			}
		}
	}

	@Override
	public int getOrder() {
		return this.order;
	}
}
