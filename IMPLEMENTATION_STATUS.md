# Suwayomi native client: implementation status

Native Suwayomi client for Mihon: settings, Pocket ID sign-in (OIDC authorization code + PKCE), library, browse, manga detail and reader over GraphQL.
Branch: `cog/suwayomi-native-client`. This checkpoint adds the deployed public defaults, separate application identity, and pinned Nix packaging to the previously published auth/UI implementation. Published APKs and signing material are not committed.

## Summary

- Application id `app.mihon.suwayomi`, versionName 0.21.2, versionCode 38.
- The final APK was built by a sandboxed offline Nix derivation, signed outside Nix, and published to the F-Droid repo; the live repo checks pass.
- Verified: unit tests, a live PKCE/refresh/GraphQL round trip from a network harness, server-side group enforcement, and an emulator smoke run of the signed APK with the network disabled.
- **Not verified: any authenticated use on Android.** No Android sign-in (browser consent, redirect activity, Keystore cipher), library, reader, image or download flow has been exercised on a device or emulator. No Android passkey round trip was tested. This is not a complete end-to-end UI verification.
- Open issue: the Nix cache upload failed (see Nix build).

## Final published artifact

- Universal APK: https://fdroid.aaaaa.news/repo/app.mihon.suwayomi_38-cc11e776.apk
- SHA-256 `cc11e7768123060baf9e3c14ae617a5eae488072e86de1164b71d460c584cfe5`.
- Signature schemes v2 and v3 verified. App signing certificate SHA-256 `2e86f244f41b5d784bad5693a929156bd68736efc66b6b5b9572d7e30dbe1cc7` (signer `CN=Mihon Suwayomi, O=aaaaa.news`).
- The file name carries the hash suffix on purpose: an earlier local-build APK (`app.mihon.suwayomi_38.apk`, sha `d2f6c2c8...`) was superseded after a mismatch was found on read-back, and the new name avoids clients and caches serving the old file. That d2f6 build and its checksums in `/home/pn/release-artifacts/` are **not current**.
- F-Droid repo: https://fdroid.aaaaa.news/repo, repo certificate SHA-256 `1435269BAC486E23FBD40A3381007FAF8EB197E3CA88E7099800596F1B7BB13E`.
- Live check by `fdroid-publish`: `index-v1` and `entry.jar` signatures verify, the `index-v2` hash matches, and the downloaded APK's hash, size and signer all pass.
- F-Droid infrastructure deployed and confirmed at commit `e155728b9268d4f99189659fa7b56520ddba123e`.
- Signing: the existing app key, applied by the parent outside the Nix store. No key is in the Nix store or the repo. Runtime secrets live in `pass`: `agent-server/mihon-suwayomi/app-signing` and `agent-server/fdroid/repo-signing`.

## Nix build

`nix/` (see `nix/README.md`): a sandboxed offline derivation on agent-server producing unsigned release APKs. nixpkgs `e7439b6`, JDK 21, Gradle 9.8.1, SDK platform 36 and 37.2, build-tools 36.0.0, 8g heap.

- Command: `cd /root/mihon-nix-build && nix-build nix/default.nix --max-jobs 1 --cores 8 -o result-unsigned`
- Unsigned output: `/nix/store/pyscnfnqnli8159x44pyswq0vlmg15xc-mihon-suwayomi-unsigned-0.21.2-code38`. The parent fetched these outputs and signed them externally.
- Source has preconfigured public defaults only (no secrets).
- Fixes needed along the way: `allprojects` in the init script is rejected by isolated projects (now `gradle.lifecycle.beforeProject`); AGP needs `package.xml` in the platform 37.2 directory.
- **Cache upload failed.** Pushing to the agent cache was attempted twice (the second with `-j1`). The unsigned output and the `modules-2` snapshot failed with HTTP 499. Only the Gradle distribution, the source archive and some SDK paths are cached. Treat the full input and output set as not cached.
- Not done: no second build, so no bit-for-bit reproducibility check. An earlier comparison of an unsigned Nix build against the superseded local build found one differing entry (`res/M7.json`), cause not investigated; that comparison is against the superseded build and is not evidence about the final artifact.

## Verification evidence

- **Unit tests:** full `:app:testDebugUnitTest`: 174 tests, 13 suites, 0 skipped, 0 failures, 0 errors (`/tmp/mihon-final-unit-tests.log`). The auth suites (`app/src/test/java/mihon/suwayomi/auth/`) cover PKCE and config validation, discovery, the session (public-client token and refresh requests, replay, state mismatch, rotation, sign-out) and the interceptor (origin scoping, https-only token, redirect blocking, single retry). `DeployedDefaultsTest` covers the deployed defaults. Tests use fake HTTP and a fake cipher.
- Two lines in `OidcSessionTest.kt` (70 and 311) were repaired so the test sources compile; they assert only that the cached access token is non-null, weaker than the original intent.
- **Live auth (network harness, not the Android runtime):** against real Pocket ID 2.18 and Suwayomi: browser PKCE consent and code exchange returned 200; refresh returned 200; GraphQL `aboutServer` returned 200 (Suwayomi v2.4.2366); a tampered token returned 401. Users-group enforcement: a user outside the group was denied before `cog` was added to it, and allowed after the user approved.
- **Server side:** native token validator deployed at `2100a7b`. The browser OAuth2Proxy path is unchanged.
- **Android emulator smoke:** the Nix-built, signed x86_64 APK (sha256 `390cdf6cf70b44f7ead562fc3cf1cc230f0a3cdd968a800fc8248d65bff447ce`) ran on an Android 14 KVM emulator, run `/root/android-suwayomi-smoke/state/runs/20261015T211845Z-release`. Install, onboarding, settings and auth-entry defaults were correct; the process stayed alive with no crash or ANR. Result SMOKE-PASS. The emulator network was disabled, so no authenticated use was exercised. The pinned smoke code is saved privately as `soft:android-suwayomi-smoke` at `7b99c90a60e63b3a5f203755b00cdd8b27cba797`.
- **Not exercised:** Android sign-in and browser/redirect handling on a device, the real Keystore cipher, Coil's fetcher override, library/browse/reader/image/download flows, Compose behavior beyond the smoke screens, and schema compatibility beyond `aboutServer`.

Build memory note: the sandbox cgroup is 3 GiB. A working local debug invocation:

```
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx2300m -XX:MaxMetaspaceSize=400m" \
  -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :app:assembleDebug
```

## Deployed defaults and Pocket ID setup

The deployed values are the preference defaults and the user can override them: issuer `https://auth.aaaaa.news`, resource `https://manga.aaaaa.news`, plus the client id and scopes in `SuwayomiPreferences`/`OidcConfig` (all public, no secrets). The Pocket ID client is a public client with PKCE `S256`, no client secret, and the exact redirect URI `app.mihon.suwayomi:/oauth2redirect`.

Server-side requirements the app relies on:

- The resource/audience that Pocket ID issues access tokens for. The app sends it as `resource` and requires it in the access token's `aud`.
- At least one API permission (non-standard scope), requested and required in the token's `scope` claim. The app refuses sign-in without one.
- Signed JWT access tokens (RFC 9068 style); opaque tokens are rejected.
- `offline_access` for refresh tokens (optional toggle).
- Group membership enforced server-side (users group). The app only requests the `groups` scope.
- Bearer validation (issuer, audience, expiry, signature, permission/group) before forwarding to Suwayomi, done by the deployed native validator.

Pocket ID management API keys are never used, requested or stored.

## What is implemented

### Native UI (`app/src/main/java/mihon/suwayomi/ui/`)
- `SuwayomiHomeScreen`: tabs for library, browse/sources, downloads and related views, driven by `SuwayomiHomeScreenModel`. While signed out it shows `SignInRequired` and loads nothing; loading starts when the session appears. `OnResume` re-reads the stored session when the browser hands control back.
- `SuwayomiMangaScreen`, `SuwayomiReaderScreen`: manga detail and reader.
- `SuwayomiUi`: shared loading/error rendering. `SuwayomiImage` loads https server-origin URLs through an `OkHttpNetworkFetcherFactory` backed by the repository's authorised, redirect-disabled client; other URLs use the app's default loader with no credentials.
- `SuwayomiAuthUi`: sign-in button, error text for every `OidcError`, resume hook.
- Settings → "Suwayomi server" (`SettingsSuwayomiScreen`): server URL, OIDC settings, sign in, sign out, connection test.
- GraphQL documents in `app/src/main/graphql/mihon/graphql/suwayomi/`; data access in `SuwayomiRepository`, returning typed `SuwayomiResult`/`SuwayomiError` (including `NotSignedIn`).

### PKCE sign-in (`app/src/main/java/mihon/suwayomi/auth/`)
- `OidcConfig`: issuer must be https, without credentials, query or fragment. Redirect URI constant `app.mihon.suwayomi:/oauth2redirect`. At least one non-standard scope is required in addition to `openid`.
- `OidcDiscovery`: discovery `issuer` must match the configured string exactly; `code_challenge_methods_supported` must include `S256`; authorization, token and JWKS endpoints must be https and same-origin as the issuer.
- `Pkce` / `OidcSession.beginSignIn`: 32-byte random `state`, `nonce` and verifier; `S256`; `resource` set to the configured audience. A new attempt replaces any pending one; requests expire after 10 minutes.
- `OidcRedirectActivity`: receives the redirect, hands it to the app-scoped session, returns to `MainActivity`. The session ignores anything not matching the pending `state`, consumes the request on first use, and rejects duplicate query parameters.
- Token exchange: public client, `client_id` only, `code_verifier` sent. Redirects are never followed; response bodies capped at 1 MiB (`OidcHttp`).
- `JwtVerifier`: allow-list of RS256/384/512 and ES256/384 (no `none`, no HMAC), key type must match algorithm, RSA keys under 2048 bits skipped, `crit` rejected, one JWKS refetch on unknown `kid`. ID token checks `iss`, `aud`/`azp`, `sub`, `exp`/`iat`/`nbf` (60 s skew), `nonce`. Access token must be a signed JWT with matching `iss`, `aud` equal to the resource, `sub`, valid times and a `scope` claim containing every configured permission.
- Refresh: single-flight, runs to completion once started, rotated refresh token stored with the new access token, `invalid_grant` wipes the session, network errors keep it. An epoch counter discards results that finish after sign-out or reconfiguration.
- Storage (`OidcPreferences`): session is one JSON blob encrypted with the Keystore AES-256-GCM `SecretCipher`, under a private preference key that backups skip. The ID token is not kept. Changing issuer, client id, resource or permissions drops the session.
- Sign-out: local wipe first, then best-effort refresh-token revocation and the end-session URL if advertised.
- `SuwayomiAuthInterceptor`: the only place the header is set. See the token transport policy.
- `SuwayomiConnectionTester` and `SuwayomiRepository` build their Apollo client through this interceptor and send no credentials without a session.

## Token transport policy

- The Pocket ID token is obtained and attached only when the configured server origin is https, and only to that exact scheme/host/port. For an http server, and for every other origin, no token is requested and any `Authorization` header is stripped.
- The client is redirect-disabled: a 3xx leaving the server origin, or heading to the issuer, becomes a 401 and is never followed with the token. On 401 it refreshes once and retries once (not for one-shot bodies).
- GraphQL, the connection test, images, page URLs and downloads all use this policy.
- Legacy http servers remain selectable; they run without a session or OIDC credentials. The saved Suwayomi API key is not sent while signed in.

## Open security notes

No secrets, tokens, client secrets or management API keys were found in tracked or untracked files; test fixtures generate keys at runtime. No logging calls exist in the auth package or auth UI.

1. **Redirect scheme can be claimed by other apps.** `app.mihon.suwayomi:` is a custom scheme, so another app can register the same filter. PKCE plus `state` limit the impact; an https App Link would be stronger.
2. **Sign-out revocation is best effort.** The local session is wiped first and revocation errors are ignored, so on a network failure the refresh token stays valid at the provider until it expires.
3. **Legacy API key field is still in the settings UI** (`SettingsSuwayomiScreen`, `SuwayomiPreferences`) but is not sent while signed in. Misleading; remove or label it.
4. **Issuer redirect check is a string prefix** (`location.toString().startsWith(issuer)`) in the interceptor. It only adds blocking on top of the same-origin rule, so it is not exploitable, but it is inexact.
5. **Exported redirect activity** accepts any `ACTION_VIEW` for the scheme. It holds nothing and the session ignores non-matching input.
6. **Token stored as one blob** under a non-exportable Keystore key; on a rooted device a running process can still read it. The Keystore path is untested on device.
7. **Release manifest contains `<profileable>`**, set by the release build type; `debuggable` is not set.

## Remaining scope gaps

- Nix cache upload (HTTP 499) and bit-for-bit reproducibility are unresolved.
- No authenticated Android runtime verification (see Summary).
- The existing Suwayomi tracker (`data/track/suwayomi`) still uses the extension source's URL and credentials and does not read these settings.
- No capability gating against the server version; no schema re-introspection against a real server; no websocket subscriptions.
- No instrumented or UI tests.
- No migration or onboarding from the tracker or extension configuration.
- `network_security_config.xml` still permits cleartext.
