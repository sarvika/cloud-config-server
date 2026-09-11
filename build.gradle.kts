plugins {
	java
	id("org.springframework.boot") version "3.3.13"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.sarvika"
version = "0.0.1-SNAPSHOT"
description = "Config Server with pluggable connectors (Git, OpenBao, AWS Secrets Manager, AWS Parameter Store)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

val springCloudVersion = "2023.0.6"
val testcontainersVersion = "1.21.4"

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
		mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
	}
}

dependencies {
	implementation("org.springframework.cloud:spring-cloud-config-server")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("software.amazon.awssdk:secretsmanager:2.28.29")
	implementation("software.amazon.awssdk:ssm:2.28.29")
	implementation("com.fasterxml.jackson.core:jackson-databind")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.testcontainers:testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Mirrors the Maven Surefire/Failsafe split this project used before migrating off Maven:
// *Test.java (fast, mocked, no Docker) run via the plain `test` task; *IT.java (the
// Testcontainers-backed suite, needs Docker) run only via the separate `integrationTest`
// task below - so `./gradlew test` stays fast and CI's two jobs map directly onto these
// two tasks.
tasks.named<Test>("test") {
	exclude("**/*IT.class")
}

tasks.register<Test>("integrationTest") {
	description = "Runs the Testcontainers-backed integration suite (*IT.java) - needs Docker."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	include("**/*IT.class")
	shouldRunAfter("test")
}
