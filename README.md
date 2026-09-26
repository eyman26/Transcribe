# Transcribe

A Paper 1.21+ plugin for voice transcribing, built on Simple Voice Chat with
pluggable speech-to-text (embedded Vosk or an external WebSocket microservice).

## Requirements

**Simple Voice Chat is a hard requirement.** Transcribe consumes the microphone packet stream
through Simple Voice Chat's plugin API, so `plugin.yml` declares `depend: [voicechat]` and
Paper will refuse to enable Transcribe if it is missing.

| | |
| --- | --- |
| Server | Paper / Purpur 1.21+ (built against `paper-api:1.21.1`), Java 21. **Folia is not supported** - Transcribe uses the standard Bukkit scheduler. |
| Required plugin | [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (Bukkit/Paper build), server side |
| Required client | The Simple Voice Chat **mod** on Fabric / Forge / NeoForge - the mod is what actually produces microphone packets, so a server-only install will never hear anything |
| Optional plugin | [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) - only for the polled condition trigger |
| `engine.mode: INTERNAL` | An unpacked small Vosk model (~40 MB) on the server |
| `engine.mode: EXTERNAL` | Python 3.11+ with `websockets` + `vosk` on the STT host |

Each player also needs to be in a voice channel, otherwise Simple Voice Chat may not forward a
microphone stream. Use a plugin that assigns per-player channels, or have staff run
`/voicechat <player>` to pull someone in.

## Project layout

```
build.gradle.kts               Java 21 / Paper 1.21.1+ Gradle build (Kotlin DSL)
settings.gradle.kts
server.py                      Standalone reference external STT server (Python 3.11+, websockets + vosk)

src/main/java/org/eyman_/
  TranscribePlugin.java        Main Paper entry point: lifecycle, config, SVC hook, recognition dispatch
  activation/
    ActivationManager.java      Detects the eight gesture triggers + PlaceholderAPI condition
    CueSettings.java            Configurable open/close action bar + sound cues
  api/
    TranscribeAPI.java          Public singleton: isListening() / forceStartListenWindow() for other plugins
  engine/
    SpeechRecognitionEngine.java Strategy interface for STT backends
    InternalVoskEngine.java      Embedded Vosk (JNI) implementation
    ExternalWebSocketEngine.java WebSocket client implementation (pairs with server.py)
  audio/
    TranscribeAudioListener.java SVC packet -> opus4j decode -> buffered PCM -> engine
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
python3 server.py --model /path/to/vosk-model-small-en-us --grammar fire,light,ice
```

See the module docstring at the top of `server.py` for the full wire protocol.

## Configuration

Edit `plugins/Transcribe/config.yml` after the first run - every option is documented inline,
covering engine selection (`INTERNAL` vs `EXTERNAL`), the Vosk grammar/keyword list, activation
triggers,
and console-command dispatch on recognized keywords.

---

# Tutorial

## 1. How the whole thing works

Read this once and every config key makes sense.

1. **Trigger.** A player does something (default: sneak + F). Optionally a PlaceholderAPI
   condition is polled instead.
2. **Listen Window.** For `activation.window-seconds` (default `3.0`), that player's audio is
   *captured instead of discarded*. You get an action bar + sound cue. Every subsequent trigger
   while the window is open just *refreshes* the timer rather than reopening.
3. **Capture.** Simple Voice Chat hands Transcribe Opus microphone packets. Transcribe decodes
   them to 48 kHz mono PCM with `opus4j`.
4. **Recognition.** On window close, the buffered PCM is submitted to the configured
   `SpeechRecognitionEngine`:
   - `INTERNAL` - Vosk, grammar-constrained to your word list, resampled to 16 kHz.
   - `EXTERNAL` - a WebSocket message to your own service.
5. **Match.** The result is accepted **only if it exactly equals one of your grammar words**
   (case-insensitive). Anything else - including the `[unk]` token - is treated as "nothing
   heard". This is what stops background conversation from casting spells.
6. **Dispatch.** On a match, `VoiceWordSpokenEvent` fires on the main thread. If not cancelled,
   every entry in `recognition.console-commands` runs as the console with `%player%` and
   `%keyword%` substituted.

Only step 2's window makes the plugin do work. Outside a window, microphone packets are dropped
before decoding - that is where the near-zero idle CPU cost comes from.

## 2. Installation

### Step 1 - Simple Voice Chat, server side

Install the Bukkit/Paper build of Simple Voice Chat into `plugins/` and start the server once.
Follow the [SVC setup wiki](https://modrepo.de/minecraft/voicechat/wiki/setup) - the
`voicechat-server.properties` file it generates lives in the SVC plugin folder, not this one.

Transcribe cannot enable without it. A successful start logs:

```
[Transcribe] Registered Transcribe with the Simple Voice Chat service.
[Transcribe] Simple Voice Chat API handshake complete.
```

If instead you see *"Simple Voice Chat is not installed on this server"* or *"its Bukkit
service is not available yet"*, see [Troubleshooting](#11-troubleshooting).

### Step 2 - Simple Voice Chat, client side

Every player who wants to cast spells needs the mod (Fabric/Forge/NeoForge) matching their
loader. In-game: **Options -> Controls -> Microphone** to pick an input device, then confirm
Simple Voice Chat's mic level meter moves when you talk. If the meter doesn't move, Transcribe
will never hear anything either - fix that first.

Also make sure each player is in a voice channel, or SVC may not forward their mic stream at
all. A plugin that assigns per-player channels is the easiest route; `/voicechat <player>` also
works for a one-off.

### Step 3 - Transcribe

Drop `Transcribe-<version>.jar` into `plugins/` and start the server. It generates
`plugins/Transcribe/config.yml`, and the console should show:

```
[Transcribe] InternalVoskEngine ready. grammar=["[unk]","fire","light","ice"] targetSampleRateHz=16000
[Transcribe] Transcribe enabled: engine=INTERNAL activation-window=3.0s debug-level=0
```

### Step 4 - Install a Vosk model (INTERNAL mode only)

1. Download `vosk-model-small-en-us-0.15` (~40 MB) from <https://alphacephei.com/vosk/models>.
2. Unzip it into `plugins/Transcribe/models/`, so you end up with:

   ```
   plugins/Transcribe/models/vosk-model-small-en-us-0.15/
   |-- am/
   |-- conf/
   |-- graph/
   ...
   ```

   `engine.internal.model-path` is resolved **relative to the plugin's own data folder**, so
   the default value `models/vosk-model-small-en-us-0.15` means the above path.
3. Do **not** put the zip there - it must be the unzipped directory containing `am/`, `graph/`,
   `conf/`. If it's wrong, Transcribe logs *"Vosk model directory does not exist or is not a
   directory"* and refuses to enable.

Skip this entirely if you use `engine.mode: EXTERNAL`.

### Step 5 - Configure and reload

Edit `plugins/Transcribe/config.yml`, then run `/transcribe reload`.

> `config.yml` is written once on first start and never overwritten, so your edits survive
> upgrades. A bad config does not take the server down: `/transcribe reload` builds the new
> engine *first* and only swaps it in if it succeeds, otherwise it names the broken key and
> leaves the previous working configuration running.

## 3. Configuration reference

### `debug.level`

How much `[Transcribe debug]` output to log (at INFO level, so it survives most log filters).
Takes effect on `/transcribe reload`.

| Level | Shows |
| --- | --- |
| `0` | Off. Only enable/disable/reload lines. **Default.** |
| `1` | Listen windows opening, each recognition result (`'fire'` or `(no keyword recognized)`) and how long it took. Answers "is it working at all?" |
| `2` | + which trigger fired for whom, windows that *refreshed* instead of opening, capture sessions opening/closing with sample counts |
| `3` | + every individual microphone packet decoded, with byte/sample counts. Extremely chatty; for deep audio-pipeline debugging only. |

Start at `2` whenever something isn't working.

### `engine.*`

#### `engine.mode`

- `INTERNAL` - Vosk embedded in this JVM (JNI/native).
  - No external process, no network hop, lower latency, audio never leaves the box.
  - Costs some server CPU/RAM per recognition - largely neutralised by the constrained grammar.
  - Requires a Vosk model on the server.
- `EXTERNAL` - audio is streamed to a separate STT service over a WebSocket.
  - Keeps the Minecraft server's footprint minimal; run STT on separate/GPU hardware, swap
    backends, or scale it.
  - Adds a network hop and one more thing to monitor.
  - You own running, securing, and keeping it online.

#### `engine.sample-rate-hertz` (default `16000`)

The rate captured PCM is delivered at before recognition. SVC gives Transcribe 48 kHz mono, so
this is a *resample target*. Vosk models expect 16000; leave it alone unless you know your
capture pipeline differs.

#### `engine.internal.model-path`

Unpacked Vosk model directory, relative to `plugins/Transcribe/`. Must be unzipped.

#### `engine.internal.grammar`

**The most important setting in the file.** The list of words players may say. It's fed
straight into Vosk as a JSON grammar, and a match must be an exact (case-insensitive) member of
this list.

```yaml
grammar:
  - fire
  - light
  - ice
```

Picking good words:

- **Short and phonetically distinct.** `fire` vs `light` is easy; `fire` vs `fyre` is not.
- **Only words your server reacts to.** Every entry should map to a real spell or command.
- **Single words.** Keep entries simple - this is a keyword grammar, not a phrase grammar.
- **Casing is irrelevant** - everything is normalised to lowercase.
- **More words costs accuracy.** Ten is easy; forty starts fighting itself. Consider one model
  per category, or per-word confidence thresholds in your own listener, if you need a big set.
- **Empty list is rejected** - an unconstrained grammar would defeat the whole CPU-gating
  design, so the plugin refuses to enable rather than quietly melting your server.

#### `engine.external.uri`

`ws://` or `wss://` endpoint of your STT service. Default `ws://localhost:2700` matches the
bundled `server.py`. If the service isn't on the same host, use `wss://` behind a TLS
terminating reverse proxy - raw voice audio should never cross an untrusted network in the clear.

Note `localhost` means *the Minecraft server's* localhost, not the STT box's.

#### `engine.external.request-timeout-millis` (default `5000`)

Ceiling on how long a single transcription request may take before that request fails. Bounds
worst-case latency if the service hangs; healthy connections are unaffected.

### `activation.*`

#### `activation.window-seconds` (default `3.0`)

How long a Listen Window stays open. Roughly "how long a player has to finish saying the word
after they trigger it."

- Too short and people get cut off mid-word.
- Too long and the window stays open through unrelated conversation, increasing false triggers.
  Since triggering again while open just refreshes the timer, a chatty moment can keep a window
  alive.
- `1.5`-`2.5` is a good range for single short words; `3.0` is forgiving.

#### `activation.triggers`

Any combination of these. The default is `["SNEAK_OFF_HAND"]`:

| Trigger | Player action |
| --- | --- |
| `OFF_HAND` | Press `F` (swap hands) |
| `SNEAK_OFF_HAND` | Sneak + `F` |
| `LEFT_CLICK` | Left click air or a block |
| `SNEAK_LEFT_CLICK` | Sneak + left click |
| `RIGHT_CLICK` | Right click air or a block |
| `SNEAK_RIGHT_CLICK` | Sneak + right click |
| `HOTBAR_SLOT` | Scroll/select to a slot in `activation.hotbar-slots` |
| `SNEAK_HOTBAR_SLOT` | Same, while sneaking |

Notes:

- `F` is the vanilla swap-offhand keybind. If you've rebound it, use whatever you bound.
- Sneak variants are almost always what you want on a survival server: they don't collide with
  ordinary play, and `SNEAK_OFF_HAND` reads as a deliberate "cast" gesture.
- Click triggers **do not fire when attacking an entity** - Bukkit emits an entity damage event
  there, not an interact event, and Transcribe doesn't hook that. Sneak-clicking a mob won't
  open a window.
- Unknown entries are ignored with a startup warning, not a crash.

> **Renamed in an earlier version:** `SWAP_HAND` / `SNEAK_SWAP_HAND` are now `OFF_HAND` /
> `SNEAK_OFF_HAND` - same behaviour, clearer name. Update old configs or those entries are
> silently skipped (with a warning).

#### `activation.hotbar-slots`

Slots `1`-`9` (1-indexed, as in the UI) used by `HOTBAR_SLOT` / `SNEAK_HOTBAR_SLOT`. Ignored
unless one of those triggers is enabled. Default `[9]`.

Handy for a "scroll to your spell slot, then talk" flow, or a dedicated cast slot.

#### `activation.cancel-trigger-events` (default `true`)

Whether to cancel the underlying Bukkit event so the vanilla action doesn't happen. With
`SNEAK_OFF_HAND` and this `true`, players don't visibly swap their offhand item while casting.
Applies to every trigger. Set `false` if you want the vanilla action to still occur.

#### `activation.feedback.*`

Fully configurable open/close cues. Both transitions are independently configurable, and any
blank value disables just that piece.

```yaml
feedback:
  action-bar:
    enabled: true
    open-message: "&b🎤 Listening..."
    close-message: ''
  sound:
    enabled: true
    open-sound: "minecraft:block.note_block.pling"
    open-volume: 0.5
    open-pitch: 1.8
    close-sound: "minecraft:block.note_block.bass"
    close-volume: 0.4
    close-pitch: 0.8
```

- Messages support `&` legacy colour codes (`&b` = aqua). `''` shows nothing.
- Sound keys accept anything `Player#playSound()` accepts: vanilla
  (`minecraft:block.note_block.pling`) **or** a resource-pack custom sound
  (`mypack:spells.cast`).
- Volume must be `>= 0`, pitch must be `> 0` - an invalid value fails the reload with a clear
  message and keeps the old config.

Good default feel: a bright, high-pitched "open" and a low "close", so players can tell by ear
that the window shut. Consider a `close-message` like `&7heard nothing` if misfires are
confusing people.

#### `activation.placeholderapi.*`

A polled condition trigger, in addition to the gesture triggers.

```yaml
placeholderapi:
  placeholder: '%yourstatus_frozen%'
  expected-value: 'true'
  poll-interval-ticks: 10
```

Leave `placeholder` as `''` (the default) to disable this entirely - a blank placeholder is
treated as "not configured". The placeholder can come from any PlaceholderAPI expansion; the
example above stands in for whatever tracks a "frozen"/"casting" state on your server.

How it works: every `poll-interval-ticks` (default `10` = twice a second), Transcribe resolves
the placeholder for every online player and opens/refreshes a Listen Window for anyone whose
value equals `expected-value`. Because it's a *level-triggered* condition rather than
edge-triggered, a player who satisfies it stays "listening" until it stops being true - so
pair it with a short `window-seconds`, or gate it behind a status effect that a player only has
while deliberately holding still.

- Requires PlaceholderAPI (a `softdepend`). If it's configured but PAPI isn't installed,
  Transcribe logs a warning and disables just this trigger rather than failing.
- `expected-value` is compared as a **string**, case-insensitively. Quote it -
  `expected-value: 'true'`, not `expected-value: true`.
- Polls run on the main thread across all online players; don't set the interval to 1 tick on
  a busy server.

## 4. Wiring up your spells

Four approaches, from simplest to most flexible. They compose - use one, or mix.

### a) One command for everything (recommended starting point)

Delegate the branching to a command plugin that understands `%keyword%`:

```yaml
recognition:
  console-commands:
    - "spellcast trigger %player% %keyword%"
```

Available placeholders:

| Placeholder | Value |
| --- | --- |
| `%player%` | The speaking player's exact username |
| `%keyword%` | The recognized word, always lowercase, always a member of your grammar |

### b) A command per word

Straightforward when each word does the same thing:

```yaml
recognition:
  console-commands:
    - "effect give %player% minecraft:glowing 5 0 true"
    - "execute as %player% run say cast"
```

All listed commands run in order, once, per recognized word.

### c) Vanilla commands, branching with a scoreboard

If you don't want a command plugin, set a score and test it:

```yaml
recognition:
  console-commands:
    - "scoreboard players set %player% transcribe_keyword 1"
    - "execute if score %player% transcribe_keyword matches 1 run function spells:on_cast"
```

### d) Your own plugin - `VoiceWordSpokenEvent`

For cooldowns, mana, per-spell logic, or silencing effects, listen to the event. It fires on
the main thread and is **cancellable**; cancelling suppresses the `console-commands` entirely.

```java
package com.example.spells;

import org.eyman_.events.VoiceWordSpokenEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class SpellListener implements Listener {

    @EventHandler
    public void onVoiceWord(VoiceWordSpokenEvent event) {
        String keyword = event.getKeyword();          // always lowercase, always in-grammar
        Player player = event.getPlayer();
        float confidence = event.getConfidence();

        if (keyword.equals("fire") && cooldownReady(player)) {
            castFireball(player);
        } else {
            event.setCancelled();   // swallow it: no configured commands run
        }
    }
}
```

Add to your `plugin.yml`:

```yaml
depend: [Transcribe]
```

Get the event class by shading or depending on the Transcribe jar:

```kotlin
compileOnly("org.eyman_:Transcribe:<version>")
```

### Forcing a window open - `TranscribeAPI`

If you'd rather trigger listening from a wand item, a GUI, or a skill of your own:

```java
import org.eyman_.api.TranscribeAPI;
import java.time.Duration;

TranscribeAPI api = TranscribeAPI.get();

boolean opened = api.forceStartListenWindow(player, Duration.ofSeconds(3));
// true  = a new window opened
// false = one was already open; its timer was refreshed

if (api.isListening(player)) { /* ... */ }

api.forceStopListenWindow(player);   // close early
```

| Method | Notes |
| --- | --- |
| `TranscribeAPI.get()` | The singleton. Throws `IllegalStateException` if Transcribe isn't enabled. |
| `isListening(Player \| UUID)` | Is a window currently open? |
| `forceStartListenWindow(Player)` | Uses `DEFAULT_WINDOW_DURATION` (4s). |
| `forceStartListenWindow(Player, Duration)` | Custom duration; must be positive. |
| `forceStopListenWindow(Player)` | Closes early; returns whether one was open. |

Thread-safe. Note the `forceStartListenWindow` **return value**: `true` means a new window
opened (play your "now listening" cue), `false` means one was already open and merely got its
timer extended. Playing the open cue unconditionally is a common bug - it re-triggers on every
refresh.

## 5. Choosing a trigger - practical advice

- **`SNEAK_OFF_HAND` (default)** - the best all-rounder on survival servers. Deliberate, doesn't
  collide with mining/building/combat, and with `cancel-trigger-events: true` players won't
  accidentally swap items.
- **`SNEAK_HOTBAR_SLOT` on one dedicated slot** - very natural. "Select your spell slot, say the
  word." Pairs well with a resource pack that puts spell icons in that slot.
- **`SNEAK_LEFT_CLICK` / `SNEAK_RIGHT_CLICK`** - intuitive, but make sure
  `cancel-trigger-events: true` or players can't mine or place blocks while casting.
- **Avoid non-sneak variants** on a busy server. Plain `LEFT_CLICK` fires on every block break
  and every door open, and `OFF_HAND` fires whenever anyone swaps hands.
- **Combine** freely. `["SNEAK_OFF_HAND", "SNEAK_HOTBAR_SLOT"]` gives players two ways in.

## 6. Tuning recognition quality

If words aren't being recognised, work down this list:

1. **Check the mic first.** Simple Voice Chat's in-game mic meter must move when you talk. If it
   doesn't, nothing else matters. This is the single most common cause of "it never works."
2. **Make the words more distinct.** `fire` and `fireball` in the same grammar is asking for
   trouble. Short, vowel-heavy, unrelated words win: `fire`, `light`, `ice`.
3. **Trim the grammar.** Fewer words = better accuracy. Ten is comfortable.
4. **Lengthen the window.** `3.0` -> `4.0` if people are being cut off mid-word. Say the word
   *immediately* after triggering.
5. **Watch your levels.** `debug.level: 2` and read the log. You want
   `Recognition result for <uuid>: 'fire' (12.3ms)`. `(no keyword recognized)` means audio
   arrived but didn't match the grammar - a vocabulary problem, not a mic problem. No line at
   all means no window opened, or no audio arrived.
6. **Reduce noise.** A grammar-constrained recognizer in a loud room will still misfire. The
   PlaceholderAPI trigger is a good way to gate listening to quiet moments.
7. **Consider a bigger model** - but only after the above. `vosk-model-en-us-0.22` (1.8 GB) is
   usually *worse* for a 3-word grammar than the small model, and much heavier.

## 7. EXTERNAL engine - running your own STT service

Use this if you want the Minecraft server's CPU/RAM untouched, want to run STT on a GPU box, or
want to swap in a different model or engine entirely.

### The wire protocol

One WebSocket connection carries many sequential segments.

**Client (Transcribe) -> Server:**

```
text    {"type":"start","requestId":"<uuid>","playerId":"<uuid>","sampleRate":16000}
binary  raw 16-bit little-endian mono PCM samples   (one or more frames per segment)
text    {"type":"end","requestId":"<uuid>"}
```

**Server -> Client:**

```
text    {"requestId":"<uuid>","text":"fire","confidence":0.94}
   or   {"requestId":"<uuid>","error":"..."}
```

- `text` must be present and may be `""` - an empty string means "heard audio, matched no
  keyword", which is a **normal success**, not an error. Omitting the field entirely is not
  correct.
- `requestId` correlates the reply; many players multiplex over the one socket.
- Any text not in your grammar list is discarded by Transcribe anyway, so echoing the raw
  transcript is fine - but returning `""` unless it's a grammar word saves work.
- Return `error` for genuine failures (model not loaded, decode crash). Transcribe surfaces
  those as a warning and moves on.

### Running the bundled reference server

`server.py` in this repository is a complete, working implementation of that protocol.

```bash
pip install websockets vosk

python3 server.py \
  --model /path/to/vosk-model-small-en-us-0.15 \
  --grammar fire,light,ice \
  --host 0.0.0.0 \
  --port 2700
```

| Flag | Default | Meaning |
| --- | --- | --- |
| `--model` | *required* | Path to an unpacked Vosk model directory |
| `--grammar` | `fire,light,ice` | Comma-separated vocabulary - **must match** your `config.yml` grammar's intent |
| `--host` | `0.0.0.0` | Bind interface |
| `--port` | `2700` | Bind port |
| `--sample-rate` | `16000` | Fallback if a `start` message omits `sampleRate` |
| `--log-level` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `--verbose-vosk` | off | Don't suppress Vosk's native Kaldi logging (very chatty) |

`--grammar` here is the service's own vocabulary. Keep it in sync with your `config.yml`
grammar, or the two will disagree about what counts as a keyword.

Then point Transcribe at it:

```yaml
engine:
  mode: EXTERNAL
  external:
    uri: ws://your-stt-host:2700
    request-timeout-millis: 5000
```

and `/transcribe reload`. You should see `Connected to external STT service at ws://...`.

### Operational notes

- **Reconnection is automatic** with exponential backoff (1s doubling to a 30s cap). On
  reconnect, in-flight requests are failed fast rather than hanging.
- **One segment at a time per connection.** The bundled `server.py` does not demultiplex
  concurrent in-flight segments on a single connection (binary frames carry no `requestId` of
  their own) - concurrent players are served by concurrent connections, which `websockets`
  handles natively. If you need true same-connection interleaving, add a small per-frame header
  carrying the `requestId` on both ends.
- **Use `wss://` off-box.** Terminate TLS with a reverse proxy (nginx/Caddy) and point
  `engine.external.uri` at it. Never ship raw voice audio over plaintext to a remote host.
- `server.py` is a **reference implementation, not a hardened deployment.** Put it behind a
  proxy, add auth, and run it under a process supervisor.
- To use something other than Vosk, just implement the protocol above - the plugin doesn't care
  what's behind it.

## 8. Commands & permissions

| Command | Description | Permission | Default |
| --- | --- | --- | --- |
| `/transcribe reload` | Re-read `config.yml` and rebuild the engine + triggers | `transcribe.reload` | op |

```yaml
permissions:
  transcribe.reload:
    default: op
```

`/transcribe reload` is safe: it builds the replacement config objects *before* tearing down
the running ones, so a syntax or value error leaves the previous working setup untouched and
tells you exactly what was wrong.

## 9. Performance & platform notes

- **Idle cost is effectively zero.** Microphone packets are dropped before decoding unless
  `isListening()` is true for that player.
- **Recognition is off-thread.** Vosk runs on virtual threads (one `Recognizer` per request, one
  shared read-only `Model`, so no locking); the WebSocket client uses its own network thread.
  Only the resulting event and command dispatch touch the main thread.
- **Constrained grammar is the main lever.** A handful of words collapses the decoder's search
  space. That's what makes `INTERNAL` mode viable on a production server.
- **Vosk's native logging is quietened** to warnings on startup.
- **Bundled native libraries** (for `INTERNAL` mode and the Opus decoder):
  - Vosk: Linux x86_64, macOS, Windows x86_64
  - opus4j: Linux / macOS / Windows, x64 and arm64
- **Folia is not supported** - Transcribe uses the standard Bukkit scheduler, which Folia
  rejects.
- The shaded jar is ~31 MB because it bundles the Vosk, JNA, and Opus natives. If you're using
  `EXTERNAL` mode only, those are still included; the plugin shades them unconditionally so a
  single jar works in every mode.

## 10. Developer API reference

### Events

**`org.eyman_.events.VoiceWordSpokenEvent`** - `Cancellable`, fired on the **main thread**.

| Member | Type | Notes |
| --- | --- | --- |
| `getPlayer()` | `Player` | The speaker. Guaranteed online at fire time. |
| `getKeyword()` | `String` | Always lowercase, always an exact member of your grammar |
| `getConfidence()` | `float` | Recognizer confidence |

Cancelling prevents `recognition.console-commands` from running. This is the intended place for
cooldowns, mana costs, and silence/quiet effects.

### API

**`org.eyman_.api.TranscribeAPI`** - thread-safe singleton, see
[§4d](#d-your-own-plugin---voicewordspokenevent) for the full method list.

**`org.eyman_.engine.SpeechRecognitionEngine`** - the strategy interface, if you want to swap
in your own backend:

```java
public interface SpeechRecognitionEngine {
    @NotNull CompletableFuture<String> processAudio(
            @NotNull UUID playerId, @NotNull short[] pcm16Samples, int sampleRateHertz);
    void shutdown();
}
```

Return the recognized keyword, or `""` for nothing. The returned future may complete on any
thread; Transcribe marshals the result onto the main thread before firing the event.

## 11. Troubleshooting

**"Transcribe cannot function without Simple Voice Chat" / plugin disables itself**
Simple Voice Chat isn't installed. `depend: [voicechat]` means Bukkit won't even try to enable
Transcribe. Install SVC and restart.

**"Simple Voice Chat is installed but its Bukkit service is not available yet (unexpected load order)"**
A genuine load-order problem - SVC registered after Transcribe. A full server restart fixes it;
a `/reload` can trigger it.

**Nothing happens when I trigger, and `debug.level: 2` shows no "Trigger fired" line**
The trigger isn't matching. Check `activation.triggers` spelling (unknown values are skipped
with a warning), that you're sneaking/not sneaking as required, and for hotbar triggers that
the slot is in `activation.hotbar-slots`.

**"Trigger fired" logs, but no Listen Window line**
The window opened and was immediately refreshed, or `window-seconds` is misconfigured. Level 2
distinguishes "opened" from "refreshed".

**Listen window opens, but always `(no keyword recognized)`**
Audio arrived and was decoded, but nothing matched the grammar. Work through
[§6](#6-tuning-recognition-quality). First confirm the SVC mic meter moves.

**Nothing at all in the log, no window, no trigger**
No microphone packets are arriving. The client probably doesn't have the SVC **mod**, has no
voice channel, or has the wrong mic device selected.

**"Failed to create an opus4j decoder for &lt;uuid&gt;"**
opus4j's native library failed to load - usually an unsupported CPU architecture. Check the
platform list in [§9](#9-performance--platform-notes).

**"Vosk model directory does not exist or is not a directory"**
`engine.internal.model-path` is wrong, relative to the wrong place, or you left the model
zipped. It must resolve to the unzipped folder containing `am/`, `graph/`, `conf/`.

**"vocabulary must not be empty"**
`engine.internal.grammar` has no entries. An unconstrained recognizer would destroy
performance, so the plugin refuses to start.

**Nothing works after editing config**
`/transcribe reload` and read the error - it names the bad key and keeps the old config
running. Remember the command itself needs `transcribe.reload` (op by default).

**`recognition.console-commands` do nothing but the window and recognition work**
Either the event is being cancelled by another plugin, or your command is failing. Commands run
as the **console** (full permission), so `%player%` is the only player context available - if
your command needs to act *as* the player, target them explicitly. Commands have no leading
`/`. Check the console for the command's own error output.

**EXTERNAL mode: "WebSocket connection closed" / constant reconnects**
The service isn't reachable at `engine.external.uri`. Remember the default is
`ws://localhost:2700` - that's *the Minecraft server's* localhost, not the STT box's. Use the
STT host's real address, and `wss://` if it's off-box.

## License

GPL-3.0. Simple Voice Chat is an independent project under its own license; it is a required
runtime dependency and is neither bundled nor redistributed here.
