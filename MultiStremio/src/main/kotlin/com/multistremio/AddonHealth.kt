package com.multistremio

data class AddonHealthAssessment(
    val success: Boolean,
    val detail: String,
)

/** Keeps transport errors separate from invalid JSON and structurally invalid manifests. */
object AddonHealthClassifier {
    fun assess(response: SimpleHttpResult, manifest: Manifest?): AddonHealthAssessment {
        if (response.status != SimpleHttpStatus.OK) {
            val detail = when (response.status) {
                SimpleHttpStatus.EMPTY_BODY -> "Server responded but returned an empty manifest"
                SimpleHttpStatus.HTTP_ERROR -> "Server returned HTTP ${response.httpCode ?: "error"}"
                SimpleHttpStatus.TIMEOUT -> "Connection timed out after 8 seconds"
                SimpleHttpStatus.DNS_ERROR -> "Could not resolve the addon host"
                SimpleHttpStatus.INVALID_URL -> "Addon URL is invalid"
                SimpleHttpStatus.NETWORK_ERROR -> "Network connection failed"
                SimpleHttpStatus.OK -> "Unexpected response state"
            }
            return AddonHealthAssessment(false, detail)
        }

        if (manifest == null) {
            return AddonHealthAssessment(false, "Server response is not valid JSON")
        }
        if (manifest.name == null && manifest.resources == null) {
            return AddonHealthAssessment(false, "JSON response is not a valid Stremio addon manifest")
        }
        return AddonHealthAssessment(true, "")
    }
}
