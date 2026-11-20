# SFL Browser

SFL Browser is an unofficial Android browser and notification companion for [Sunflower Land](https://sunflower-land.com/). It is a native Kotlin app and does not require Capacitor or Node.js.

The app supports Android 7.0 (API 24) and later. On Android 7, notifications use the app-wide Android settings rather than the per-category system channels available on Android 8 and later.

[Project website and privacy policy](https://ispankzombiez.github.io/SFL-Browser/) · [Google Play](https://play.google.com/store/apps/details?id=com.sfl.browser) · [GitHub Releases](https://github.com/ispankzombiez/SFL-Browser/releases) · [Support](https://discord.com/channels/880987707214544966/1314031342182338651)

## Using the app

- **Full browser** opens Home in the built-in browser. The tab switcher starts with Home, SFL Calculator, and SFL Wiki; Calculator and Wiki load only when selected. On upgrade, the app replaces untouched old defaults once and keeps custom tabs and a custom Home URL. The browser layout and native colors are customizable, and the game can initiate supported WalletConnect connections.
- **Notifications only** lets you use farm and price alerts without opening the built-in browser.
- Long-press the launcher icon for **Overview**, **Timeline**, **Settings**, and **Deliveries & Chores** shortcuts. Use the top-right menu in any farm page to switch between those farm pages.

To set up alerts, allow Android notifications and open **Settings → Farm & Sync**. Enter your **Farm ID** and **Sunflower Land API key**, then tap **Run a check now** to verify the setup. The relevant in-game menu is **Settings → three-dot menu**: the Farm ID is at the top, and the API key is under **General → API Key**. Exact alarms and ignoring battery optimisation are optional and can be changed later.

The **Daily reset** alert targets 00:00 UTC independently of the farm offset and battery-saving setting. **VIP expiry**, **Calendar events**, **Weather events**, and **Special events** have separate switches and lead times. Date-only entries start at 00:00 UTC. Village Project goals, new animal sickness, and mushroom spawns are detected on farm polls; the first observed state is recorded without alerting about older changes.

Under **Settings → Notifications**, listing sales and accepted buy offers have separate switches. When one marketplace check finds multiple newly sold listings, it sends one notification with the total number of items sold and total FLOWER. Accepted-offer alerts are on by default and stay separate, even though they use the same marketplace check.

## Building from source

Use JDK 17 and the Android SDK with Android 16 (API 36). Open the repository root in Android Studio, or run these Gradle commands from the root:

| Task | Windows PowerShell | macOS/Linux |
| --- | --- | --- |
| Run unit tests | `.\gradlew.bat :app:testDebugUnitTest` | `sh ./gradlew :app:testDebugUnitTest` |
| Build a debug APK | `.\gradlew.bat :app:assembleDebug` | `sh ./gradlew :app:assembleDebug` |
| Build a release APK | `.\gradlew.bat :app:assembleRelease` | `sh ./gradlew :app:assembleRelease` |
| Build a Google Play AAB | `.\gradlew.bat :app:bundleRelease` | `sh ./gradlew :app:bundleRelease` |

If needed, set `ANDROID_HOME` or let Android Studio create a local `local.properties` file. APKs are written to `app/build/outputs/apk/`, and the release AAB is written to `app/build/outputs/bundle/release/`.

For a signed release, copy [`signing.properties.template`](./signing.properties.template) to `signing.properties` at the repository root and supply the appropriate keystore path, alias, and passwords. Use an absolute keystore path, or a path relative to `app/`. Never commit signing credentials, the keystore, a farm API key, or private farm data.

### Optional crash reporting

With `app/google-services.json` for `com.sfl.browser` (ignored by Git), debug and release builds include Firebase Crashlytics. Source builds without the configuration do not include Crashlytics or show its controls. The app asks once and saves either answer; users can change it under **Settings → Farm & Sync → Share crash reports**. Firebase starts only after the user has opted in and restarted the app. Turning reporting off takes full effect on restart. SFL Browser does not add Google Analytics, custom logs, user IDs, or handled-error reporting.

Before rollout, publish the privacy policy and update **Play Console → App content → Data safety**. Review the [Firebase data disclosure guide](https://firebase.google.com/docs/android/play-data-disclosure#crashlytics), including Crashlytics, Installations, and Sessions. For crash reporting, declare **Crash logs**, **Diagnostics**, **Device or other IDs**, and **App interactions** (session starts) as optional collection for **Analytics**, the Play Data safety purpose that covers diagnosing crashes. This does not mean the Google Analytics SDK is installed. Preserve the form's existing declarations for the browser, farm API, and wallet features. Crashlytics can include original exception messages and does not provide a client-side filter for automatic reports. Do not attach raw API responses, logs, URLs, credentials, or wallet details.

## Publishing releases

In GitHub, add an Actions repository secret named `FIREBASE_GOOGLE_SERVICES_JSON` with the contents of the Android Firebase config. Then open **Actions → Android Release Build → Run workflow** and select `main`. The workflow runs unit tests, verifies the APK signer, uploads the mapping for that APK to Crashlytics, and publishes the signed APK to GitHub Releases. The existing signing secrets are `RELEASE_KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Pushing code does not start the release workflow.

After publishing the versioned release, the workflow moves the `latest` tag to the built commit and recreates the rolling `latest` release with a fresh publication date and only the new APK. Its page may be briefly unavailable during replacement, and its download count resets. Older APKs and their download counts stay on their versioned releases.

GitHub APKs must match the signing certificate of the previously published GitHub APK so existing installations can update in place. That certificate differs from the original local APK's certificate; do not substitute the local key for the GitHub release key.

For Google Play, build the AAB locally with `:app:bundleRelease -PrequireCrashReporting=true -PuploadCrashlyticsMapping=true`, use the upload key registered in Play Console, and upload the bundle manually. The GitHub workflow does not build or upload the AAB. Local builds omit the Crashlytics mapping upload unless explicitly requested; use the flags together for the exact build that will ship.

Release bundles include their R8 mapping so Google Play can recover class names and source lines in **Android vitals → Crashes & ANRs**. For local ReTrace use, keep `app/build/outputs/mapping/release/mapping.txt` with its exact build; a later build replaces it. The mapping does not cover SQLCipher native crashes. Those require symbols that match the native binaries in the bundle.

## Project layout

- [`app/src/main/`](./app/src/main/) — Android app, resources, and bundled assets
- [`app/src/test/`](./app/src/test/) — unit tests
- [`docs/`](./docs/) — project website and privacy policy

## License and disclaimer

The project is distributed under the [MIT License](./LICENSE). SFL Browser is a community project and is not affiliated with or endorsed by Sunflower Land or its developers.
