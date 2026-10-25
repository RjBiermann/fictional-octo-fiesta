// A build file with no plugin version line: the bumper must fail loudly instead of
// committing a no-op (a silent skip hides a broken provider subproject).
cloudstream {
    authors     = listOf("kraptor")
    language    = "en"
    description = "No version line here."
    tvTypes = listOf("NSFW")
}
