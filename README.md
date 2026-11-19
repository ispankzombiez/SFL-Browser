# SFL Browser

SFL Browser is an unofficial Android browser and notification companion for [Sunflower Land](https://sunflower-land.com/). It is a native Kotlin app and does not require Capacitor or Node.js.

[Project website and privacy policy](https://ispankzombiez.github.io/SFL-Browser/) · [Google Play](https://play.google.com/store/apps/details?id=com.sfl.browser) · [GitHub Releases](https://github.com/ispankzombiez/SFL-Browser/releases) · [Support](https://discord.com/channels/880987707214544966/1314031342182338651)

## Using the app

- **Full browser** opens the game in the built-in browser. The browser layout and native colors are customizable, and the game can initiate supported WalletConnect connections.
- **Notifications only** lets you use farm and price alerts without opening the built-in browser.
- Long-press the launcher icon for **Overview**, **Timeline**, and **Settings** shortcuts.

To set up alerts, allow Android notifications and open **Settings → Farm & Sync**. Enter your **Farm ID** and **Sunflower Land API key**, then tap **Run a check now** to verify the setup. The relevant in-game menu is **Settings → three-dot menu**: the Farm ID is at the top, and the API key is under **General → API Key**. Exact alarms and ignoring battery optimisation are optional and can be changed later.

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

## Publishing releases

In GitHub, open **Actions → Android Release Build → Run workflow** and select `main`. The workflow runs unit tests and publishes the verified, signed APK to GitHub Releases. It uses the repository's existing signing secrets: `RELEASE_KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Pushing code does not start the release workflow.

For Google Play, build the AAB locally with `:app:bundleRelease`, use the upload key registered in Play Console, and upload the bundle manually. The GitHub workflow does not build or upload the AAB.

## Project layout

- [`app/src/main/`](./app/src/main/) — Android app, resources, and bundled assets
- [`app/src/test/`](./app/src/test/) — unit tests
- [`docs/`](./docs/) — project website and privacy policy

## License and disclaimer

The project is distributed under the [MIT License](./LICENSE). SFL Browser is a community project and is not affiliated with or endorsed by Sunflower Land or its developers.
