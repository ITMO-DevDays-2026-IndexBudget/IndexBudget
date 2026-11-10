import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    application
    `java-test-fixtures`
}

group = "ru.itmo"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.postgresql:postgresql")
    implementation("com.github.jsqlparser:jsqlparser:5.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testFixturesImplementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass.set("ru.itmo.indexbudget.IndexBudgetApplicationKt")
}

springBoot {
    mainClass.set(application.mainClass)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("reportPreview") {
    group = "verification"
    description = "Generate synthetic HTML previews for visual review (no database access)."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("ru.itmo.indexbudget.fixtures.ReportPreview")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(layout.projectDirectory.file("index.html").asFile.absolutePath,
        layout.buildDirectory.dir("report-previews").get().asFile.absolutePath)
}

// Separate acceptance tools: never included in the installed production CLI.
tasks.register<JavaExec>("exhaustiveOracle") {
    group = "verification"
    description = "Enumerate a fixed space of at most eight candidates with an explicit deadline"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ru.itmo.indexbudget.acceptance.ExhaustiveOracleMainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

tasks.register<JavaExec>("plannerExperiments") {
    group = "verification"
    description = "Compare objectives and search strategies on the same fixed PostgreSQL candidate pool"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ru.itmo.indexbudget.experiments.PlannerExperimentsKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
