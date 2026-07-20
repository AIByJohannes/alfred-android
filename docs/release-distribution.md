# Alfred Android release and distribution

Alfred's permanent application ID is `com.aibyjohannes.alfred`. Never change it for a published build: Android and Google Play treat a different package name as a different app.

## Chosen channel

The primary public channel is **Google Play**, using an Android App Bundle (`.aab`) and Play App Signing. Direct, signed APK distribution remains a secondary channel for testers and users who explicitly opt into sideloading. Debug APK and ADB development remain independent of both channels.

Google's current requirements are account-bound:

- create and verify a Play Console developer account;
- create the app in Play Console and accept Play App Signing;
- register `com.aibyjohannes.alfred` and the eligible signing certificate under Android developer verification;
- complete the store listing, app-content declarations, data-safety form, target-API checks, and any testing requirement shown by Play Console;
- upload a signed AAB with a new version code.

Official references: [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152), [register Play package names](https://support.google.com/googleplay/android-developer/answer/16984799), and [app signing](https://developer.android.com/studio/publish/app-signing).

## Durable release key

Create the key once on a trusted offline workstation. The alias is not secret, but the keystore and both passwords are secrets.

```powershell
keytool -genkeypair -v `
  -keystore C:\secure\alfred-release.jks `
  -alias alfred-release `
  -keyalg RSA -keysize 4096 -validity 10000
```

Back up the keystore, alias, passwords, creation date, and SHA-256 fingerprint in two independently secured locations. At least one backup must be offline. Test recovery by copying the backup to a clean machine, supplying the four settings below, building, and comparing its certificate fingerprint with the recorded value. Losing the app-signing identity can make future upgrades impossible. If Play App Signing is enabled, also preserve the upload key and document Google's upload-key reset process separately from the Play-managed app-signing key.

Supply secrets through CI secret storage, process environment variables, or untracked user Gradle properties:

- `ALFRED_RELEASE_STORE_FILE`
- `ALFRED_RELEASE_STORE_PASSWORD`
- `ALFRED_RELEASE_KEY_ALIAS`
- `ALFRED_RELEASE_KEY_PASSWORD`

Keystores, `keystore.properties`, `local.properties`, and password-bearing files are ignored by Git. Missing configuration fails release packaging with a message naming the missing settings; debug builds do not require them.

## Version policy

- `ALFRED_VERSION_CODE` is a monotonically increasing positive integer. Increase it for every artifact uploaded to Play; never reuse a code that Play has seen.
- `ALFRED_VERSION_NAME` is the user-facing semantic version (`MAJOR.MINOR.PATCH`, with a prerelease suffix when appropriate).
- Local builds default to code `1` and name `1.0`; the release pipeline must set both values explicitly.
- Record the version, commit SHA, artifact checksum, and certificate fingerprint for every published build.

## Build and verify

```powershell
$env:ALFRED_VERSION_CODE = '2'
$env:ALFRED_VERSION_NAME = '1.0.1'
./gradlew.bat clean assembleRelease bundleRelease

apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

Outputs:

- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

For an upgrade rehearsal, keep an older signed APK, build a newer APK with the same key and a larger version code, then run:

```powershell
adb install app-release-old.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Confirm the second command reports success and that app data remains available. Also perform a clean install after uninstalling on a test device. Never use a debug-signed build for this rehearsal because Android correctly rejects an update signed by a different key.

## Release checklist and ownership

1. **Release owner:** select version code/name and confirm the commit and changelog.
2. **Credential owner:** retrieve the upload keystore and passwords from the approved secret stores; verify the recorded SHA-256 fingerprint.
3. **CI:** run unit tests and `assembleDebug`, then build `bundleRelease` and `assembleRelease` with injected secrets.
4. **CI:** verify signatures and publish artifact hashes; never archive plaintext credentials.
5. **Play Console owner:** verify developer identity, create/register `com.aibyjohannes.alfred`, confirm the registered SHA-256 certificate, and complete account/app declarations.
6. **Play Console owner:** upload the AAB to an internal test track and confirm Play accepts it.
7. **QA owner:** install from the internal track on a clean device, then install the next higher-version test release as an upgrade and verify retained data.
8. **Release owner:** promote the tested release and retain the Play release record, mapping file (when minification is enabled), checksums, and rollback notes.

The Play-account registration, identity verification, certificate registration, upload acceptance, and device install evidence cannot be completed from this repository alone; record their console links/screenshots in the private release log when the account owner performs them.
