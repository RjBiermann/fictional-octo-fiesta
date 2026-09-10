// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.
version = 24

cloudstream {
    authors     = listOf("kraptor", "ByAyzen")
    language    = "en"
    description = "JavGuru"
    status  = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=jav.guru&sz=%size%"
}

dependencies {
    // jsoup is `implementation` in root -> not exposed to the unit-test compile classpath.
    testImplementation("org.jsoup:jsoup:1.23.2") // TDD-fixture parser (ADR-0005)
}
