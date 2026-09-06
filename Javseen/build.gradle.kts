// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.
version = 9

cloudstream {
    authors     = listOf("byayzen")
    language    = "jp"
    description = "Watch Free JAV Sex Movies Streaming, Japanese Adult Videos, Tons of hot jav censored,Japanese tube, Japanese sex online."
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://javseen.tv/favicon-96x96.png"
}
android {
    // Shared extractors (JavGuru/Javseen/Mangoporn/XXXParodyHD) compiled into this plugin
    sourceSets.getByName("main").kotlin.srcDir(rootDir.resolve("shared/src/main/kotlin"))
}
