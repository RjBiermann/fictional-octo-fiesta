version = 2

cloudstream {
    authors     = listOf("RjBiermann")
    language    = "en"
    description = "Watch Online Porn Full Movie on PandaMovies"
    status  = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=pandamovies.pw&sz=%size%"
}

dependencies {
    // jsoup is `implementation` in root -> not exposed to the unit-test compile classpath.
    testImplementation("org.jsoup:jsoup:1.23.2") // TDD-fixture parser (ADR-0005)
}
