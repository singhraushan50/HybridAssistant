# Hybrid AI Assistant (Android + Termux + llama.cpp)

Local-first, offline by default, optional OpenAI-compatible cloud fallback.

```
Android app (Kotlin/Compose) ──RUN_COMMAND intent──▶ Termux ──▶ llama-server (llama.cpp, :8080)
   chat · 🎤 audio-in · TTS audio-out · 🖼 image      ▲            rag.py / shell tools
   permission + confirmation layer ◀── result broadcast ┘
   Tool engine: open_url, open_app, dial, sms, search_docs, shell
   Mode: LOCAL | AUTO (cloud on failure) | CLOUD  → any OpenAI-compatible endpoint
```
- **Audio in:** mic → 16 kHz WAV → sent as `input_audio` directly to the model (needs an audio-capable GGUF + mmproj, e.g. LFM3-Omni / Qwen-Omni class).
- **Audio out:** Android TextToSpeech (offline with an installed voice).
- **Images:** sent as `image_url` (needs mmproj).

## Build
Push to GitHub → Actions → "Build debug APK" → download artifact. (Or open in Android Studio.)

## Phone setup (once)
1. Install **Termux from F-Droid** (not Play Store).
2. In Termux: `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties` then restart Termux.
3. Android Settings → Apps → Hybrid Assistant → Permissions → enable **"Run commands in Termux environment"**.
4. Open the app → ⚙ → **Setup Termux** (installs scripts, builds llama.cpp; ~10-30 min, log: `~/llm/setup.log`).

## Add the model (this is all that's needed)
In Termux: `termux-setup-storage`, then copy your files:
```
cp ~/storage/downloads/<model>.gguf ~/llm/models/
cp ~/storage/downloads/<mmproj>.gguf ~/llm/models/    # filename must contain "mmproj"
```
Tap **▶ Start** in the app. Done — chat by text, voice, or image.
Knowledge base: drop files in `~/llm/kb` and the model can call `search_docs`.

## Notes
- Needs a llama.cpp build that supports your model's audio/vision (see the "compatible version" note in your architecture; `git pull` in `~/llm/llama.cpp` and rebuild if needed).
- Cloud is only used in AUTO/CLOUD mode with an API key. Calls/SMS only open the dialer/composer after you confirm.
