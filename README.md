# Grimmdroid

Native Android client for the [Grimmory](https://github.com/grimmory-tools/grimmory) self-hosted book server.

Work in progress.

## Build

Requires JDK 21 (BellSoft Liberica) and the Android SDK.

```sh
./gradlew spotlessCheck lint testDebugUnitTest   # merge gate
bundle exec fastlane test                        # same, via fastlane
```

Release builds are signed from `GRIMMDROID_KEYSTORE`, `GRIMMDROID_KEYSTORE_PASSWORD`,
`GRIMMDROID_KEY_ALIAS` and `GRIMMDROID_KEY_PASSWORD` (`bundle exec fastlane release`).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
