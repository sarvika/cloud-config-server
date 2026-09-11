package com.sarvika.configserver.aws;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared "most specific candidate wins" naming convention used by both custom AWS
 * connectors (Secrets Manager, Parameter Store): split a possibly comma-separated
 * profile list into individual profiles ordered most-specific (rightmost) first, and
 * normalize a label so the default "master" label behaves the same as no label at all.
 */
final class ConnectorNaming {

	private ConnectorNaming() {
	}

	static List<String> splitProfiles(String profile) {
		if (profile == null || profile.isBlank()) {
			return List.of();
		}
		return Arrays.stream(profile.split(","))
				.map(String::trim)
				.filter(p -> !p.isEmpty())
				.toList();
	}

	static String normalizeLabel(String label) {
		return (label == null || label.isBlank() || "master".equalsIgnoreCase(label)) ? null : label.trim();
	}

	static List<String> mostSpecificFirst(List<String> profiles) {
		List<String> reversed = new ArrayList<>(profiles);
		Collections.reverse(reversed);
		return reversed;
	}
}
