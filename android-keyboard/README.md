# Offline Voice Keyboard (Android)

A voice keyboard for Android that works like Wispr Flow, but **fully offline**.
Tap the mic, speak, tap again, and your words are typed into whatever app you
are using: WhatsApp, Outlook, Teams, Notes, a browser form, anything with a
text box.

Speech recognition runs on the phone with OpenAI's Whisper model (through
[whisper.cpp](https://github.com/ggml-org/whisper.cpp)). The app has **no
internet permission**, so Android itself stops it from sending anything
anywhere.

This folder is a separate app from the Handy desktop app in the rest of this
repository. It shares the idea (local Whisper dictation), not the code.

---

## 1. Install it on your phone

You install the app from an APK file ("sideloading"). It isn't on the Play Store.

1. Get `app-release.apk`, either:
   - from the **Actions** tab on GitHub: open the latest _Android voice
     keyboard_ run and download **offline-voice-keyboard-apk** under
     _Artifacts_ (it downloads as a zip; the APK is inside), or
   - by building it yourself (see section 5).
2. Copy the APK to your phone (or download it there) and tap it.
3. If Android asks, allow your browser or file manager to **install unknown
   apps**. Turn that setting off again afterwards if you like.
4. Tap **Install**. Google Play Protect may warn about an app from an unknown
   developer. That's expected for apps you build yourself.

## 2. One-time setup (about a minute)

Open **Offline Voice Keyboard** from your app drawer. The screen walks you
through three steps:

| Step                    | What to do                                                        | Why                                                    |
| ----------------------- | ----------------------------------------------------------------- | ------------------------------------------------------ |
| 1. Allow the microphone | Tap **Allow microphone** → _While using the app_                  | The keyboard needs to hear you                         |
| 2. Turn on the keyboard | Tap **Open keyboard settings** → switch on **Offline Voice** → OK | Android only lets you use keyboards you have turned on |
| 3. Switch to it         | Tap **Choose keyboard** → **Offline Voice**                       | Makes it the keyboard shown now                        |

Android shows a standard warning in step 2 that a keyboard "may be able to
collect all the text you type". Every keyboard gets this warning. This one has
no internet permission, so it has no way to send text anywhere.

## 3. Daily use

1. Tap into any text box. If your normal keyboard appears, tap the **keyboard
   icon** at the bottom of the screen (or the 🌐 key on Gboard) and pick
   **Offline Voice**.
2. Tap the **blue mic** and speak. The button turns red and the bar shows your
   voice level.
3. Tap the **red ■** when done. After a second or two the text is typed in.

| Key             | What it does                                                     |
| --------------- | ---------------------------------------------------------------- |
| 🌐 (left)       | Back to your normal keyboard. Long-press to choose from a list.  |
| Mic             | Start / stop dictation                                           |
| ⌫ (right)       | Delete. Hold to delete repeatedly.                               |
| ↶ (bottom left) | Undo the last dictation. While recording it becomes ✕ to cancel. |
| space / ⏎       | Space, and Enter (or Send/Search/Go, depending on the app)       |

**Tips**

- Speak in full sentences. Whisper adds punctuation and capitals itself.
- Say **"comma"**, **"full stop"**, **"question mark"**, **"new line"** or
  **"new paragraph"** when you want them exactly. ("Period" is deliberately
  _not_ a command, so "the reporting period" types normally.)
- Add names and jargon under **Settings → Custom words** (for example _Oracle,
  OTC, AR aging, vendor master, DSO_) to help Whisper spell them right.
- One dictation can be up to 5 minutes long. Shorter clips (under 30 seconds)
  feel quickest.
- If the keyboard closes while you are dictating, the text is not lost: it is
  saved under **History** in the app. Tap it there to copy it.

## 4. Privacy and controls

These are things you can check yourself, for example for an IT or audit review:

| Control             | Evidence                                                                                                                                                                                                      |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| No network access   | `AndroidManifest.xml` asks only for `RECORD_AUDIO`. On the phone: _Settings → Apps → Offline Voice Keyboard → Permissions_ shows only Microphone. The GitHub build fails if the APK ever asks for `INTERNET`. |
| Audio is not stored | Audio is kept in memory only and released after each dictation (`AudioRecorder.kt`).                                                                                                                          |
| No cloud backup     | Android backup and device-to-device transfer are turned off for the app (`data_extraction_rules.xml`).                                                                                                        |
| History is optional | _Settings → Keep history_. Turning it off deletes saved items.                                                                                                                                                |
| Pinned components   | The whisper.cpp source and the model file are checked against fixed SHA-256 values during the build (`app/build.gradle.kts`).                                                                                 |

## 5. Building it yourself

You need JDK 17 and the Android SDK with NDK `27.2.12479018` and CMake `3.22.1`.

```bash
cd android-keyboard
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew testReleaseUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The first build downloads two things (about 60 MB) into `.downloads/` and
checks their SHA-256:

- whisper.cpp **v1.8.3** source (from the `whisper-rs-sys` crate on crates.io,
  which ships an unmodified copy of upstream)
- the Whisper **base.en** model, 5-bit quantised (`ggml-base.en-q5_1.bin`, 57 MB)

To install over USB: `adb install -r app/build/outputs/apk/release/app-release.apk`.

### Signing

Without a key the APK is signed with the build machine's debug key. That's fine
for one phone, but an APK built on a _different_ machine (or a different GitHub
run) won't install over it: you'd have to uninstall first, which loses your
settings and history. To keep one signature:

1. Create a key once:
   `keytool -genkeypair -v -keystore release.jks -alias voice -keyalg RSA -keysize 2048 -validity 10000`
2. Locally: create `android-keyboard/keystore.properties` (never commit it) with
   `storeFile=release.jks`, `storePassword=…`, `keyAlias=voice`, `keyPassword=…`.
3. On GitHub: add the repository secrets `ANDROID_KEYSTORE_BASE64`
   (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
   and `ANDROID_KEY_PASSWORD`.

## 6. How it works

```
Mic (16 kHz, in memory) → Whisper base.en (on-device, whisper.cpp)
  → cleanup (filler words, spoken punctuation, spacing/capitals)
  → typed into the focused text box
```

| File                                      | Role                                                               |
| ----------------------------------------- | ------------------------------------------------------------------ |
| `VoiceKeyboardService.kt`                 | The keyboard: buttons, recording state, typing the result          |
| `AudioRecorder.kt`                        | Microphone capture into memory                                     |
| `WhisperEngine.kt`, `cpp/whisper_jni.cpp` | Bridge to whisper.cpp                                              |
| `ModelManager.kt`                         | Copies the bundled model out of the APK on first use               |
| `TextCleanup.kt`                          | Filler removal, spoken punctuation, spacing/capitals (unit tested) |
| `MainActivity.kt`                         | Setup steps, test box, settings, history                           |

whisper.cpp is compiled once per ARM CPU feature level. On first use it checks
the phone's processor and loads the fastest version it supports, so newer
phones get the speed-ups and older ones still work.

## 7. Known limitations

- **English only.** It uses the English-only `base.en` model.
- **Not live.** Text appears after you stop, not while you speak.
- **Cleanup is rule-based.** Unlike Wispr Flow's cloud AI it doesn't rewrite
  or reformat your sentences. It removes fillers and handles spoken
  punctuation.
- **64-bit ARM phones only** (`arm64-v8a`), which covers practically every
  phone sold since 2017.
- **Speed depends on the phone.** Expect roughly 1–3 seconds for a 10-second
  clip on a mid-range phone from the last few years. The first dictation after
  the phone restarts takes about a second longer while the model loads.
