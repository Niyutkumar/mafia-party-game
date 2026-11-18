# Mafia Android App (v1.8)

A small native Android app (Java, no extra libraries) that shows the Mafia game from
`app/src/main/assets/index.html` in a full-screen WebView, fully offline.

- Google Play ID: `com.niyutshah.mafia` (permanent once uploaded)
- Java package: `com.mafia.game`
- minSdk 24 (Android 7.0), targetSdk 36 (Android 16), versionCode 9 / versionName 1.8

## What the native side adds (`WebAppInterface.java` → `window.AndroidBridge` in JavaScript)
- `speak(text)` / `stopSpeaking()` – Android text-to-speech for Vincent's narration
- `vibrate(ms)` – haptic feedback on card flips, alarms and the dawn bell (capped at 1 second)
- `isAndroidApp()` – lets the page know it runs inside the app

`MainActivity.java` also:
- hides the status/navigation bars, and pads the game away from camera cut-outs and the keyboard
- handles Back on every Android version: an open sheet in the game closes first, then it asks before exiting
- restarts cleanly if Android stops the WebView engine to free memory

## Security
- Only permission: `VIBRATE`. No internet, storage, location or contacts.
- The WebView can only load the bundled `index.html`. File access, content access, geolocation,
  pop-up windows and remote debugging are all off. Web links open in the phone's browser.
- A Content-Security-Policy in `index.html` blocks outside scripts and sending data anywhere.
- No backups or device-to-device copies of app data (`allowBackup="false"` + `res/xml/data_extraction_rules.xml`).
- The app collects no data at all, so the Play Data safety form is "No data collected".

## Updating the game
1. Edit `../index.html` (the website version) and test it in a browser.
2. Copy it to `app/src/main/assets/index.html`.
3. Raise `versionCode` in `app/build.gradle` by 1 (Google Play rejects a repeated number).
4. Android Studio: **Build > Generate Signed App Bundle / APK > Android App Bundle**, release variant,
   signed with `keystore/mafia-release.jks`. Upload the `.aab` to Google Play.

## The signing key
`keystore/mafia-release.jks` is your **upload key** for Google Play (Google keeps the final app
signing key through Play App Signing). Keep a backup of the `keystore/` folder and its password
somewhere safe (a password manager plus a second drive). Never put it on GitHub: `.gitignore`
already excludes it. If it's ever lost, Google can reset the upload key from Play Console.
