package no.bellaybestia.codexaudio.auth

import android.net.Uri

/**
 * Builders for ABS's native OIDC mobile flow (docs/05 diagram a).
 *
 * The app opens {base}/auth/openid in a Custom Tab (NEVER a WebView — Custom
 * Tabs share the default browser's Authentik session cookie, which is what
 * makes login on the second..nth server a silent redirect). ABS bounces the
 * authorization code back through {base}/auth/openid/mobile-redirect to our
 * custom scheme, which must be whitelisted in the server's
 * "Allowed Mobile Redirect URLs" setting.
 *
 * Exact query parameters and PKCE handling are [verify]-flagged in
 * docs/03 §3.1 and docs/08 — confirm against the deployed ABS version before
 * finishing Phase 1.
 */
object AbsOidcFlow {

    const val REDIRECT_SCHEME = "codexaudio"
    const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth"

    /** The URL to open in a Custom Tab to start login against one server. */
    fun authorizationUrl(baseUrl: String, state: String, codeChallenge: String): Uri =
        Uri.parse(baseUrl.trimEnd('/'))
            .buildUpon()
            .appendPath("auth")
            .appendPath("openid")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    data class Callback(val code: String, val state: String)

    /** Parse the codexaudio://oauth?code=…&state=… redirect. */
    fun parseCallback(uri: Uri): Callback? {
        if (uri.scheme != REDIRECT_SCHEME) return null
        val code = uri.getQueryParameter("code") ?: return null
        val state = uri.getQueryParameter("state") ?: return null
        return Callback(code, state)
    }
}
