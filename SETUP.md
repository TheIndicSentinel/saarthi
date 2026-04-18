# Saarthi — Developer Setup

## 1. Model Setup (Required before first run)

The app expects the Gemma model at one of these locations on-device:

```
# Option A — internal (preferred, copied by app or push via adb)
/data/data/com.saarthi.app/files/models/gemma-2-2b-it-gpu.bin

# Option B — external (easiest for dev/testing)
/sdcard/Android/data/com.saarthi.app/files/gemma-2-2b-it-gpu.bin
```

### Push model via adb (development)
```bash
# Convert GGUF → MediaPipe bin format first (see below)
adb push gemma-2-2b-it-gpu.bin \
  /sdcard/Android/data/com.saarthi.app/files/gemma-2-2b-it-gpu.bin
```

### Convert GGUF → MediaPipe format
You currently have: `models/gemma-2-2b-it-Q4_K_M.gguf`

MediaPipe requires the `.task` or `.bin` format:
```bash
# Install MediaPipe model maker
pip install mediapipe-model-maker

# Convert (run once on your Mac)
python3 - <<'EOF'
from mediapipe.tasks.python.genai import converter
converter.convert_checkpoint(
    backend="gpu",
    input_ckpt="models/gemma-2-2b-it-Q4_K_M.gguf",
    ckpt_format="gguf",
    model_type="GEMMA_2B",
    precision="q8",
    output_dir="models/mediapipe/",
)
EOF
# Output: models/mediapipe/gemma-2-2b-it-gpu.bin
```

## 2. Build & Run

```bash
cd saarthi
./gradlew :app:installDebug
```

## 3. Skip Onboarding During Development

To jump directly to the chat screen (bypassing onboarding + model init):

In `SaarthiNavHost.kt`, change:
```kotlin
startDestination: String = Route.Onboarding.path
```
to:
```kotlin
startDestination: String = Route.Assistant.path
```

Note: The inference engine will show "Model not loaded" until initialized.

## 4. Test Chat Without the Model

The `InferenceEngine` interface makes mocking trivial:

```kotlin
// In your test / preview DI module:
@Provides
fun provideFakeEngine(): InferenceEngine = object : InferenceEngine {
    override val isReady = true
    override suspend fun initialize(config: InferenceConfig) = Unit
    override fun generateStream(prompt: String, packType: PackType) = flow {
        listOf("Hello! ", "I am ", "Saarthi. ").forEach { emit(it); delay(100) }
    }
    override suspend fun generate(prompt: String, packType: PackType) = "Test response"
    override fun release() = Unit
}
```

## 5. File Attachment Formats Supported

| Type | Extraction |
|------|-----------|
| `.txt`, `.md`, `.csv`, `.json`, `.xml` | Full text → injected into prompt |
| `.kt`, `.py`, `.js`, `.ts`, `.yaml` | Full code → injected |
| `.pdf` | Filename noted (PDFBox needed for text) |
| Images | Preview shown, model notified it's text-only |
| Other | Attached as reference, no text extraction |
