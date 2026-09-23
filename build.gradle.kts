import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins —
        // original source: com.github.recloudstream.gradle:gradle via jitpack
        // (-SNAPSHOT metadata resolves under Gradle 9.7.1 as of 2026-09-12; the
        // earlier vendoring existed only for the then-broken jitpack -SNAPSHOT path).
        classpath("com.github.recloudstream.gradle:gradle:-SNAPSHOT")
        // Pinned: CodeQL's Kotlin extractor cannot parse newer versions;
        // codeql.yml downgrades to 2.4.10 to build (ADR-0010). Dependabot ignores this.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // com.lagradost:cloudstream3:pre-release is not on jitpack or Maven
        // Central; bootstrapCloudstream installs the official release asset into
        // the local Maven repo. mavenLocal() is LAST so it can only backfill,
        // never shadow.
        mavenLocal()
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        // builds are published to Codeberg, not GitHub — pass a codeberg URL so plugins.json
        // gets codeberg raw links (upstream setRepo defaults bare slugs to github)
        setRepo("https://codeberg.org/${System.getenv("GITHUB_REPOSITORY") ?: "RjBiermann/fictional-octo-fiesta"}")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        // Unit tests (ADR-0005): shared test sources splice into every provider,
        // so `gradlew <Provider>:test` runs them all. Same pattern providers use for main.
        sourceSets.getByName("test").kotlin.srcDir(rootDir.resolve("shared/src/test/kotlin"))
        // shared test fixtures land on every provider's test classpath too
        sourceSets.getByName("test").resources.srcDir(rootDir.resolve("shared/src/test/resources"))
        // Host registry + shared adapters (ADR-0002) compile into every provider.
        sourceSets.getByName("main").kotlin.srcDir(rootDir.resolve("shared/src/main/kotlin"))

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val testImplementation by configurations
        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // HTTP Lib
        implementation("org.jsoup:jsoup:1.23.2") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
        implementation("org.mozilla:rhino:1.9.1") // JS engine (shared Filemoon extractor)
        implementation("org.jspecify:jspecify:1.0.1") // annotations referenced by jsoup
        testImplementation("junit:junit:4.13.2") // TDD-first, ADR-0005
        // Issue #353: HostRegistryTest instantiates framework extractor rows
        // (Voe, StreamTape, …) to pin the supersession/naming invariants — the
        // framework classes live on the `cloudstream` configuration only.
        // Constructors are pure field assignments, JVM-test safe. The two
        // kotlinx-serialization jars satisfy Voe's @Serializable companions.
        testImplementation(files(configurations.getByName("cloudstream")))
        testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0")
        testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    }

}

// Cold-runner bootstrap for the com.lagradost:cloudstream3:pre-release dependency.
// The :cloudstream configuration carries that maven dependency and resolves it
// from the repositories — but the coordinate is not on jitpack or Maven Central;
// upstream publishes the framework classes only as the `pre-release` release
// asset. On a warm Gradle cache the resolved artifact survived; PR #400 changed
// the cache key and the first cold run failed (2026-09-12, run 34716659549).
// Fix: fetch classes.jar from the official release asset, verify its digest, and
// install it (plus a stub POM) into mavenLocal (~/.m2), which allprojects already
// searches LAST (backfill only, never shadow). The digest pins the moving
// `pre-release` tag: an upstream update fails loudly here — bump the digest as a
// deliberate, reviewable change.
tasks.register("bootstrapCloudstream") {
    group = "build setup"
    description = "Fetches the official cloudstream3:pre-release classes.jar and installs it into mavenLocal"
    // sha256 of the classes.jar served by the `pre-release` tag as of 2026-09-18
    // (#444 run: upstream refreshed the moving tag; new jar in mavenLocal compiles all
    // 24 providers unchanged — reviewed via full clean build of every subproject)
    val expectedSha = "e984bf17ee840843bcc425176cf9fab448b36491274c7f7681609ec417accdd1"
    val url = "https://github.com/recloudstream/cloudstream/releases/download/pre-release/classes.jar"
    val mavenLocalDir = file(System.getProperty("user.home")).resolve(".m2/repository")
    val dest = mavenLocalDir.resolve("com/lagradost/cloudstream3/pre-release")
    outputs.files(
        dest.resolve("cloudstream3-pre-release.jar"),
        dest.resolve("cloudstream3-pre-release.pom"),
    )
    doLast {
        dest.mkdirs()
        val jarFile = dest.resolve("cloudstream3-pre-release.jar")
        java.net.URL(url).openStream().use { input ->
            jarFile.outputStream().use { output -> input.copyTo(output) }
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        jarFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedSha) {
            "cloudstream3:pre-release classes.jar digest changed (upstream updated the pre-release tag):\n" +
                "  expected $expectedSha\n  actual   $actual\n" +
                "Review the upstream diff, then bump expectedSha in root build.gradle.kts — review is the gate."
        }
        dest.resolve("cloudstream3-pre-release.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.lagradost</groupId>" +
                "<artifactId>cloudstream3</artifactId><version>pre-release</version></project>"
        )
        logger.lifecycle("bootstrapCloudstream: $url -> $jarFile")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}