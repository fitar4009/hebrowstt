# HebrewSTT — Hebrew Speech-to-Text for Android

Offline, on-device Hebrew speech recognition powered by
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) Whisper Tiny.

## Features
- Listens immediately on launch, no button required
- Full Hebrew UI and RTL layout
- Silence-detection pipeline — transcribes each utterance automatically
- Settings screen to tune silence threshold and silence duration
- CI/CD: every push builds a Debug APK artifact via GitHub Actions

---

## One-time setup

### 1. Clone
```bash
git clone https://github.com/<you>/HebrewSTT.git
cd HebrewSTT
```

### 2. Bootstrap the Gradle wrapper jar
The binary `gradle-wrapper.jar` is excluded from git.
Run the bootstrap script once (requires Java 17+ and internet access):
```bash
./scripts/bootstrap.sh
```
> **Android Studio users**: open the project; Studio will offer to generate the wrapper automatically.

### 3. Add the native libraries
Download the sherpa-onnx Android release for your target ABI from:
<https://github.com/k2-fsa/sherpa-onnx/releases>

Place both `.so` files for each ABI under:
```
app/src/main/jniLibs/<ABI>/
    libsherpa-onnx-jni.so
    libonnxruntime.so
```
Typical ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.

### 4. Add the Whisper Tiny model
Download and extract `sherpa-onnx-whisper-tiny.tar.bz2` from the same releases page.

Push the model files onto the device (replace the package ID if you change it):
```bash
adb shell mkdir -p /data/data/com.hebrewstt.app/files/sherpa-onnx-whisper-tiny
adb push tiny-encoder.int8.onnx   /data/data/com.hebrewstt.app/files/sherpa-onnx-whisper-tiny/
adb push tiny-decoder.int8.onnx   /data/data/com.hebrewstt.app/files/sherpa-onnx-whisper-tiny/
adb push tiny-tokens.txt          /data/data/com.hebrewstt.app/files/sherpa-onnx-whisper-tiny/
```

### 5. Build and install
```bash
./gradlew installDebug
```

---

## CI/CD
`.github/workflows/build.yml` runs on every push:
1. Checks out the repo
2. Installs Gradle 8.7 and bootstraps the wrapper
3. Runs `./gradlew assembleDebug`
4. Uploads `app-debug.apk` as a workflow artifact (retained 30 days)

> Note: The CI build will succeed (compile + link) even without the model
> files and `.so` libraries — those are runtime-only requirements.
> Add the `.so` files to the repo under `app/src/main/jniLibs/` to make
> the CI-produced APK fully functional.

---

## Settings
| Setting | Default | Range | Effect |
|---------|---------|-------|--------|
| Silence threshold | 20 | 5 – 100 | RMS amplitude ÷ 1000. Lower = more sensitive. |
| Silence duration | 1500 ms | 500 – 3000 ms | How long silence must last before transcription fires. |

---

## Project structure
```
app/src/main/
├── java/com/k2fsa/sherpa/onnx/   # Thin JNI wrappers (must match native ABI)
│   ├── FeatureConfig.kt
│   ├── OfflineStream.kt
│   └── OfflineRecognizer.kt
└── java/com/hebrewstt/app/
    ├── AudioRecorder.kt           # AudioRecord wrapper, 16 kHz PCM
    ├── SilenceDetector.kt         # RMS-based VAD, buffers speech
    ├── SherpaEngine.kt            # Coroutine-friendly recognizer facade
    ├── MainActivity.kt            # Live transcript UI
    └── SettingsActivity.kt        # Preference screen
```

## License
Apache 2.0
