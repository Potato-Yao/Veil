plugins {
    `java-library`
    `maven-publish`
}

group = "com.potato"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.postgresql:postgresql:42.7.11")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

sourceSets {
    create("examples") {
        compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "Veil"
                description = "A Java file management library"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    forkEvery = 1
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Runs the example application"
    classpath = sourceSets["examples"].runtimeClasspath
    mainClass.set("com.potato.examples.ExampleMain")
}