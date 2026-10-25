// legacy: version = 3 was the pre-fleet value — this comment line is not the version line
version = 7 // #778: shared xvideos registry row (fleet bump)

cloudstream {
    authors     = listOf("kraptor")
    language    = "en"
    description = "Only the first real version line is the plugin version."
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
}

// The framework reads `version` from the plugin line above; version = 9 here is prose.
