/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    `java-library`
    `maven-publish`
}

description = "An experimental Smithy build plugin that renders a service model as a single, self-contained HTML API explorer."

val smithyVersion: String by project

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

dependencies {
    implementation("software.amazon.smithy:smithy-model:$smithyVersion")
    implementation("software.amazon.smithy:smithy-build:$smithyVersion")
    implementation("software.amazon.smithy:smithy-diff:$smithyVersion")

    // The weather example applies aws.protocols#restJson1.
    testImplementation("software.amazon.smithy:smithy-aws-traits:$smithyVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Records the project version so the generated page can name the generator that produced it.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/api-explorer.properties") {
        expand("version" to version)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // The example tests read examples/ and write the rendered pages under build/examples/.
    inputs.dir("examples")
    systemProperty("apiExplorer.examplesOutput", layout.buildDirectory.dir("examples").get().asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Smithy API Explorer")
                description.set(project.description)
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
