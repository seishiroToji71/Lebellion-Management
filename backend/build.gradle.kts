plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.jpa") version "2.4.20"
}

group = "uz.lebellion"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The app's entry point. Pinned explicitly because the repo also carries a manual `main` (the
// dhashCalibration tool, uz.lebellion.tools.DhashCalibrationKt) in the main source set — without this,
// `resolveMainClassName`/`bootJar` fail with "Unable to find a single main class".
springBoot {
    mainClass.set("uz.lebellion.LebellionApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Required by Spring's Kotlin support (e.g. constructor binding of @ConfigurationProperties).
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 3 (Boot 4 default, package tools.jackson) Kotlin support for data-class DTOs.
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Boot 4 modularized auto-config: spring-boot-flyway provides FlywayAutoConfiguration
    // (runs migrations before the EntityManagerFactory). flyway-core is only the library itself.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core:13.8.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.8.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

// Kotlin classes are final by default; open JPA entities so Hibernate can manage/proxy them.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// dHash calibration helper — NOT a test, NOT wired into `check`/CI. Prints a pairwise Hamming-distance
// matrix for a folder of sample photos so a human can pick per-item thresholds.
//   ./gradlew dhashCalibration            (defaults to docs/samples/photos)
//   ./gradlew dhashCalibration -Pdir=/abs/path/to/photos
tasks.register<JavaExec>("dhashCalibration") {
    group = "help"
    description = "Print a dHash Hamming-distance matrix for docs/samples/photos (manual tool, not CI)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("uz.lebellion.tools.DhashCalibrationKt")
    if (project.hasProperty("dir")) args(project.property("dir").toString())
}
