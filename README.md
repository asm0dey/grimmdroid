# Grimmdroid

Native Android client for the [Grimmory](https://github.com/grimmory-tools/grimmory) self-hosted book server.

Work in progress.

## Build

Requires the Android SDK and [mise](https://mise.jdx.dev), which pins the toolchain (JDK 21 Liberica via `.sdkmanrc`, Ruby, actionlint). Run `mise install` once, then `mise tasks` lists the shortcuts.

```sh
mise run check                                   # merge gate (./gradlew spotlessCheck lint testDebugUnitTest)
bundle exec fastlane test                        # same, via fastlane
```

Release builds are signed from `GRIMMDROID_KEYSTORE`, `GRIMMDROID_KEYSTORE_PASSWORD`,
`GRIMMDROID_KEY_ALIAS` and `GRIMMDROID_KEY_PASSWORD` (`bundle exec fastlane release`).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
