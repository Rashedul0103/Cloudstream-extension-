// Use an integer for version numbers; bump it on every release so CloudStream offers the update.
version = 41
cloudstream {
    description = "Combine any number of Stremio addons: merged catalogs, separate search rows, metadata, " +
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

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Live", "AudioBook", "Others")

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
    // Icon paths use AndroidX's SVG path parser supplied by the CloudStream host app.
    compileOnly("androidx.core:core:1.15.0")
    // Linear-time matching prevents user-defined stream filters from stalling playback.
    implementation("com.google.re2j:re2j:1.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.core:core:1.15.0")
    // Renders Android views on the JVM to produce screenshots of settings pages for review.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
}


tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped", "standardOut")
        showStandardStreams = true
    }
}
