version = 16

cloudstream {
    authors     = listOf("kraptor","coxju","SIX")
    language    = "en"
    description = "(VPN) 5 milyondan fazla Porno Videosunu ücretsiz izleyin."
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=xhamster.com&sz=%size%"
}

// Unit tests reference MainAPI (ADR-0005 TDD). The cloudstream gradle plugin downloads
// its stub jar to gradle user home and wires it only as compileOnly — mirror it onto the
// unit-test classpath (single-version cache layout: caches/cloudstream/cloudstream/cloudstream.jar).
dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.3")
    testImplementation(
        files("${System.getenv("GRADLE_USER_HOME") ?: "${System.getProperty("user.home")}/.gradle"}/caches/cloudstream/cloudstream/cloudstream.jar")
    )
}

