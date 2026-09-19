# Transcribe

A Paper 1.21+ plugin for voice transcribing, built on Simple Voice Chat with
pluggable speech-to-text (embedded Vosk or an external WebSocket microservice).

## Project layout

```
build.gradle.kts               Java 21 / Paper 1.21.1+ Gradle build (Kotlin DSL)
settings.gradle.kts
server.py                      Standalone reference external STT server (Python 3.11+, websockets + vosk)

src/main/java/org/eyman_/
  TranscribePlugin.java        Main Paper entry point: lifecycle, config, SVC hook, recognition dispatch
  activation/
    ActivationManager.java      Detects SHIFT+Q / SHIFT+F / hotbar-slot / PlaceholderAPI activation triggers
  api/
    TranscribeAPI.java          Public singleton: isListening() / forceStartListenWindow() for other plugins
  engine/
    SpeechRecognitionEngine.java Strategy interface for STT backends
    InternalVoskEngine.java      Embedded Vosk (JNI) implementation
    ExternalWebSocketEngine.java WebSocket client implementation (pairs with server.py)
  events/
    VoiceWordSpokenEvent.java    Cancellable Bukkit event fired when a keyword is recognized

src/main/resources/
  plugin.yml                    Plugin descriptor (depends on Simple Voice Chat, soft-depends on PlaceholderAPI)
  config.yml                    Heavily annotated default configuration
```

## Building

Requires network access to a Maven repository mirror providing Paper API, the Simple Voice
Chat API, PlaceholderAPI, Vosk, Java-WebSocket, and Gson (see the `repositories { }` block in
`build.gradle.kts`).

```bash
./gradlew build
```

The shaded jar (`build/libs/Transcribe-<version>.jar`) is ready to drop into a Paper server's
`plugins/` folder alongside Simple Voice Chat.

## Running the external STT server (only needed for `engine.mode: EXTERNAL`)

```bash
pip install websockets vosk
python3 server.py --model /path/to/vosk-model-small-en-us --grammar ignis,lumos,glacio
```

See the module docstring at the top of `server.py` for the full wire protocol.

## Configuration

Edit `plugins/Transcribe/config.yml` after the first run - every option is documented inline,
covering engine selection (`INTERNAL` vs `EXTERNAL`), the Vosk grammar/keyword list, activation
triggers (fixed SHIFT+Q/SHIFT+F keybinds, configurable hotbar slot, PlaceholderAPI condition),
and console-command dispatch on recognized keywords.
