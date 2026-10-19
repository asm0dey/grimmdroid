# Grimmdroid

Native Android client for the [Grimmory](https://github.com/grimmory-tools/grimmory) self-hosted book server.
Browse your libraries and shelves, search the catalogue, and download books to read in the reader app you
already use (Grimmdroid has no reader of its own). Android 8.0 (API 26) or newer.

## Supported servers

Grimmory with the mobile app API (`/api/v1/app/*`), tested against v3.5.0. Servers without that API are
detected at sign-in and shown as unsupported.

- **Password sign-in** works out of the box.
- **SSO (OIDC)**: `grimmory://oauth2-callback` must be allowed as a redirect URI in two places: in the identity
  provider, for Grimmory's OIDC client, and in Grimmory's OIDC redirect URIs setting (its default already includes
  it). If the identity provider rejects it, the browser shows the provider's error page; if Grimmory rejects it,
  the app shows a message naming that URI.
- **Self-signed or private CA certificates**: install the CA certificate on the phone (Settings > Security >
  Encryption & credentials > Install a certificate > CA certificate). Grimmdroid trusts user-installed CAs.
  Plain `http://` also works, after a warning that the password travels unencrypted.

## Build

Requires [mise](https://mise.jdx.dev), which pins the toolchain: JDK 21 Liberica (`.sdkmanrc`), Ruby, actionlint and the Android SDK command-line tools. `mise tasks` lists the shortcuts.

```sh
mise install && mise run setup                  # once: toolchain, gems, installs SDK packages
mise run check                                   # merge gate (./gradlew spotlessCheck lint testDebugUnitTest)
bundle exec fastlane test                        # same, via fastlane
```

mise puts the SDK under `$(mise where android-sdk)` and exports `ANDROID_HOME`, so `local.properties` is not needed
(a `sdk.dir` there would override it). In Android Studio, set Settings > Languages & Frameworks > Android SDK > Android SDK Location
to that path to avoid keeping two SDKs. CI uses the runner's preinstalled SDK instead.

Release builds are signed from `GRIMMDROID_KEYSTORE`, `GRIMMDROID_KEYSTORE_PASSWORD`,
`GRIMMDROID_KEY_ALIAS` and `GRIMMDROID_KEY_PASSWORD` (`bundle exec fastlane release`, or
`./gradlew assembleRelease` with those variables set). Release builds are minified with R8.

`./gradlew integrationTest` runs against a throwaway Grimmory from `integration/docker-compose.yml`.

## Distribution

- **F-Droid**: nothing extra; every dependency is FOSS (no Google Play Services, Firebase or analytics).
- **Google Play**: downloads run in a `dataSync` foreground service, so the Play Console needs a
  foreground-service-type declaration with a justification (user-initiated book downloads) and a short video.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
