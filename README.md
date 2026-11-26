# Transcribe

Voice-triggered commands for Paper servers. Players trigger a short "listen window" (default:
sneak + F), say a word, and the plugin runs whatever you've mapped to it. Built on Simple Voice
Chat for the audio, with Vosk (embedded or over a websocket) for speech-to-text.

## Requirements

- Paper/Purpur 1.21.x or 26.x. Folia doesn't work, it uses the normal Bukkit scheduler.
  Use the jar that matches your server: `mc1.21.x` jars are Java 21 bytecode (run on Java 21+),
  `mc26.x` jars are Java 25 bytecode (need Java 25). The plain `mc1.21` jar loads on any 1.21.x
  server; the others refuse to load on a server older than their version.
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) on the server, and the SVC
  **mod** on every client. Without the mod nothing produces mic packets.
- Players have to be in a voice channel (a plugin that makes channels, or `/voicechat <player>`).
- A Vosk model if you use `INTERNAL` mode (~40 MB), or Python 3.11+ with `websockets` and `vosk`
  for `EXTERNAL` mode.
- PlaceholderAPI is optional, only for the placeholder trigger.

## Building

```bash
./gradlew build                        # default target (26.2)
./gradlew shadowJar -Ptarget=26.3      # pick a target
./gradlew shadowJar -Ptarget=1.21.4
./gradlew printTargets                 # list them
./build-all.sh                         # every target into dist/
./build-all.sh 1.21.11 26.3            # only these
```

Targets: `1.21`, `1.21.1`, `1.21.3` to `1.21.11` (Paper never released 1.21.2), `26.1`, `26.2`, `26.3`.
1.21.x builds need a JDK 21 and 26.x builds a JDK 25; Gradle's toolchain picks them up from the
usual install locations.

The jar ends up in `build/libs/` and is about 31 MB because Vosk, JNA and the Opus natives are
shaded in.

## Setup

1. Install Simple Voice Chat and start the server once.
2. Drop the Transcribe jar into `plugins/` and start again so `config.yml` gets created.
3. For `INTERNAL` mode, download `vosk-model-small-en-us-0.15` from
   <https://alphacephei.com/vosk/models> and **unzip** it into
   `plugins/Transcribe/models/`. The path in the config is relative to the plugin folder.
4. Edit `config.yml` and run `/transcribe reload`. A broken config keeps the old one running
   and tells you which key was wrong.

## How it works

1. A player does a trigger gesture and a listen window opens (`activation.window-seconds`).
2. While it's open their mic audio is decoded and buffered. Outside a window packets are dropped
   straight away, so idle cost is basically nothing.
3. When the window closes the audio goes to the engine, with the grammar limited to your words.
4. If the result is one of your keywords, `VoiceWordSpokenEvent` fires and then the commands run.

## Config notes

Everything is commented in `config.yml`. The parts people trip over:

- **`engine.internal.grammar`** is the list of words that can be heard. Short, distinct,
  single words work best, and keep the list small. It can't be empty.
- **`activation.triggers`**: `OFF_HAND`, `SNEAK_OFF_HAND` (default), `LEFT_CLICK`,
  `SNEAK_LEFT_CLICK`, `RIGHT_CLICK`, `SNEAK_RIGHT_CLICK`, `HOTBAR_SLOT`, `SNEAK_HOTBAR_SLOT`.
  Use the sneak ones on survival servers or the plain ones will fire all the time. Clicking on
  an entity doesn't count, Bukkit sends a different event for that.
- **`activation.window-seconds`**: 1.5 to 3 is fine for a single word. Triggering again while
  open just extends it.
- **`activation.placeholderapi.*`**: polls a placeholder every few ticks and keeps the window
  open for anyone whose value matches. Leave `placeholder` empty to turn it off.
- **`debug.level`**: set it to 2 if something isn't working and read the log.

## Keywords

Each file in `plugins/Transcribe/keywords/` is one keyword, with aliases, a permission,
`min-confidence`, a cooldown, `actions` (console or player commands) and `hooks` (MythicMobs
skills, Nexo, ItemsAdder, Oraxen, or any other plugin via commands). `keywords/example.yml` shows
every option.

Words that are only in `engine.internal.grammar` still work through `recognition.console-commands`:

```yaml
recognition:
  console-commands:
    - "spellcast trigger %player% %keyword%"
```

Placeholders in commands: `%player%`, `%uuid%`, `%keyword%`, `%keyword_id%`, `%spoken%`,
`%confidence%`, `%confidence_percent%`, `%world%`, `%x%`, `%y%`, `%z%`. PlaceholderAPI ones work too.

## Commands and permissions

`/transcribe` (alias `/tsc`)

| Command | Permission |
| --- | --- |
| `add keyword <word>` | `transcribe.admin.add` |
| `list` | `transcribe.admin.list` |
| `mute <player> [duration]` (`30s`, `5m`, `1h30m`, `1d`...) | `transcribe.admin.mute` |
| `unmute <player>` | `transcribe.admin.unmute` |
| `reload` | `transcribe.admin.reload` |

Other nodes: `transcribe.use` (everyone by default), `transcribe.keyword.<id>` and
`transcribe.keyword.*`, `transcribe.bypass.cooldown` (op), `transcribe.admin` (op).

## For plugin developers

Listen for the event (main thread, cancellable):

```java
@EventHandler
public void onVoiceWord(VoiceWordSpokenEvent event) {
    if (event.getKeyword().equals("fire") && !manaOk(event.getPlayer())) {
        event.setCancelled(true);
    }
}
```

Open or close a window yourself:

```java
TranscribeAPI api = TranscribeAPI.get();
boolean opened = api.forceStartListenWindow(player, Duration.ofSeconds(3)); // false = was already open
api.isListening(player);
api.forceStopListenWindow(player);
```

To plug in your own recognizer, implement `SpeechRecognitionEngine`.

## External STT server

For `engine.mode: EXTERNAL`, run `server.py` on whatever machine you like:

```bash
pip install websockets vosk
python3 server.py --model /path/to/vosk-model-small-en-us-0.15 --grammar fire,light,ice
```

Then set `engine.external.uri` to it (use `wss://` behind a proxy if it's off-box). It's a
reference implementation, there's no auth on it.

Protocol, one segment at a time per connection:

```
client -> text    {"type":"start","requestId":"<uuid>","playerId":"<uuid>","sampleRate":16000}
client -> binary  16-bit little-endian mono PCM (one or more frames)
client -> text    {"type":"end","requestId":"<uuid>"}

server -> text    {"requestId":"<uuid>","text":"fire","confidence":0.94}
              or  {"requestId":"<uuid>","error":"..."}
```

`text` must be there but can be `""` when nothing matched, that's not an error. The Java side
reconnects with backoff (1s up to 30s).

## Troubleshooting

- **Plugin disables itself on start**: Simple Voice Chat is missing or loaded after Transcribe.
  Restart the server instead of using `/reload`.
- **Nothing happens at all**: check the SVC mic meter in-game first. Then `debug.level: 2`.
- **Always "(no keyword recognized)"**: audio is arriving but doesn't match, so look at your
  words. Small model, short distinct words.
- **"Vosk model directory does not exist"**: wrong `model-path`, or you left the model zipped.
- **EXTERNAL keeps reconnecting**: `localhost` in the URI means the Minecraft server's localhost.

## Testing

`tools/smoke_test.py --accept-eula [--only 1.21.4,26.2]` boots a real Paper server per target with
Simple Voice Chat and the jar from `dist/`, checks that Transcribe enables, and runs `list`,
`reload` and `mute`. `tools/voice_test.py --accept-eula --target <t>` also joins a headless bot,
feeds a recorded clip through the plugin's mic-packet handler (via the `testkit/` helper plugin)
and checks that Vosk recognises it and the keyword's command runs. Both expect the server jars,
Vosk model and bot at the paths at the top of each script.

## License

GPL-3.0. Simple Voice Chat has its own license and isn't bundled.

Used Claude for making the README.md
