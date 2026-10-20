plugins {
    java
}

group = "io.github.r0xll"
// Overridable from CI so the jar name matches the release tag, e.g.
// `./gradlew build -PreleaseVersion=1.2.3`. Defaults to the baseline version.
version = (findProperty("releaseVersion") as String? ?: "1.1.0")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Rule 9: reference the Montoya API, provided by Burp at runtime (not bundled).
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
    testImplementation("net.portswigger.burp.extensions:montoya-api:2026.7")

    // Rule 2: bundled runtime dependencies (see fat-jar config below).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Rule 2: build a single self-contained jar bundling all runtime dependencies
// (jackson + snakeyaml) so users get one-click install, while montoya-api stays
// compileOnly and is NOT bundled (Burp provides it).
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Drop signature files and module descriptors. We also drop the dependencies'
    // own META-INF/LICENSE|NOTICE because, merged into one jar, only one of each
    // colliding path would survive; instead we ship our own curated notices below
    // so the Apache-2.0 attribution for every bundled component is complete.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/LICENSE*", "META-INF/NOTICE*")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")

    // Apache-2.0 compliance: carry the license and the third-party notices for
    // the bundled dependencies (Jackson, SnakeYAML) inside the distributed jar.
    from(listOf("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.txt")) { into("META-INF") }
}

tasks.build {
    dependsOn(tasks.jar)
}
