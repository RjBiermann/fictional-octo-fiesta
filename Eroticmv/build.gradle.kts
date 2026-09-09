version = 4

cloudstream {
    authors     = listOf("imperialbob")
    language    = "en"
    description = "Softcore erotic adult movies, classic and modern, full in HD on eroticmv.com"
    status      = 1
    tvTypes     = listOf("NSFW")

    iconUrl = "https://www.google.com/s2/favicons?domain=eroticmv.com&sz=%size%"
}

afterEvaluate {
    dependencies {
        // unit tests need the cloudstream stub classes on the runtime classpath;
        // the gradle plugin caches the stub jar here (downloaded by the make task)
        val stub = File(System.getProperty("user.home"), ".gradle/caches/cloudstream/cloudstream/cloudstream.jar")
        if (stub.exists()) testImplementation(files(stub))
    }
}
