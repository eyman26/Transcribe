package org.eyman_.audio;

import org.eyman_.TranscribePlugin;
import org.eyman_.api.TranscribeAPI;
import org.eyman_.engine.SpeechRecognitionEngine;

import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TranscribeAudioListener implements TranscribeAPI.WindowLifecycleListener {

    public static final int MICROPHONE_SAMPLE_RATE_HZ = 48_000;

    // Mono, matching opus4j's official microphone-capture usage examples.
    private static final int MICROPHONE_CHANNELS = 1;

    private final TranscribePlugin plugin;
    private final Logger logger;
    private final Map<UUID, CaptureSession> sessions = new ConcurrentHashMap<>();
    private volatile int debugLevel;

    public TranscribeAudioListener(@NotNull final TranscribePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // Called by {@code /transcribe reload} so the debug level takes effect without a restart.
    public void setDebugLevel(final int debugLevel) {
        this.debugLevel = debugLevel;
    }

    // Raw SVC packet ingestion (fires off the main thread, on SVC's own network/audio thread)

    public void onMicrophonePacket(@NotNull final MicrophonePacketEvent event) {
        final UUID playerId = resolvePlayerId(event);
        if (playerId == null) {
            return;
        }

        // The actual gate: if this player has no open Listen Window, the packet is dropped here
        // and never decoded, this is what keeps idle CPU usage near zero.
        if (!TranscribeAPI.get().isListening(playerId)) {
            return;
        }

        final CaptureSession session = sessions.get(playerId);
        if (session == null) {
            // Window is open per TranscribeAPI but our own session map doesn't have an entry yet -
            // onWindowOpened() hasn't run on this listener for it yet. Drop safely rather than
            // decode into nothing; the next packet will very likely find the session in place.
            return;
        }

        final byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) {
            return;
        }

        try {
            final short[] pcm = session.decoder.decode(opusData);
            if (pcm != null && pcm.length > 0) {
                session.append(pcm);
                if (debugLevel >= 3) {
                    logger.info("[Transcribe debug] " + playerId + ": decoded " + opusData.length
                            + " opus bytes -> " + pcm.length + " PCM samples (buffer now "
                            + session.totalSamples() + " samples)");
                }
            }
        } catch (final RuntimeException nativeDecodeFailure) {
            logger.log(Level.WARNING, "opus4j failed to decode a packet for " + playerId
                    + " - dropping this packet and continuing.", nativeDecodeFailure);
        }
    }

    @Nullable
    private UUID resolvePlayerId(@NotNull final MicrophonePacketEvent event) {
        final VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) {
            return null;
        }
        final ServerPlayer serverPlayer = connection.getPlayer();
        return serverPlayer == null ? null : serverPlayer.getUuid();
    }

    // Window lifecycle: start/stop buffering, hand off to the engine, play cues

    @Override
    public void onWindowOpened(@NotNull final UUID playerId) {
        final OpusDecoder decoder;
        try {
            decoder = new OpusDecoder(MICROPHONE_SAMPLE_RATE_HZ, MICROPHONE_CHANNELS);
        } catch (final Exception nativeInitFailure) {
            logger.log(Level.SEVERE, "Failed to create an opus4j decoder for " + playerId
                    + " - this player's speech will not be captured for this window.", nativeInitFailure);
            return;
        }
        sessions.put(playerId, new CaptureSession(decoder));
        if (debugLevel >= 2) {
            logger.info("[Transcribe debug] Audio capture session opened for " + playerId);
        }
    }

    @Override
    public void onWindowClosed(@NotNull final UUID playerId) {
        final CaptureSession session = sessions.remove(playerId);
        runOnMainThread(() -> playCloseCue(playerId));

        if (session == null) {
            return;
        }
        try {
            session.decoder.close();
        } catch (final RuntimeException closeFailure) {
            logger.log(Level.WARNING, "Failed to close the opus4j decoder for " + playerId, closeFailure);
        }

        final short[] pcm = session.flatten();
        if (debugLevel >= 2) {
            logger.info("[Transcribe debug] Audio capture session closed for " + playerId
                    + " - " + pcm.length + " total PCM samples captured");
        }
        if (pcm.length == 0) {
            return;
        }

        final SpeechRecognitionEngine engine;
        try {
            engine = plugin.getSpeechEngine();
        } catch (final IllegalStateException notReadyYet) {
            return; // plugin is between reload() steps or shutting down
        }

        final long startNanos = System.nanoTime();
        engine.processAudio(playerId, pcm, MICROPHONE_SAMPLE_RATE_HZ)
                .thenAccept(text -> {
                    if (debugLevel >= 1) {
                        final double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
                        logger.info("[Transcribe debug] Recognition result for " + playerId + ": "
                                + (text == null || text.isEmpty() ? "(no keyword recognized)" : "'" + text + "'")
                                + String.format(" (%.1fms)", millis));
                    }
                    if (text == null || text.isEmpty()) {
                        return;
                    }
                    final Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        plugin.handleRecognizedSpeech(player, text, 1.0f);
                    }
                })
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Speech recognition failed for " + playerId, error);
                    return null;
                });
    }

    // Closing cue (opening cue already lives in ActivationManager, alongside its trigger) 

    private void playCloseCue(@NotNull final UUID playerId) {
        if (!plugin.getConfig().getBoolean("activation.feedback.sound", true)) {
            return;
        }
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        // Deliberately lower pitch/volume than the opening cue (Sound.BLOCK_NOTE_BLOCK_PLING at
        // 0.5f/1.8f in ActivationManager) so open/close read as a distinct pair, not a repeat.
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.8f);
    }

    private void runOnMainThread(@NotNull final Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Per-player audio accumulator for one open Listen Window. One {@link OpusDecoder} is kept
     * for the session's whole duration rather than recreated per packet, since Opus decoding is
     * stateful/streaming.
     */
    private static final class CaptureSession {
        private final OpusDecoder decoder;
        private final List<short[]> chunks = Collections.synchronizedList(new ArrayList<>());

        CaptureSession(@NotNull final OpusDecoder decoder) {
            this.decoder = decoder;
        }

        void append(@NotNull final short[] pcm) {
            chunks.add(pcm);
        }

        int totalSamples() {
            synchronized (chunks) {
                int total = 0;
                for (final short[] chunk : chunks) {
                    total += chunk.length;
                }
                return total;
            }
        }

        @NotNull
        short[] flatten() {
            synchronized (chunks) {
                final short[] out = new short[totalSamples()];
                int offset = 0;
                for (final short[] chunk : chunks) {
                    System.arraycopy(chunk, 0, out, offset, chunk.length);
                    offset += chunk.length;
                }
                return out;
            }
        }
    }
}