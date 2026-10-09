// Java SDK for the Agent Control Specification (ACS).
//
// It reaches the Rust engine through the C ABI of `policy-engine/sdk/rust` with the Foreign Function & Memory API
// (java.lang.foreign, final since Java 22), so there is no JNI glue and no native code in this project.
//
//   JDK 25 or later builds it (language level 25); run Gradle with JAVA_HOME on that JDK, or pass -Pacs.jdk=<n> to select
//   a toolchain.
//   The native library is built separately (see README.md) and found through -Dacs.native.library=<path>,
//   the ACS_NATIVE_LIBRARY environment variable, or the resource /native/<os>-<arch>/ in the jar.

plugins {
    `java-library`
    `maven-publish`
}

group = "com.microsoft.agentgovernance"
version = "0.1.0-SNAPSHOT"

val jdk = providers.gradleProperty("acs.jdk").orNull?.toInt()

java {
    if (jdk != null) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(jdk))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON on the wire: the engine speaks JSON, the JDK has no JSON API.
    api("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "com.microsoft.agentgovernance.acs",
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

val nativeLibrary = providers.gradleProperty("acs.native.library").orElse(providers.environmentVariable("ACS_NATIVE_LIBRARY"))

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // the real-library tests are skipped when this is not set (see NativeAvailability in the tests)
    nativeLibrary.orNull?.let { systemProperty("acs.native.library", it) }
    // repository root of the policy engine, for the conformance corpus
    systemProperty("acs.policy.engine.dir", layout.projectDirectory.dir("../..").asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Agent Control Specification Java SDK")
                description.set("Java host API for the Agent Control Specification engine, over its Rust C ABI (Foreign Function & Memory API).")
                url.set("https://github.com/microsoft/agent-governance-toolkit")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
