# Suwayomi native client: implementation status

Native Suwayomi client for Mihon: settings, Pocket ID sign-in (OIDC authorization code + PKCE), library, browse, manga detail and reader over GraphQL.
Branch: `cog/suwayomi-native-client`. State at this checkpoint: the auth work and UI changes described below are in the working tree, pending supervisor review; this pass did not commit or push.

## Release status: NOT releasable

Blockers, none of which is resolved:

1. **No Pocket ID registration and no authenticated runtime proof.** The public client and the API resource/permission have not been registered, so no real sign-in, token exchange, refresh or authenticated Suwayomi request has ever run. Everything auth-related is checked against fakes only.
2. **Proxy bearer authorization is not deployed or verified.** The reverse proxy in front of Suwayomi must accept the Pocket ID access token in `Authorization: Bearer`. Nothing confirms it does, or that it rejects missing, expired, wrong-audience and wrong-group tokens.
3. **No device or emulator.** The app has never been launched: no screen, browser round trip, redirect activity, reader or Keystore cipher has been exercised.
4. **No pinned Nix artifact.** No Nix or F-Droid metadata exists in the repo.
5. **No release signing and no F-Droid deployment.** No release key exists; only debug-signed APKs were produced.

## What is implemented

### Native UI (`app/src/main/java/mihon/suwayomi/ui/`)
- `SuwayomiHomeScreen`: tabs for library, browse/sources, downloads and related views, driven by `SuwayomiHomeScreenModel`. While signed out it shows `SignInRequired` instead of content and loads nothing; loading starts when the session appears. `OnResume` re-reads the stored session when the browser hands control back.
- `SuwayomiMangaScreen`, `SuwayomiReaderScreen`: manga detail and reader.
- `SuwayomiUi`: shared loading/error rendering. `SuwayomiImage` loads https server-origin URLs through an `OkHttpNetworkFetcherFactory` backed by the repository's authorised, redirect-disabled client; other URLs use the app's default loader with no credentials.
- `SuwayomiAuthUi`: sign-in button, error text for every `OidcError`, resume hook.
- Settings → "Suwayomi server" (`SettingsSuwayomiScreen`): server URL, OIDC settings (issuer, client id, resource/audience, scopes, offline access), sign in, sign out, connection test.
- GraphQL documents in `app/src/main/graphql/mihon/graphql/suwayomi/` (about, catalog, manga, management, read-progress); data access in `SuwayomiRepository`, which returns typed `SuwayomiResult`/`SuwayomiError` (including `NotSignedIn`).

### PKCE sign-in (`app/src/main/java/mihon/suwayomi/auth/`)
- `OidcConfig`: no defaults for issuer or client id. Issuer must be https, without credentials, query or fragment. Redirect URI constant `app.mihon.suwayomi:/oauth2redirect`. At least one non-standard scope (an API permission) is required in addition to `openid`.
- `OidcDiscovery`: issuer in the discovery document must match the configured string exactly; `code_challenge_methods_supported` must include `S256`; authorization, token and JWKS endpoints must be https and same-origin as the issuer.
- `Pkce` / `OidcSession.beginSignIn`: 32-byte random `state`, `nonce` and verifier; `code_challenge_method=S256`; `resource` set to the configured audience. A new attempt replaces any pending one; requests expire after 10 minutes.
- `OidcRedirectActivity`: receives the redirect, passes it to the app-scoped session, returns to `MainActivity`. The session ignores anything that does not match the pending `state`, consumes the request on first use (replays are ignored), and rejects duplicate query parameters.
- Token exchange: public client, `client_id` only, no client secret, `code_verifier` sent. Redirects are never followed and response bodies are capped at 1 MiB (`OidcHttp`).
- `JwtVerifier`: allow-list of RS256/384/512 and ES256/384 only (no `none`, no HMAC), key type must match algorithm, RSA keys under 2048 bits skipped, `crit` rejected, one JWKS refetch on unknown `kid`. ID token: `iss`, `aud`/`azp`, `sub`, `exp`/`iat`/`nbf` with 60 s skew, `nonce`. Access token must be a signed JWT with matching `iss`, `aud` equal to the configured resource, `sub`, valid times and a `scope` claim containing every configured API permission.
- Refresh: single-flight, runs to completion once started, rotated refresh token written in the same blob as the new access token, `invalid_grant` wipes the session, network errors keep it. An epoch counter discards results that finish after sign-out or reconfiguration.
- Storage (`OidcPreferences`): session (access token, refresh token, identity) is one JSON blob encrypted with the existing Keystore AES-256-GCM `SecretCipher`, under a private preference key that backups skip. The ID token is not kept. Changing issuer, client id, resource or API permissions drops the session.
- Sign-out: local wipe first, then best-effort refresh-token revocation (no client secret), and the end-session URL if the provider advertises one.
- `SuwayomiAuthInterceptor`: the only place the header is set. `Authorization: Bearer <Pocket ID access token>` goes only to the configured server's exact scheme/host/port, and only when that origin is https (no token is requested for an http server); any other Authorization header on any other origin is stripped. Redirects are disabled; a 3xx leaving the server origin, or heading to the issuer, becomes a 401 and is not followed with the token. On 401 it refreshes once and retries once (not for one-shot bodies). The saved Suwayomi API key is deliberately not sent while signed in.
- `SuwayomiConnectionTester` and `SuwayomiRepository` both build their Apollo client through this interceptor and send no credentials without a session.

## Pocket ID admin setup required (not done; do not invent IDs)

Register an OIDC **public client** in Pocket ID with:

- Issuer: `https://auth.aaaaa.news` (entered in the app exactly as written, no trailing slash; the discovery document `issuer` must match character for character).
- Redirect URI (exact): `app.mihon.suwayomi:/oauth2redirect`
- PKCE `S256`, public client, **no client secret**. The app never sends one.
- Client ID: whatever Pocket ID assigns. Enter it in the app; it is not hardcoded anywhere and is not recorded here.

The server side must also provide:

- An API **resource/audience** value that Pocket ID issues access tokens for. The app sends it as `resource` and requires it in the access token's `aud`. Its value is whatever the admin registers.
- At least one API **permission** (non-standard scope) on that resource, requested as a scope and required in the access token's `scope` claim. The app refuses sign-in without one configured, and refuses tokens that lack it.
- Signed JWT access tokens (RFC 9068 style). Opaque access tokens are rejected.
- `offline_access` if refresh tokens are wanted (optional toggle in the app).
- **Groups**: the user must be in the group(s) the proxy authorizes for Suwayomi, and the `groups` claim must be available to whatever the proxy checks. Group authorization is enforced by the proxy, not by the app; the app only requests the `groups` scope by default.
- Reverse proxy configured to validate the bearer token (issuer, audience, expiry, signature, permission/group) before forwarding to Suwayomi.

Pocket ID management API keys are never used, requested or stored by this app or by this work. Only end-user OIDC sign-in is used.

## Verification evidence

Independently verified by the supervisor; not re-run while editing this document:

- Universal debug APK: v2 signature valid; SHA256 `cc162d59a0b4b67f4558de7d16569d889f0789eb48996b9c0d34228dc0ac8b4b`. This is a debug-signed artifact, not a release.
- Unit tests: 172 tests across 12 suites, 0 skipped, 0 failures, 0 errors. The auth suites (`app/src/test/java/mihon/suwayomi/auth/`) cover PKCE and config validation, discovery, the session (public-client token and refresh requests, replay, state mismatch, rotation, sign-out) and the interceptor (origin scoping, https-only token, redirect blocking, single retry). Repository and connection-tester tests cover the http and https policy. Tests use fake HTTP and a fake cipher.
- Two lines in `OidcSessionTest.kt` (70 and 311) were repaired so the test sources compile; they now assert the cached access token is non-null, weaker than the original intent.

Not verified: no live authenticated runtime proof exists. Not exercised: any live Pocket ID or Suwayomi server, Coil's fetcher override on a device, the real Android Keystore cipher, browser/redirect handling on a device, any UI rendering, Compose behavior, and schema compatibility with a running Suwayomi.

Build memory note: the sandbox cgroup is 3 GiB. A working invocation:

```
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx2300m -XX:MaxMetaspaceSize=400m" \
  -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :app:assembleDebug
```

## Open security notes

No secrets, tokens, client secrets or management API keys were found in tracked or untracked files; test fixtures generate keys at runtime. No logging calls exist in the auth package or auth UI. Open items:

2. **Redirect scheme can be claimed by other apps.** `app.mihon.suwayomi:` is a custom scheme, so another installed app can register the same filter. PKCE plus `state` limit the impact (an interceptor lacks the verifier), but the browser may deliver the code to the wrong app. Acceptable for a public client; an https App Link is stronger if Pocket ID can issue one.
3. **Sign-out revocation is best effort.** The local session is wiped before revocation, and revocation errors are ignored, so on a network failure the refresh token stays valid at the provider until it expires. Low severity.
4. **Legacy API key field is still in the settings UI** (`SettingsSuwayomiScreen`, `SuwayomiPreferences`) but is no longer sent. Misleading, and an encrypted secret is kept for nothing. Remove it or label it.
5. **Issuer redirect check is a string prefix** (`location.toString().startsWith(issuer)`) in the interceptor. It only adds blocking on top of the same-origin rule, so it is not exploitable, but it is inexact; a server on the same origin as the issuer would not have such redirects blocked by origin alone.
7. **Exported redirect activity** accepts any `ACTION_VIEW` for the scheme. It holds nothing and the session ignores non-matching input, so this is only noted.
8. **Token stored as one blob** encrypted by a non-exportable Keystore key; on a rooted device a running process can still read it. Standard for this design; the Keystore path is untested on device.

## Remaining scope gaps

- The existing Suwayomi tracker (`data/track/suwayomi`) still uses the extension source's URL and credentials and does not read these settings.
- No capability gating against the server version; no re-introspection of the schema against a real server; no websocket subscriptions.
- No instrumented or UI tests.
- No migration or onboarding from the tracker or extension configuration.
- `network_security_config.xml` still permits cleartext, and legacy http servers remain selectable (they run without a session or OIDC credentials).

## Token transport policy

- The Pocket ID token is obtained and attached only when the configured server origin is https. `SuwayomiAuthInterceptor` skips the token request and strips any `Authorization` header for an http server and for every other origin. It runs on a redirect-disabled client, so a redirect (including https to http on the same host) is reported as 401 and never followed with the token.
- GraphQL, the connection test, images, page URLs and downloads all use that policy. Images no longer pass the token through Coil: `SuwayomiImage` uses an `OkHttpNetworkFetcherFactory` backed by the repository's authorised, redirect-disabled client for https server-origin URLs only. Other URLs use the app's default loader with no credentials. `imageHeaders` and `freshImageHeaders` are removed.
- Legacy http servers are still selectable. They run without a session and carry no OIDC credentials; the saved API key is still not sent.
