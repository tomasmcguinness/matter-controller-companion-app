package com.tomasmcguinness.mcc.data

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

/**
 * A Matter controller (hub) the user has added to MCC.
 *
 * Purely local metadata the user typed in -- there's no pairing and no token, just a name and
 * the base address to reach it at.
 */
@Serializable
data class Controller(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    /**
     * The username this controller signs in to the push relay with. The matching password is
     * in [RelayCredentialStore]; null when notifications aren't set up for this controller.
     */
    val relayUsername: String? = null,
) {
    companion object {
        /**
         * Parses free-form text into a connection URL, requiring an http/https scheme and a
         * non-empty host. Returns null if the text isn't a usable address.
         */
        fun connectionUrl(text: String): String? {
            val trimmed = text.trim()

            if (trimmed.isEmpty()) return null

            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                return null
            }

            val scheme = uri.scheme?.lowercase()

            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty()) return null

            return trimmed
        }
    }
}
