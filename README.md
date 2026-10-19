# Grimmdroid

Native Android client for the [Grimmory](https://github.com/grimmory-tools/grimmory) self-hosted book server.

Work in progress.

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
`GRIMMDROID_KEY_ALIAS` and `GRIMMDROID_KEY_PASSWORD` (`bundle exec fastlane release`).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
