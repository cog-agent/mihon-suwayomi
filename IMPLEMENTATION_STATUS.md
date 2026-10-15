# Suwayomi native client: implementation status

Stage 1: connection foundation and settings UI.
Branch: `cog/suwayomi-native-client`. Baseline was verified clean (`git status --porcelain` empty) at `446f6df82`. Nothing is committed.

## Verification status: NOT COMPILED, NOT TESTED

The sandbox has no JDK, Android SDK, Gradle distribution or dependency cache:

```
$ ./gradlew --version
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
$ which java javac kotlinc gradle sdkmanager   # none found
$ mise ls                                       # nothing installed
```

So no Gradle compile or test ran. Treat the code as unverified until the commands below pass in a prepared environment. The only checks done were static: line length ≤ 120 on touched `.kt` files, and confirming that the Apollo, Preference and Compose APIs used are already used elsewhere in the repo or are in the checked-in schema.

Commands to run where a JDK and the Android SDK are available:

```
./gradlew :app:testDebugUnitTest --tests 'mihon.suwayomi.*'
./gradlew :app:compileDebugKotlin
./gradlew spotlessCheck   # if configured
```

## Live server

No Suwayomi URL or credential was provided or found in the environment, so no live instance was queried. The schema used is the one already checked in at `app/src/main/graphql/mihon/graphql/suwayomi/suwayomi.graphqls`. It contains `Query.aboutServer { name version buildType }` and `AuthMode { NONE BASIC_AUTH SIMPLE_LOGIN UI_LOGIN }`. Compatibility with v2.4.2366 is not confirmed against a running server.

## Design

- **Storage**: server URL and key are stored as private preferences (`Preference.privateKey`), which backups skip.
- **Key encryption**: the API key is encrypted with AES-256-GCM using a non-exportable Android Keystore key (alias `mihon_suwayomi_api_key`). The repo had no encrypted-preferences pattern or `security-crypto` dependency, so this uses `java.security`/`javax.crypto` only and adds no dependency. If the key is lost, the key reads back as empty and the user re-enters it.
- **Connection test**: runs a read-only `aboutServer` query over Apollo against `<base>/api/graphql`. The key is sent as `Authorization: Bearer <key>`. Results are the typed `SuwayomiConnectionResult`: Success(name, version), InvalidUrl, Unauthorized (401/403), HttpError(code), Unreachable, InvalidResponse.
- **Logging and exposure**: results never carry response bodies or the key, and nothing is logged. Verbose HTTP logging now redacts the `Authorization` header (see NetworkHelper below).
- **Loading and error state**: `SuwayomiConnectionState` is an app-scoped `StateFlow` (Idle, Testing, Finished) that the screen collects.
- **UI**: Settings → "Suwayomi server" has URL and API key fields, Save, Test connection, and Remove server buttons. The stored key is never loaded back into the field; an empty field means "keep the stored key".
- **URL rules**: http(s) only, reverse-proxy path prefix kept, trailing slash trimmed. URLs with embedded credentials, a query or a fragment are rejected.

## Files

New:
- `app/src/main/graphql/mihon/graphql/suwayomi/SuwayomiAboutServer.graphql`
- `app/src/main/java/mihon/suwayomi/SecretCipher.kt`
- `app/src/main/java/mihon/suwayomi/SuwayomiServerUrl.kt`
- `app/src/main/java/mihon/suwayomi/SuwayomiPreferences.kt`
- `app/src/main/java/mihon/suwayomi/SuwayomiConnectionTester.kt`
- `app/src/main/java/mihon/suwayomi/SuwayomiConnectionState.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSuwayomiScreen.kt`
- `app/src/test/java/mihon/suwayomi/SuwayomiConnectionTesterTest.kt` (10 cases, using a fake OkHttp interceptor)
- `app/src/test/java/mihon/suwayomi/SuwayomiServerUrlTest.kt`
- `app/src/test/java/mihon/suwayomi/SuwayomiPreferencesTest.kt` (fake cipher)

Modified upstream files (small):
- `app/.../mihon/app/di/AppBindings.kt`: provides `SecretCipher`.
- `app/.../mihon/app/di/AppGraph.kt`: exposes `suwayomiPreferences` and `suwayomiConnectionState`.
- `app/.../settings/screen/SettingsMainScreen.kt`: adds the settings entry.
- `app/.../settings/screen/SettingsSearchScreen.kt`: includes the screen in settings search.
- `i18n/src/commonMain/moko-resources/base/strings.xml`: adds `pref_category_suwayomi`, `pref_suwayomi_summary` and `suwayomi_*` strings.
- `core/common/.../network/NetworkHelper.kt`: `redactHeader("Authorization")` on the verbose logging interceptor, so a bearer token can't reach logcat.

## Known risks to check on first compile

- Metro DI: `SuwayomiPreferences` and `SuwayomiConnectionState` use `@Inject @SingleIn(AppScope::class)`, and `SecretCipher` is provided in `AppBindings`. Confirm the graph resolves.
- Compose: `ConnectionForm` smart-casts `normalizedUrl` inside the Save lambda. Confirm it compiles and that the `CustomPreference` form renders and scrolls correctly in `SearchableSettings`.
- Apollo 5 API names used: `response.hasErrors()`, `ApolloClient.close()`, `ApolloHttpException.body`. Confirm they exist as written.
- The Keystore cipher is not unit-tested, because the JVM has no AndroidKeyStore. It needs an instrumented test or manual device check. The tests use a fake cipher.

## Remaining full-scope gaps

Nothing beyond the connection foundation exists yet:

- The existing Suwayomi tracker (`data/track/suwayomi`) still uses the extension source's URL and credentials, and does not read the new settings.
- No native GraphQL data layer: library, categories, browse/sources/extensions, manga and chapters, reader page fetch, downloads, and updates.
- No handling of server auth modes beyond a static bearer token: no `login`/`refreshToken` for SIMPLE_LOGIN/UI_LOGIN and no Basic auth.
- No minimum-version check or capability gating against the server version.
- No instrumented or UI tests, and no onboarding or migration from the existing tracker or source configuration.
- No schema re-introspection against a real v2.4.2366 server, and no Apollo `subscription` (websocket) handling.
- Cleartext http is allowed so LAN servers work. A network-security-config review is still needed.
