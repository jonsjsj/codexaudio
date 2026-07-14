package no.bellaybestia.audex.auth

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
 * Verified against ABS 2.35.1 (server/Auth.js + auth/OidcAuthStrategy.js):
 *  - Authorization: GET {base}/auth/openid with response_type=code (only `code`
 *    is accepted), redirect_uri = our custom scheme, state, and PKCE
 *    code_challenge + code_challenge_method=S256 (S256 is REQUIRED for the
 *    mobile flow — plain is rejected). `client_id`/`nonce`/`scope` are set
 *    server-side from the ABS OIDC config, so the app must NOT send them.
 *  - ABS runs the whole dance with the IdP, then bounces the code back to our
 *    scheme via {base}/auth/openid/mobile-redirect →
 *    audiobookshelf://oauth?code=…&state=…
 *  - Token exchange: GET {base}/auth/openid/callback?code=…&state=…&code_verifier=…
 *    returns the login payload (AbsLoginResponse) with user.accessToken and
 *    user.refreshToken. The redirect scheme must be whitelisted in each ABS
 *    server's "Allowed Mobile Redirect URLs".
 */
object AbsOidcFlow {

    // Reuse the STANDARD Audiobookshelf mobile scheme: every ABS server ships
    // `audiobookshelf://oauth` in its default authOpenIDMobileRedirectURIs, so
    // Audex logs in against any server with zero server-side config (unlike a
    // custom scheme, which each server would have to whitelist). Trade-off: if
    // the official ABS app is also installed, Android shows an app chooser on
    // the OAuth redirect — a non-issue since Audex replaces it.
    const val REDIRECT_SCHEME = "audiobookshelf"
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

    /**
     * The token-exchange URL called after catching the custom-scheme redirect.
     * Carries the PKCE `code_verifier` matching the challenge sent above; the
     * response is an [no.bellaybestia.audex.network.abs.AbsLoginResponse].
     */
    fun tokenExchangeUrl(baseUrl: String, code: String, state: String, codeVerifier: String): Uri =
        Uri.parse(baseUrl.trimEnd('/'))
            .buildUpon()
            .appendPath("auth")
            .appendPath("openid")
            .appendPath("callback")
            .appendQueryParameter("code", code)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_verifier", codeVerifier)
            .build()

    data class Callback(val code: String, val state: String)

    /** Parse the audiobookshelf://oauth?code=…&state=… redirect. */
    fun parseCallback(uri: Uri): Callback? {
        if (uri.scheme != REDIRECT_SCHEME) return null
        val code = uri.getQueryParameter("code") ?: return null
        val state = uri.getQueryParameter("state") ?: return null
        return Callback(code, state)
    }
}
