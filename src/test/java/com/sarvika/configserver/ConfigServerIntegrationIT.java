package com.sarvika.configserver;

import com.github.dockerjava.api.model.Capability;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the whole pluggable-connector pattern end to end against real backend instances
 * (real OpenBao, real Moto for both AWS services, a real local git repo) instead of mocks
 * - the same thing verified manually, by hand, dozens of times over the course of this
 * project. This is that verification made permanent.
 *
 * Deliberately separate from the fast mocked unit tests (*Test.java, run by surefire during
 * `mvn test`, on every push): this class is named *IT.java and only runs during `mvn verify`
 * (via failsafe), since it needs Docker and takes meaningfully longer.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerIntegrationIT {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@TempDir
	static Path gitRepoDir;

	@Container
	static final GenericContainer<?> openbao = new GenericContainer<>(DockerImageName.parse("openbao/openbao:latest"))
			.withExposedPorts(8200)
			.withEnv("BAO_DEV_ROOT_TOKEN_ID", "root")
			.withEnv("BAO_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
			.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.IPC_LOCK))
			.withCommand("server", "-dev")
			.waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

	@Container
	static final GenericContainer<?> moto = new GenericContainer<>(DockerImageName.parse("motoserver/moto:latest"))
			.withExposedPorts(5000)
			.waitingFor(Wait.forHttp("/").forStatusCode(200));

	@org.springframework.beans.factory.annotation.Autowired
	private TestRestTemplate restTemplate;

	/**
	 * Seeds every backend (git repo + Vault + both Moto-backed AWS services) from inside
	 * this method rather than a separate @BeforeAll: Spring guarantees @DynamicPropertySource
	 * runs after the containers are up but strictly before the ApplicationContext is
	 * created - and that ordering matters here, because the actuator health indicator
	 * eagerly probes every EnvironmentRepository as part of context startup. A plain
	 * @BeforeAll method has no such guarantee relative to context creation, so seeding
	 * there risked a race where the health check's first findOne() call could run before
	 * the backends actually had anything in them.
	 */
	@DynamicPropertySource
	static void registerConnectorPropertiesAndSeedBackends(DynamicPropertyRegistry registry) {
		try {
			seedGitRepo();
			seedVault();
			seedSecretsManager();
			seedParameterStore();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to seed integration test backends", e);
		}

		registry.add("VAULT_HOST", openbao::getHost);
		registry.add("VAULT_PORT", () -> openbao.getMappedPort(8200));
		registry.add("VAULT_SCHEME", () -> "http");
		registry.add("VAULT_TOKEN", () -> "root");

		String motoUrl = "http://" + moto.getHost() + ":" + moto.getMappedPort(5000);
		registry.add("AWS_SECRETSMANAGER_ENDPOINT", () -> motoUrl);
		registry.add("AWS_SECRETSMANAGER_REGION", () -> "us-east-1");
		registry.add("AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER", () -> "static");
		registry.add("AWS_SECRETSMANAGER_ACCESS_KEY", () -> "test");
		registry.add("AWS_SECRETSMANAGER_SECRET_KEY", () -> "test");

		registry.add("AWS_PARAMETERSTORE_ENDPOINT", () -> motoUrl);
		registry.add("AWS_PARAMETERSTORE_REGION", () -> "us-east-1");
		registry.add("AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER", () -> "static");
		registry.add("AWS_PARAMETERSTORE_ACCESS_KEY", () -> "test");
		registry.add("AWS_PARAMETERSTORE_SECRET_KEY", () -> "test");

		registry.add("GIT_REPO_URI", () -> "file://" + gitRepoDir);
		registry.add("SECURITY_PASSWORD", () -> "test");
	}

	@Test
	@Order(1)
	void mergesAllFourRealConnectorsWithCorrectPrecedence() {
		ResponseEntity<String> response = restTemplate.withBasicAuth("root", "test")
				.getForEntity("/demo-app-staging.yml", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String body = response.getBody();
		assertThat(body).contains("source: vault"); // Vault (order 1) wins over Git's default
		assertThat(body).contains("greeting: hello-from-parameter-store"); // AWS Parameter Store (order 3)
		assertThat(body).contains("token: moto-secret-token-123"); // AWS Secrets Manager (order 2)
		assertThat(body).contains("port: 9000"); // Git (order 4, lowest) - only source for this key
	}

	@Test
	@Order(2)
	void degradesGracefullyInsteadOfFailingWhenVaultBecomesUnreachable() {
		openbao.stop();

		ResponseEntity<String> response = restTemplate.withBasicAuth("root", "test")
				.getForEntity("/demo-app-staging.yml", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String body = response.getBody();
		assertThat(body).doesNotContain("source: vault"); // Vault's contribution is gone...
		assertThat(body).contains("greeting: hello-from-parameter-store"); // ...but everything else
		assertThat(body).contains("token: moto-secret-token-123"); // still works normally
		assertThat(body).contains("port: 9000");
	}

	private static void seedGitRepo() throws Exception {
		runGit(gitRepoDir, "init", "-q", "-b", "master");
		Files.writeString(gitRepoDir.resolve("demo-app-staging.yml"), "server:\n  port: 9000\n");
		runGit(gitRepoDir, "-c", "user.email=it@example.com", "-c", "user.name=integration-test", "add", "-A");
		runGit(gitRepoDir, "-c", "user.email=it@example.com", "-c", "user.name=integration-test", "commit", "-q",
				"-m", "seed");
	}

	private static void runGit(Path dir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
		if (process.waitFor() != 0) {
			throw new IllegalStateException("git command failed: " + command);
		}
	}

	private static void seedVault() throws Exception {
		String vaultUrl = "http://" + openbao.getHost() + ":" + openbao.getMappedPort(8200)
				+ "/v1/secret/data/demo-app,staging";
		postJson(vaultUrl, Map.of("X-Vault-Token", "root"), "{\"data\":{\"demo.source\":\"vault\"}}");
	}

	private static void seedSecretsManager() throws Exception {
		String motoUrl = "http://" + moto.getHost() + ":" + moto.getMappedPort(5000) + "/";
		postJson(motoUrl,
				Map.of("X-Amz-Target", "secretsmanager.CreateSecret",
						"Content-Type", "application/x-amz-json-1.1",
						"Authorization", "AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/secretsmanager/"
								+ "aws4_request, SignedHeaders=host, Signature=dummy"),
				"{\"Name\":\"demo-app-staging\",\"SecretString\":"
						+ "\"{\\\"external.token\\\":\\\"moto-secret-token-123\\\"}\"}");
	}

	private static void seedParameterStore() throws Exception {
		String motoUrl = "http://" + moto.getHost() + ":" + moto.getMappedPort(5000) + "/";
		postJson(motoUrl,
				Map.of("X-Amz-Target", "AmazonSSM.PutParameter",
						"Content-Type", "application/x-amz-json-1.1",
						"Authorization", "AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/ssm/"
								+ "aws4_request, SignedHeaders=host, Signature=dummy"),
				"{\"Name\":\"/demo-app/staging/greeting\",\"Value\":\"hello-from-parameter-store\","
						+ "\"Type\":\"String\",\"Overwrite\":true}");
	}

	private static void postJson(String url, Map<String, String> headers, String body) throws Exception {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
				.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(requestBuilder::header);
		HttpResponse<String> response = HTTP.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 300) {
			throw new IllegalStateException("Seeding call to " + url + " failed: " + response.statusCode() + " "
					+ response.body());
		}
	}
}
