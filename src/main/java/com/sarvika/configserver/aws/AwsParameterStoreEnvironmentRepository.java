package com.sarvika.configserver.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.core.Ordered;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom EnvironmentRepository for AWS Systems Manager Parameter Store - like
 * AwsSecretsManagerEnvironmentRepository, this proves a platform with no built-in Spring
 * Cloud Config support can be plugged in with nothing more than this class + a bean
 * registration.
 *
 * Path convention, most specific to least specific: "/{application}/{profile}/{label}",
 * "/{application}/{profile}", "/{application}/{label}", "/{application}". The label
 * segment is omitted when no label is requested or the label is the default "master".
 * When multiple comma-separated profiles are requested, each is tried in turn from most
 * specific (rightmost) to least specific, per Spring profile precedence. The first path
 * with any parameters under it wins - all parameters found there (recursively, decrypting
 * SecureString values) become the property source, with each parameter's remaining path
 * segments (after the matched prefix) joined with "." as the property key, e.g.
 * "/demo-app/staging/db/password" under path "/demo-app/staging" becomes "db.password".
 */
public class AwsParameterStoreEnvironmentRepository implements EnvironmentRepository, Ordered {

	private static final Logger log = LoggerFactory.getLogger(AwsParameterStoreEnvironmentRepository.class);

	private final SsmClient client;
	private final int order;

	public AwsParameterStoreEnvironmentRepository(SsmClient client, int order) {
		this.client = client;
		this.order = order;
	}

	@Override
	public Environment findOne(String application, String profile, String label) {
		List<String> profiles = ConnectorNaming.splitProfiles(profile);
		String resolvedLabel = ConnectorNaming.normalizeLabel(label);

		Environment environment = new Environment(application, profiles.toArray(new String[0]), label, null, null);

		for (String path : candidatePaths(application, profiles, resolvedLabel)) {
			Map<String, Object> parameters;
			try {
				parameters = fetchParametersUnderPath(path);
			}
			catch (RuntimeException e) {
				// A real failure (timeout, auth, throttling) rather than "nothing there" -
				// trying the remaining, less-specific candidates would just hit the same
				// broken client/endpoint again. Log loudly and let this connector's
				// contribution degrade to empty rather than blocking or failing the whole
				// merged environment response for the other (working) connectors.
				log.warn("AWS Parameter Store lookup failed for path '{}' (application={}, profile={}, label={}); "
						+ "skipping this connector for this request", path, application, profile, label, e);
				break;
			}
			if (!parameters.isEmpty()) {
				environment.add(new PropertySource("aws-parameter-store:" + path, parameters));
				break;
			}
		}

		return environment;
	}

	private static List<String> candidatePaths(String application, List<String> profiles, String label) {
		List<String> mostSpecificFirst = ConnectorNaming.mostSpecificFirst(profiles);

		List<String> candidates = new ArrayList<>();
		if (label != null) {
			for (String p : mostSpecificFirst) {
				candidates.add("/" + application + "/" + p + "/" + label);
			}
		}
		for (String p : mostSpecificFirst) {
			candidates.add("/" + application + "/" + p);
		}
		if (label != null) {
			candidates.add("/" + application + "/" + label);
		}
		candidates.add("/" + application);
		return candidates;
	}

	private Map<String, Object> fetchParametersUnderPath(String path) {
		try {
			Map<String, Object> flattened = new LinkedHashMap<>();
			String nextToken = null;
			do {
				GetParametersByPathRequest.Builder request = GetParametersByPathRequest.builder()
						.path(path)
						.recursive(true)
						.withDecryption(true);
				if (nextToken != null) {
					request.nextToken(nextToken);
				}
				GetParametersByPathResponse response = client.getParametersByPath(request.build());
				for (Parameter parameter : response.parameters()) {
					flattened.put(toPropertyKey(path, parameter.name()), parameter.value());
				}
				nextToken = response.nextToken();
			}
			while (nextToken != null && !nextToken.isBlank());
			// No exception for "nothing under this path" - GetParametersByPath simply
			// returns an empty list, which the findOne() candidate loop already treats
			// the same way ResourceNotFoundException is treated for Secrets Manager: move
			// on to the next, less-specific candidate.
			return flattened;
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read parameters under path '" + path
					+ "' from AWS Parameter Store", e);
		}
	}

	private static String toPropertyKey(String path, String parameterName) {
		String remainder = parameterName.substring(path.length());
		if (remainder.startsWith("/")) {
			remainder = remainder.substring(1);
		}
		return remainder.replace('/', '.');
	}

	@Override
	public int getOrder() {
		return this.order;
	}
}
