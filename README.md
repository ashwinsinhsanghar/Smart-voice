# SmartVoice (Nuance)

Android text-to-speech engine with two built-in Nuance Vocalizer voices:

- English (United States) - Samantha
- Hindi (India) - Lekha

The app detects the language of the text automatically and picks the matching voice.

## Build

GitHub Actions: `.github/workflows/build.yml` builds a signed release APK on every push to any branch
(or manually via *Run workflow*), stores it as an artifact and sends it to Telegram.

Locally: `./gradlew assembleRelease`. Signing uses `keystore.properties` + `Ketan.jks`
(keep the repository private).

The native Nuance library is arm64-v8a only (Android 7.0+, minSdk 24).
