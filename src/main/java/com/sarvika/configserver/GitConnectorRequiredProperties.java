package com.sarvika.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * spring.cloud.config.server.git.uri silently tolerates an unresolved ${GIT_REPO_URI}
 * placeholder until a client request first triggers a git clone, then fails with a
 * plain 404 - not the fail-fast startup error every other required connector setting
 * gets. Binding the same property here via @Value forces Spring's stricter, eager
 * placeholder resolution instead, so a missing GIT_REPO_URI fails at startup like
 * everything else.
 */
@Configuration
@Profile("git")
class GitConnectorRequiredProperties {

	GitConnectorRequiredProperties(@Value("${GIT_REPO_URI}") String gitRepoUri) {
	}
}
