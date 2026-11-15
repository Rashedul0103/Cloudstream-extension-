// Use an integer for version numbers; bump it on every release so CloudStream offers the update.
version = 23

cloudstream {
    description = "Combine any number of Stremio addons: merged catalogs, search, metadata, " +
        "subtitles and streams, with filtering, sorting and a no-code visual stream-label designer. " +
        "Open the plugin settings to add addons."
    authors = listOf("MultiStremio")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 3

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Live", "Others")

    requiresResources = false
    language = "en"

    iconUrl = "https://raw.githubusercontent.com/Stremio/stremio-web/development/images/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Compile against the Activity Result API supplied by CloudStream's AndroidX host app.
    compileOnly("androidx.activity:activity:1.9.3")
    // Linear-time matching prevents user-defined stream filters from stalling playback.
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("junit:junit:4.13.2")
}


tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped", "standardOut")
        showStandardStreams = true
    }
}
