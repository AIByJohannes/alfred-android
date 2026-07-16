# Alfred Android release distribution

Alfred's stable package name is `com.aibyjohannes.alfred`. Do not change it after publishing or registering the app.

## Release signing

Release builds intentionally fail until a dedicated release key is configured. Create and protect the key outside the repository, then provide these values as Gradle properties or environment variables:

- `ALFRED_RELEASE_STORE_FILE`: absolute path, or a path relative to the repository root
- `ALFRED_RELEASE_STORE_PASSWORD`
- `ALFRED_RELEASE_KEY_ALIAS`
- `ALFRED_RELEASE_KEY_PASSWORD`

For a local build, pass the values through an untracked user Gradle configuration or the process environment. Never add a keystore or passwords to this repository. Verify the signed output before distribution:

```powershell
just build
./gradlew.bat bundleRelease
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

Back up the release keystore and its passwords in two secure locations. Losing the signing identity can prevent future updates and package registration.

## Registration checklist

1. Choose the distribution channel: Play Console for Play distribution, or Android Developer Console for distribution outside Play.
2. Verify the developer identity in that console.
3. Register `com.aibyjohannes.alfred`.
4. Supply the SHA-256 certificate fingerprint of the release signing key.
5. Upload a signed Android App Bundle or APK and retain the console registration record.

Developer identity verification, console acceptance, and signing-key creation are account-bound actions and must be performed by the app owner.
