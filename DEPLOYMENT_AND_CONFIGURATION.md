# SiamVoice TTS — User-Provided Gemini API Key (BYOK) & Android TTS Configuration

SiamVoice TTS uses a lightweight **Bring Your Own Key (BYOK)** architecture for personal use, paired with automatic **External Android System TTS Fallback**.

---

## 1. Obtaining and Configuring Your Gemini API Key

1. Visit Google AI Studio to create or copy your personal Gemini API Key:
   **https://aistudio.google.com/api-keys**
2. Open **SiamVoice** on your Android device and tap the **Settings (ตั้งค่า)** icon in the top-right corner.
3. Under **1. Gemini API Key (BYOK)**:
   - Paste your API key into the secure input field (use the eye icon to show/hide while typing).
   - Tap **บันทึกคีย์ (Save API Key)** to encrypt and store the key using **Android Keystore (`AES/GCM/NoPadding`, 256-bit)**.
   - Tap **ทดสอบการเชื่อมต่อ (Test Connection)** to verify key validity and check available Gemini TTS models via `https://generativelanguage.googleapis.com/v1beta/models`.
   - You can replace or delete the stored API key at any time.

### Security & Billing Notice
- **Personal Use (BYOK)**: User-provided API keys are intended for personal use on your own device and are not a substitute for a multi-user backend.
- **Quota & Charges**: Requests made with your API key consume quota or may incur charges on your Google AI Studio / Google Cloud billing account.
- **Key Protection**:
  - Keys are encrypted at rest via `AndroidKeyStore` (`AES/GCM/NoPadding`).
  - No API keys are hardcoded in source code, `BuildConfig`, XML resources, or APK assets.
  - The full key is never logged, exported, or displayed after saving (only a masked preview such as `AIza••••••••1234` is shown).
  - Requests are sent exclusively over HTTPS to `https://generativelanguage.googleapis.com` using the `x-goog-api-key` header.

---

## 2. Unified TTS Routing & Automatic Fallback

Both the **Home Screen** and the system-wide **Android `TextToSpeechService` (`SiamVoiceTtsService`)** share a single synthesis pipeline (`SiamVoiceTtsSynthesizer`) with strict priority:

1. **Gemini Flash TTS** (`gemini-3.8-flash-tts` / verified Flash TTS model)
2. **Gemini Flash-Lite TTS** (`gemini-3.8-flash-lite-tts` / verified Flash-Lite TTS model)
3. **External Android System TTS** (non-recursive Thai `th-TH` fallback)

When Gemini fails due to a missing API key, no internet connection, quota exhaustion (`429`), API errors, network timeout, or model unavailability, SiamVoice automatically switches to an installed external Android TTS engine that supports Thai (`th-TH`) without ever calling `com.aistudio.siamvoice.thtts` recursively.
