# Spokify: offline voice typing for Android

Spokify works like Wispr Flow, but **fully offline**. Hold the floating mic,
speak, let go, and your words are typed into whatever app you are using:
WhatsApp, Outlook, Teams, Notes, a browser form, anything with a text box. It
works alongside your normal keyboard, so you never have to switch keyboards
to talk.

Speech recognition runs on the phone with OpenAI's Whisper model (through
[whisper.cpp](https://github.com/ggml-org/whisper.cpp)). The app has **no
internet permission**, so Android itself stops it from sending anything
anywhere.

There are two ways to dictate:

| Way                                    | How it works                                                        | Best for                               |
| -------------------------------------- | ------------------------------------------------------------------- | -------------------------------------- |
| **Floating mic** (recommended)         | A bubble appears whenever any keyboard is open. Hold it to talk.    | Everyday use with your normal keyboard |
| **Spokify Keyboard** (optional backup) | A voice-only keyboard you switch to. Tap the mic to start and stop. | Apps where the floating mic can't type |

This folder is a separate app from the Handy desktop app in the rest of this
repository. It shares the idea (local Whisper dictation), not the code.

---

## Two editions

|                          | **Spokify**                                    | **Spokify AI**                                                                                        |
| ------------------------ | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Speech to text           | On the phone (Whisper)                         | On the phone (Whisper)                                                                                |
| Tidy-up                  | Built-in rules                                 | Built-in rules, plus optional **AI polish** with your own API key                                     |
| Internet permission      | **None**: Android itself blocks network access | Yes, used only for AI polish                                                                          |
| What can leave the phone | Nothing                                        | When AI polish is on: the **text** of each dictation (never audio), sent to the AI service you choose |
| APK                      | `spokify-apk`                                  | `spokify-ai-apk`                                                                                      |

Both can be installed side by side (they're separate apps). Use one floating
mic at a time: switch off the other one's accessibility service.

### AI polish (Spokify AI)

Off until you turn it on. In **Spokify AI → AI polish (optional)**:

1. Choose a service: **Claude (Anthropic)**, **ChatGPT (OpenAI)**, **Gemini
   (Google)**, or **Other** (any OpenAI-compatible server address).
2. Paste your **API key** from that provider and tap **Save AI settings**. The
   key is stored encrypted with a key held in the Android Keystore, and app
   backup is turned off.
3. Model: for Claude, leave it empty to use `claude-opus-5-5`, or enter another
   (e.g. `claude-haiku-5-5` for faster, cheaper polishing). For Gemini, leave it empty to use `gemini-3.5-flash-lite` (about 1–2 seconds
   in testing). For other services, enter a model name from your provider.
4. Pick a style: **Light touch** (fix grammar, keep your words) or **Polished**
   (clearer sentences, lists on separate lines).
5. Tap **Test with a sample sentence** to check it works, then switch on **Use
   AI polish**.

Every dictation is first cleaned by Spokify's own rules, then sent for polish. Long dictations are polished in pieces of about 200 words (one after another), so a long explanation of a process is punctuated and cleaned throughout; if one piece fails, that piece keeps Spokify's own text.
If the AI can't be reached, is slow (Spokify waits 20 s for a short message, longer for long ones, up to 90 s), declines, or returns
something that isn't a clean-up of your words, Spokify types its own version
and shows why. The AI is told to treat your dictation as text to tidy, never
as instructions to follow. Free API keys allow only a few requests per minute; when the limit is
reached, Spokify types its own version and says so. You pay your AI provider for usage; check your
employer's policy before using it for work content.

## 1. Install it on your phone

You install the app from an APK file ("sideloading"). It isn't on the Play Store.

1. Get `app-release.apk`, either:
   - from the **Actions** tab on GitHub: open the latest _Android voice
     keyboard_ run and download **spokify-apk** under _Artifacts_ (it
     downloads as a zip; the APK is inside), or
   - by building it yourself (see section 5).
2. Copy the APK to your phone (or download it there) and tap it.
3. If Android asks, allow your browser or file manager to **install unknown
   apps**. Turn that setting off again afterwards if you like.
4. Tap **Install**. Google Play Protect may warn about an app from an unknown
   developer. That's expected for apps you build yourself.

## 2. One-time setup (about two minutes)

Open **Spokify** from your app drawer.

### Floating mic

1. Under **Floating mic**, tap **Open accessibility settings**. Allow the
   microphone if Android asks first (_While using the app_).
2. In the list, tap **Installed apps** / **Downloaded apps** if you see it,
   then **Spokify floating mic**, and switch it on.
3. **If the switch is greyed out** ("Restricted setting"): Android does this
   for apps installed outside the Play Store. Go back to Spokify, tap **Open
   App info**, tap **⋮** (top right) → **Allow restricted settings**, then
   repeat step 2.

Android warns that an accessibility service can see your screen. Spokify uses
it only to find the text box you're typing in and put your words at the cursor.
With no internet permission it can't send anything anywhere.

### Spokify Keyboard (optional)

| Step                    | What to do                                                           | Why                                                    |
| ----------------------- | -------------------------------------------------------------------- | ------------------------------------------------------ |
| 1. Allow the microphone | Tap **Allow microphone** → _While using the app_                     | Spokify needs to hear you                              |
| 2. Turn on the keyboard | Tap **Open keyboard settings** → switch on **Spokify Keyboard** → OK | Android only lets you use keyboards you have turned on |
| 3. Switch to it         | Tap **Choose keyboard** → **Spokify Keyboard**                       | Makes it the keyboard shown now                        |

## 3. Daily use

### Floating mic

1. Tap into any text box. Your normal keyboard opens and the **mic bubble**
   appears on the edge of the screen.
2. **Hold** the bubble and speak. It turns red and pulses with your voice.
3. **Let go.** After a second or two the text is typed at your cursor.

| Gesture                                 | What it does                                                         |
| --------------------------------------- | -------------------------------------------------------------------- |
| Hold                                    | Talk (recording starts after a short moment, with a little buzz)     |
| Let go                                  | Stop and type the text                                               |
| While holding, slide far off and let go | Cancel                                                               |
| Drag straight away                      | Move the bubble. It snaps to the nearest edge and remembers the spot |

To keep the bubble on screen even with no keyboard open, turn on **Always
show the bubble**. If an app doesn't accept typed-in text, Spokify tries to
paste it, and otherwise leaves it on the clipboard and tells you. Spokify never
types into password boxes.

### Spokify Keyboard

1. Tap into a text box and switch to **Spokify Keyboard** (keyboard icon at
   the bottom of the screen, or the 🌐 key on Gboard).
2. Tap the **mic** and speak. Tap **■** when done.

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
- **Correct yourself as you go:** say _"no wait"_, _"sorry, I mean"_, _"or
  rather"_ or _"actually no"_ and Spokify keeps only the correction
  ("Meet on Friday, no wait, Monday" → "Meet on Monday"). Say _"scratch that"_
  to drop the sentence you just said.
- **Pauses are fine.** Stopping to think mid-sentence doesn't leave "..." in
  the text; the sentence just continues.
- Spokify also fixes repeated words ("the the"), "a"/"an", a lowercase "i" and
  a missing full stop. Turn this off under **Settings → Fix corrections and
  grammar** if you prefer your words exactly as spoken.
- Add names and jargon under **Settings → Custom words** (for example _Oracle,
  OTC, AR aging, vendor master, DSO_) to help Whisper spell them right.
- One dictation can be up to 15 minutes long (enough to explain a whole process). With **Settings → Faster results
  while you speak** (on by default), Spokify turns each sentence into text
  while you're still talking, so even long dictations appear about a second
  after you stop. Pausing briefly between sentences helps it split cleanly.
- If the keyboard closes while you are dictating, the text is not lost: it is
  saved under **History** in the app. Tap it there to copy it.

## 4. Privacy and controls

These are things you can check yourself, for example for an IT or audit review:

| Control                          | Evidence                                                                                                                                                                                                                                                            |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| No network access                | `AndroidManifest.xml` asks only for `RECORD_AUDIO`. On the phone: _Settings → Apps → Spokify → Permissions_ shows only Microphone. The GitHub build fails if the APK ever asks for `INTERNET`.                                                                      |
| Audio is not stored              | Audio is kept in memory only and released after each dictation (`AudioRecorder.kt`).                                                                                                                                                                                |
| No cloud backup                  | Android backup and device-to-device transfer are turned off for the app (`data_extraction_rules.xml`).                                                                                                                                                              |
| Limited screen access            | The floating mic only listens for window and focus events and reads the focused text box, to type at the cursor (`floating_mic_service.xml`, `FloatingMicService.kt`). It skips password boxes.                                                                     |
| History is optional              | _Settings → Keep history_. Turning it off deletes saved items.                                                                                                                                                                                                      |
| AI polish is opt-in (Spokify AI) | Off by default; only the text is sent, only to the service you choose, with your key (`StreamingTranscriber.kt`, `ClaudePolisher.kt`, `OpenAiCompatiblePolisher.kt`). The offline edition contains none of this code (`app/src/ai/` is only built into Spokify AI). |
| Pinned components                | The whisper.cpp source and the model file are checked against fixed SHA-256 values during the build (`app/build.gradle.kts`).                                                                                                                                       |

## 5. Building it yourself

You need JDK 17 and the Android SDK with NDK `27.2.12479018` and CMake `3.22.1`.

```bash
cd android-keyboard
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew testOfflineReleaseUnitTest testAiReleaseUnitTest assembleOfflineRelease assembleAiRelease
# APKs: app/build/outputs/apk/offline/release/app-offline-release.apk (Spokify)
#       app/build/outputs/apk/ai/release/app-ai-release.apk (Spokify AI)
```

The first build downloads two things (about 60 MB) into `.downloads/` and
checks their SHA-256:

- whisper.cpp **v1.8.3** source (from the `whisper-rs-sys` crate on crates.io,
  which ships an unmodified copy of upstream)
- the Whisper **base.en** model, 5-bit quantised (`ggml-base.en-q5_1.bin`, 57 MB)

To install over USB: `adb install -r app/build/outputs/apk/offline/release/app-offline-release.apk`.

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
Mic (16 kHz, in memory) → split at pauses while you talk (SpeechChunker)
  → each piece → Whisper base.en (on-device, whisper.cpp), in the background
  → join + cleanup (filler words, spoken punctuation, spacing/capitals)
  → typed into the focused text box
```

**Speed.** Two things keep the wait after you stop short:

1. Sentences are transcribed while you're still speaking, so at the end only
   the last one (usually a few seconds of audio) is left.
2. Whisper normally processes a fixed 30-second window, however short the
   clip. Spokify sizes the window to each piece instead (plus a 4-second
   margin, which avoids a Whisper glitch that repeats a sentence). In testing
   this made a 4-second clip about 4× faster with the same text.

In testing, 43 seconds of speech took 2.8 s to process in one go after
release; with this approach the last sentence took about 0.3 s.

| File                                      | Role                                                                   |
| ----------------------------------------- | ---------------------------------------------------------------------- |
| `VoiceKeyboardService.kt`                 | The keyboard: buttons, recording state, typing the result              |
| `AudioRecorder.kt`                        | Microphone capture into memory                                         |
| `WhisperEngine.kt`, `cpp/whisper_jni.cpp` | Bridge to whisper.cpp                                                  |
| `ModelManager.kt`                         | Copies the bundled model out of the APK on first use                   |
| `AiPolish.kt`, `SecretStore.kt`           | AI polish prompt, reply checks and encrypted key storage               |
| `ai/…/ClaudePolisher.kt`                  | Claude via Anthropic's official Java SDK (Spokify AI only)             |
| `ai/…/OpenAiCompatiblePolisher.kt`        | ChatGPT, Gemini and other OpenAI-compatible services (Spokify AI only) |
| `Tidy.kt`                                 | Pause dots, self-corrections and grammar fixes (unit tested)           |
| `TextCleanup.kt`                          | Filler removal, spoken punctuation, spacing/capitals (unit tested)     |
| `FloatingMicService.kt`                   | The floating hold-to-talk bubble, and typing into other apps           |
| `Dictation.kt`                            | Quick checks before transcribing (silence, blocked mic)                |
| `SpeechChunker.kt`                        | Splits live audio at pauses (unit tested)                              |
| `StreamingTranscriber.kt`                 | Transcribes pieces in the background and joins them                    |
| `MainActivity.kt`                         | Setup steps, test box, settings, history                               |

whisper.cpp is compiled once per ARM CPU feature level. On first use it checks
the phone's processor and loads the fastest version it supports, so newer
phones get the speed-ups and older ones still work.

## 7. Known limitations

- **English only.** It uses the English-only `base.en` model.
- **Not live.** Text appears after you stop, not while you speak.
- **Spokify's own clean-up is rule-based.** It removes fillers, applies
  self-corrections and fixes small grammar slips, but doesn't reword sentences.
  For that, use AI polish in Spokify AI.
- **64-bit ARM phones only** (`arm64-v8a`), which covers practically every
  phone sold since 2017.
- **Speed depends on the phone.** Expect roughly 1–3 seconds for a 10-second
  clip on a mid-range phone from the last few years. The first dictation after
  the phone restarts takes about a second longer while the model loads.
