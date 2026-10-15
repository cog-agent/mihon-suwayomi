package mihon.suwayomi.auth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

class OidcSessionTest {

    private val env = AuthEnv()
    private val provider get() = env.provider
    private val session get() = env.session

    // region Authorize request

    @Test
    fun `authorize url carries the PKCE and OIDC parameters`() = runBlocking<Unit> {
        val started = env.begin()
        val url = started.url
        url.toString().startsWith("$ISSUER/authorize") shouldBe true
        url.queryParameter("response_type") shouldBe "code"
        url.queryParameter("client_id") shouldBe CLIENT_ID
        url.queryParameter("redirect_uri") shouldBe "app.mihon.suwayomi:/oauth2redirect"
        url.queryParameter("scope") shouldBe TEST_SCOPES
        url.queryParameter("code_challenge_method") shouldBe "S256"
        (url.queryParameter("code_challenge")!!.length >= 43) shouldBe true
        (started.state.length >= 43) shouldBe true
        (started.nonce.length >= 43) shouldBe true
        (started.state != started.nonce) shouldBe true
        url.queryParameter("client_secret") shouldBe null
        url.queryParameter("resource") shouldBe AUDIENCE
        session.state.value.signingIn shouldBe true
    }

    @Test
    fun `audience is sent as resource and offline_access only when configured`() = runBlocking<Unit> {
        val other = AuthEnv(configure = false)
        other.session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, "https://api.test", "openid email $PERMISSION", true))
        val url = other.begin().url
        url.queryParameter("resource") shouldBe "https://api.test"
        url.queryParameter("scope") shouldBe "openid email $PERMISSION offline_access"
    }

    @Test
    fun `without config nothing is requested`() = runBlocking<Unit> {
        val other = AuthEnv(configure = false)
        other.session.beginSignIn() shouldBe SignInStart.Failed(OidcError.NotConfigured)
        other.provider.requests.size shouldBe 0
    }

    // endregion

    // region State, nonce, expiry, replay

    @Test
    fun `successful sign-in stores a session and exposes only the identity`() = runBlocking<Unit> {
        env.signIn() shouldBe RedirectResult.SignedIn
        val state = session.state.value
        state.identity shouldBe OidcIdentity("user-subject-1", "user@example.test", "Test User")
        state.signingIn shouldBe false
        state.toString() shouldNotContain provider.jwt(1)
        session.cachedAccessToken() shouldNotBe null
    }

    @Test
    fun `token request is a public client request with no client secret`() = runBlocking<Unit> {
        val started = env.begin()
        session.handleRedirect(env.redirect(started.state, code = "fake-code-xyz"))
        val form = provider.forms.single { it["grant_type"] == "authorization_code" }
        form["code"] shouldBe "fake-code-xyz"
        form["client_id"] shouldBe CLIENT_ID
        form["redirect_uri"] shouldBe "app.mihon.suwayomi:/oauth2redirect"
        (form["code_verifier"]!!.length >= 43) shouldBe true
        Pkce.challengeS256(form["code_verifier"]!!) shouldBe started.url.queryParameter("code_challenge")
        form.keys.none { it.contains("secret") } shouldBe true
        provider.requests.last { it.url.encodedPath.endsWith("/token") }.header("Authorization") shouldBe null
    }

    @Test
    fun `state mismatch is rejected and does not consume the pending request`() = runBlocking<Unit> {
        val started = env.begin()
        session.handleRedirect(env.redirect("wrong-state")) shouldBe RedirectResult.Ignored(OidcError.StateMismatch)
        session.handleRedirect("app.mihon.suwayomi:/oauth2redirect?code=abc") shouldBe
            RedirectResult.Ignored(OidcError.StateMismatch)
        provider.forms.none { it["grant_type"] == "authorization_code" } shouldBe true
        // The genuine redirect still works.
        session.handleRedirect(env.redirect(started.state)) shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `duplicate parameters are ignored`() = runBlocking<Unit> {
        val started = env.begin()
        session.handleRedirect(env.redirect(started.state) + "&state=other") shouldBe
            RedirectResult.Ignored(OidcError.StateMismatch)
    }

    @Test
    fun `a redirect without a pending request is ignored`() = runBlocking<Unit> {
        session.handleRedirect(env.redirect("any-state")) shouldBe RedirectResult.Ignored(OidcError.NoPendingRequest)
        session.state.value.error shouldBe null
    }

    @Test
    fun `foreign uris are ignored`() = runBlocking<Unit> {
        env.begin()
        session.handleRedirect("https://auth.test/oauth2redirect?code=a&state=b") shouldBe
            RedirectResult.Ignored(OidcError.NoPendingRequest)
        session.handleRedirect("app.mihon.suwayomi://evil/oauth2redirect?code=a") shouldBe
            RedirectResult.Ignored(OidcError.NoPendingRequest)
        session.handleRedirect("app.mihon.suwayomi:/other?code=a") shouldBe
            RedirectResult.Ignored(OidcError.NoPendingRequest)
    }

    @Test
    fun `a replayed redirect is ignored`() = runBlocking<Unit> {
        val started = env.begin()
        val url = env.redirect(started.state)
        session.handleRedirect(url) shouldBe RedirectResult.SignedIn
        session.handleRedirect(url) shouldBe RedirectResult.Ignored(OidcError.NoPendingRequest)
        provider.forms.count { it["grant_type"] == "authorization_code" } shouldBe 1
    }

    @Test
    fun `a request older than ten minutes is rejected`() = runBlocking<Unit> {
        val started = env.begin()
        env.now += 10 * 60 * 1000L + 1
        session.handleRedirect(env.redirect(started.state)) shouldBe RedirectResult.Failed(OidcError.RequestExpired)
        provider.forms.none { it["grant_type"] == "authorization_code" } shouldBe true
        // Single use: it is gone now.
        session.handleRedirect(env.redirect(started.state)) shouldBe RedirectResult.Ignored(OidcError.NoPendingRequest)
    }

    @Test
    fun `a new attempt cancels the previous one`() = runBlocking<Unit> {
        val first = env.begin()
        val second = env.begin()
        session.handleRedirect(env.redirect(first.state)) shouldBe RedirectResult.Ignored(OidcError.StateMismatch)
        session.handleRedirect(env.redirect(second.state)) shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `user cancel and provider errors are reported`() = runBlocking<Unit> {
        var started = env.begin()
        session.handleRedirect("app.mihon.suwayomi:/oauth2redirect?error=access_denied&state=${started.state}") shouldBe
            RedirectResult.Failed(OidcError.AccessDenied)
        session.state.value.error shouldBe OidcError.AccessDenied
        session.state.value.signingIn shouldBe false

        started = env.begin()
        session.handleRedirect(
            "app.mihon.suwayomi:/oauth2redirect?error=server_error&error_description=x&state=${started.state}",
        ) shouldBe RedirectResult.Failed(OidcError.ProviderError)
        session.isSignedIn() shouldBe false
    }

    @Test
    fun `a redirect with no code fails`() = runBlocking<Unit> {
        val started = env.begin()
        session.handleRedirect("app.mihon.suwayomi:/oauth2redirect?state=${started.state}") shouldBe
            RedirectResult.Failed(OidcError.ProviderError)
    }

    // endregion

    // region ID token verification

    private fun failsWith(error: OidcError, build: (nonce: String?) -> String) = runBlocking<Unit> {
        provider.idTokenBuilder = build
        env.signIn() shouldBe RedirectResult.Failed(error)
        session.isSignedIn() shouldBe false
    }

    @Test
    fun `bad signature is rejected`() {
        val impostor = TestKey.rsa("key-1")
        failsWith(OidcError.InvalidToken) { signJwt(impostor, idClaims(it)) }
    }

    @Test
    fun `wrong issuer including a trailing slash is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, issuer = "$ISSUER/")) }
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, issuer = "https://evil.test")) }
    }

    @Test
    fun `wrong audience is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, aud = JsonPrimitive("someone-else"))) }
    }

    @Test
    fun `several audiences need a matching azp`() {
        val aud = jsonArrayOf(CLIENT_ID, "other-client")
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, aud = aud)) }
        failsWith(OidcError.InvalidToken) {
            signJwt(env.key, idClaims(it, aud = aud, extra = mapOf("azp" to JsonPrimitive("other-client"))))
        }
        runBlocking {
            provider.idTokenBuilder = {
                signJwt(env.key, idClaims(it, aud = aud, extra = mapOf("azp" to JsonPrimitive(CLIENT_ID))))
            }
            env.signIn() shouldBe RedirectResult.SignedIn
        }
    }

    @Test
    fun `expired token is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, exp = NOW_S - 3600)) }
    }

    @Test
    fun `small clock skew is tolerated`() = runBlocking<Unit> {
        provider.idTokenBuilder = { signJwt(env.key, idClaims(it, exp = NOW_S - 30, iat = NOW_S + 30)) }
        env.signIn() shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `token issued in the future or not yet valid is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it, iat = NOW_S + 3600)) }
        failsWith(OidcError.InvalidToken) {
            signJwt(env.key, idClaims(it, extra = mapOf("nbf" to JsonPrimitive(NOW_S + 3600))))
        }
    }

    @Test
    fun `wrong or missing nonce is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims("some-other-nonce")) }
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(null)) }
    }

    @Test
    fun `alg none is rejected`() {
        failsWith(OidcError.InvalidToken) {
            val header = b64("""{"alg":"none","kid":"key-1"}""".toByteArray())
            header + "." + b64(idClaims(it).toString().toByteArray()) + "."
        }
    }

    @Test
    fun `HS256 signed with the public key is rejected`() {
        failsWith(OidcError.InvalidToken) {
            val header = b64("""{"alg":"HS256","kid":"key-1"}""".toByteArray())
            val input = header + "." + b64(idClaims(it).toString().toByteArray())
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(env.key.pair.public.encoded, "HmacSHA256"))
            input + "." + b64(mac.doFinal(input.toByteArray()))
        }
    }

    @Test
    fun `an unknown kid refetches the key set once and then fails`() = runBlocking<Unit> {
        val rogue = TestKey.rsa("rotated-key")
        provider.idTokenBuilder = { signJwt(rogue, idClaims(it)) }
        env.signIn() shouldBe RedirectResult.Failed(OidcError.UnknownSigningKey)
        provider.jwksCalls.get() shouldBe 2
    }

    @Test
    fun `a rotated key is picked up by the single refetch`() = runBlocking<Unit> {
        // Warm the cache with the old key set, then rotate.
        env.signIn() shouldBe RedirectResult.SignedIn
        val rotated = TestKey.rsa("rotated-key")
        provider.extraKeys = listOf(rotated)
        provider.idTokenBuilder = { signJwt(rotated, idClaims(it)) }
        val before = provider.jwksCalls.get()
        env.signIn() shouldBe RedirectResult.SignedIn
        provider.jwksCalls.get() shouldBe before + 1
    }

    @Test
    fun `ES256 signed tokens are accepted`() = runBlocking<Unit> {
        val ec = TestKey.ec("ec-1")
        provider.key = ec
        provider.idTokenBuilder = { signJwt(ec, idClaims(it)) }
        env.signIn() shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `an RSA key declared for ES256 is rejected`() {
        failsWith(OidcError.InvalidToken) { signJwt(env.key, idClaims(it), jwtHeader(env.key, alg = "ES256")) }
    }

    @Test
    fun `a missing ID token is rejected`() = runBlocking<Unit> {
        provider.idTokenBuilder = { "" }
        val started = env.begin()
        // An empty id_token string is treated as invalid by the verifier.
        session.handleRedirect(env.redirect(started.state)) shouldBe RedirectResult.Failed(OidcError.InvalidToken)
    }

    // endregion

    // region Access token and scopes

    private suspend fun signInWith(configure: AuthEnv.() -> Unit = {}, token: (AuthEnv, Int) -> String): RedirectResult {
        val e = AuthEnv().also(configure)
        e.provider.accessTokenBuilder = { n -> token(e, n) }
        return e.signIn()
    }

    @Test
    fun `a signed JWT for the resource with the permission is accepted`() = runBlocking<Unit> {
        env.signIn() shouldBe RedirectResult.SignedIn
        session.cachedAccessToken() shouldNotBe null
    }

    @Test
    fun `an opaque access token is rejected`() = runBlocking<Unit> {
        signInWith { _, n -> "opaque-access-token-$n" } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
    }

    @Test
    fun `the client id audience is not accepted for the API`() = runBlocking<Unit> {
        signInWith { e, n -> e.provider.jwt(n, aud = JsonPrimitive(CLIENT_ID)) } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
        signInWith { e, n -> e.provider.jwt(n, aud = jsonArrayOf(CLIENT_ID, "other")) } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
    }

    @Test
    fun `an audience list containing the resource is accepted`() = runBlocking<Unit> {
        signInWith { e, n -> e.provider.jwt(n, aud = jsonArrayOf(CLIENT_ID, AUDIENCE)) } shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `a JWT access token with a bad signature, issuer or expiry is rejected`() = runBlocking<Unit> {
        signInWith { e, n -> e.provider.jwt(n, signer = TestKey.rsa("key-1")) } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
        signInWith { e, n -> e.provider.jwt(n, issuer = "https://evil.test") } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
        signInWith { e, n -> e.provider.jwt(n, exp = NOW_S - 3600) } shouldBe RedirectResult.Failed(OidcError.InvalidToken)
    }

    @Test
    fun `the permission must be in the signed scope claim`() = runBlocking<Unit> {
        signInWith { e, n -> e.provider.jwt(n, scope = JsonPrimitive("openid profile")) } shouldBe RedirectResult.Failed(OidcError.InsufficientScope)
        signInWith { e, n -> e.provider.jwt(n, scope = null) } shouldBe RedirectResult.Failed(OidcError.InsufficientScope)
        signInWith { e, n -> e.provider.jwt(n, scope = JsonPrimitive(7)) } shouldBe RedirectResult.Failed(OidcError.InsufficientScope)
        signInWith { e, n -> e.provider.jwt(n, scope = jsonArrayOf("openid", PERMISSION)) } shouldBe RedirectResult.SignedIn
    }

    @Test
    fun `the token response scope field cannot stand in for the signed claim`() = runBlocking<Unit> {
        provider.scopeInResponse = TEST_SCOPES
        signInWith { e, n -> e.provider.jwt(n, scope = JsonPrimitive("openid")) } shouldBe RedirectResult.Failed(OidcError.InsufficientScope)
    }

    @Test
    fun `a refresh that returns an opaque or unpermitted token keeps the old session unchanged`() = runBlocking<Unit> {
        env.signIn()
        val original = session.cachedAccessToken()!!
        env.now += 3_590_000
        provider.refreshReply = { Reply(200, provider.tokenBody("fake-refresh-token-9", null, 9).replace(provider.jwt(9), "opaque")) }
        (session.accessToken() as TokenResult.Failed).error shouldBe OidcError.InvalidToken
        session.cachedAccessToken() shouldBe original
        provider.refreshReply = {
            Reply(200, provider.tokenBody("fake-refresh-token-9", null, 9).replace(provider.jwt(9), provider.jwt(9, scope = JsonPrimitive("openid"))))
        }
        (session.accessToken() as TokenResult.Failed).error shouldBe OidcError.InsufficientScope
    }

    @Test
    fun `the ID token is never stored`() = runBlocking<Unit> {
        var idToken = ""
        provider.idTokenBuilder = { n -> signJwt(env.key, idClaims(n)).also { idToken = it } }
        env.signIn() shouldBe RedirectResult.SignedIn
        env.cipher.decrypt(env.rawBlob())!!.shouldNotContain(idToken)
    }

    // endregion

    // region Storage

    @Test
    fun `the stored blob never contains plaintext tokens`() = runBlocking<Unit> {
        env.signIn()
        val blob = env.rawBlob()
        blob shouldNotBe ""
        blob shouldNotContain provider.jwt(1)
        blob shouldNotContain "fake-refresh-token"
        blob shouldNotContain "user@example.test"
        env.cipher.decrypt(blob)!!.contains("fake-refresh-token-1") shouldBe true
    }

    @Test
    fun `a corrupt or undecryptable blob means signed out`() = runBlocking<Unit> {
        env.signIn()
        env.cipher.canDecrypt = false
        env.prefs.setRawSessionBlob(env.rawBlob())
        env.prefs.loadSession() shouldBe null
        env.session.isSignedIn() shouldBe false
        env.session.accessToken() shouldBe TokenResult.NotSignedIn
    }

    @Test
    fun `garbage in the blob means signed out`() = runBlocking<Unit> {
        env.signIn()
        env.prefs.setRawSessionBlob(env.cipher.encrypt("not json at all"))
        env.prefs.loadSession() shouldBe null
    }

    @Test
    fun `changing issuer or client id wipes the session`() = runBlocking<Unit> {
        env.signIn()
        env.session.isSignedIn() shouldBe true
        // Same values: nothing happens.
        env.session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, AUDIENCE, TEST_SCOPES, false))
        env.session.isSignedIn() shouldBe true
        env.session.saveConfig(OidcRawConfig(ISSUER, "another-client", AUDIENCE, TEST_SCOPES, false))
        env.session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""
        env.session.state.value.identity shouldBe null
    }

    @Test
    fun `changing the resource or the API permissions wipes the session`() = runBlocking<Unit> {
        env.signIn()
        env.session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, "https://other-api.test", TEST_SCOPES, false))
        env.session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""

        env.session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, AUDIENCE, TEST_SCOPES, false))
        env.signIn()
        env.session.isSignedIn() shouldBe true
        env.session.saveConfig(OidcRawConfig(ISSUER, CLIENT_ID, AUDIENCE, "$TEST_SCOPES extra:permission", false))
        env.session.isSignedIn() shouldBe false
    }

    @Test
    fun `changing the issuer wipes the session and cancels a pending sign-in`() = runBlocking<Unit> {
        val started = env.begin()
        env.signIn()
        env.session.saveConfig(OidcRawConfig("https://auth2.test", CLIENT_ID, AUDIENCE, TEST_SCOPES, false))
        env.session.isSignedIn() shouldBe false
        session.handleRedirect(env.redirect(started.state)) shouldBe RedirectResult.Ignored(OidcError.NoPendingRequest)
    }

    // endregion

    // region Refresh

    @Test
    fun `an expiring token is refreshed and the rotated refresh token is persisted`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L - 30_000L // 30 s left: inside the 60 s margin
        val result = session.accessToken()
        result.shouldBeInstanceOf<TokenResult.Token>()
        (result as TokenResult.Token).value shouldBe provider.jwt(2)
        val form = provider.forms.last { it["grant_type"] == "refresh_token" }
        form["refresh_token"] shouldBe "fake-refresh-token-1"
        form["client_id"] shouldBe CLIENT_ID
        form.keys.none { it.contains("secret") } shouldBe true
        env.prefs.loadSession()!!.refreshToken shouldBe "fake-refresh-token-2"
    }

    @Test
    fun `a token with plenty of life is returned without a request`() = runBlocking<Unit> {
        env.signIn()
        val before = provider.requests.size
        (session.accessToken() as TokenResult.Token).value shouldBe provider.jwt(1)
        provider.requests.size shouldBe before
    }

    @Test
    fun `concurrent callers share one refresh`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L
        provider.tokenDelayMs = 200
        val results = (1..8).map { async(Dispatchers.Default) { session.accessToken() } }.awaitAll()
        provider.refreshCalls.get() shouldBe 1
        results.map { (it as TokenResult.Token).value }.distinct() shouldContainExactly listOf(provider.jwt(2))
    }

    @Test
    fun `refresh after a rejection reuses a token another caller already refreshed`() = runBlocking<Unit> {
        env.signIn()
        val rejected = provider.jwt(1)
        val a = async(Dispatchers.Default) { session.refreshAfterRejection(rejected) }
        val b = async(Dispatchers.Default) { session.refreshAfterRejection(rejected) }
        awaitAll(a, b)
        provider.refreshCalls.get() shouldBe 1
    }

    @Test
    fun `invalid_grant wipes the session`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L
        provider.refreshReply = { Reply(400, """{"error":"invalid_grant"}""") }
        session.accessToken() shouldBe TokenResult.NotSignedIn
        session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""
        session.state.value.error shouldBe OidcError.InvalidGrant
        session.state.value.identity shouldBe null
    }

    @Test
    fun `a network failure keeps the session`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L
        provider.failNetwork = true
        session.accessToken() shouldBe TokenResult.Network
        session.isSignedIn() shouldBe true
        provider.failNetwork = false
        (session.accessToken() as TokenResult.Token).value shouldBe provider.jwt(2)
    }

    @Test
    fun `a server error keeps the session`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L
        provider.refreshReply = { Reply(503, "") }
        session.accessToken() shouldBe TokenResult.Network
        session.isSignedIn() shouldBe true
    }

    @Test
    fun `no refresh token and an expired access token means signed out`() = runBlocking<Unit> {
        val noRefresh = AuthEnv()
        noRefresh.provider.noRefreshToken = true
        noRefresh.signIn()
        noRefresh.now += 3600_000L
        noRefresh.session.accessToken() shouldBe TokenResult.NotSignedIn
        noRefresh.session.isSignedIn() shouldBe false
    }

    @Test
    fun `a refresh that finishes after sign-out does not resurrect the session`() = runBlocking<Unit> {
        env.signIn()
        env.now += 3600_000L
        provider.tokenDelayMs = 300
        val refresh = async(Dispatchers.Default) { session.accessToken() }
        Thread.sleep(100)
        session.signOut()
        refresh.await() shouldBe TokenResult.NotSignedIn
        session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""
    }

    // endregion

    // region Logout

    @Test
    fun `sign-out wipes local tokens and revokes the refresh token without a client secret`() = runBlocking<Unit> {
        env.signIn()
        val result = session.signOut()
        session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""
        session.state.value.identity shouldBe null
        provider.revokeCalls.get() shouldBe 1
        val form = provider.forms.last { it.containsKey("token") }
        form["token"] shouldBe "fake-refresh-token-1"
        form["token_type_hint"] shouldBe "refresh_token"
        form["client_id"] shouldBe CLIENT_ID
        form.keys.none { it.contains("secret") } shouldBe true
        result.endSessionUrl shouldBe "$ISSUER/api/oidc/end-session?client_id=$CLIENT_ID"
        result.endSessionUrl!!.toHttpUrl().queryParameter("id_token_hint") shouldBe null
    }

    @Test
    fun `sign-out still wipes when revocation fails or is unavailable`() = runBlocking<Unit> {
        env.signIn()
        provider.failNetwork = true
        session.signOut()
        session.isSignedIn() shouldBe false
        env.rawBlob() shouldBe ""

        val other = AuthEnv()
        other.signIn()
        other.provider.revokeCode = 500
        other.session.signOut().endSessionUrl shouldNotBe null
        other.session.isSignedIn() shouldBe false
    }

    @Test
    fun `sign-out without a revocation endpoint makes no revoke call`() = runBlocking<Unit> {
        provider.discovery = kotlinx.serialization.json.JsonObject(provider.discovery - "revocation_endpoint")
        env.signIn()
        session.signOut()
        provider.revokeCalls.get() shouldBe 0
        session.isSignedIn() shouldBe false
    }

    // endregion
}
