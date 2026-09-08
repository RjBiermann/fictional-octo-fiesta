import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        maven("$rootDir/vendor") // vendored recloudstream gradle plugin (jitpack -SNAPSHOT paths 404 today)
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        // ponytail: vendored the cloudstream gradle plugin jar — jitpack's -SNAPSHOT
        // metadata no longer resolves under Gradle 8.12 (file lives in a non-standard
        // version dir); replace this file when upstream publishes a fix.
        classpath(files("gradlelibs/cs-plugin-facade.jar"))
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
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
        implementation("org.mozilla:rhino:1.9.1") // JS engine (JavGuru, Javseen)
        implementation("org.jspecify:jspecify:1.0.1") // annotations referenced by jsoup
        testImplementation("junit:junit:4.13.2") // TDD-first, ADR-0005
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}