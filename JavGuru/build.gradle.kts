// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.
version = 18

cloudstream {
    authors     = listOf("kraptor", "ByAyzen")
    language    = "en"
    description = "JavGuru"
    status  = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=jav.guru&sz=%size%"
}
android {
    // Shared extractors (JavGuru/Javseen/Mangoporn/XXXParodyHD) compiled into this plugin
    sourceSets.getByName("main").kotlin.srcDir(rootDir.resolve("shared/src/main/kotlin"))
}
