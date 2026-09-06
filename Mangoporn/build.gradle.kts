version = 27
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
}

cloudstream {
    authors     = listOf("kraptor", "ByAyzen", "HindiProvider")
    language    = "en"
    description = "MangoPorn"
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://mangoporn.net/wp-content/uploads/2024/07/mangoporn.net_.png"
}
android {
    // Shared extractors (JavGuru/Javseen/Mangoporn/XXXParodyHD) compiled into this plugin
    sourceSets.getByName("main").kotlin.srcDir(rootDir.resolve("shared/src/main/kotlin"))
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
}
