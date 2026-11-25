package org.eyman_.audio;

import org.eyman_.TranscribePlugin;
import org.eyman_.activation.CueSettings;
import org.eyman_.api.TranscribeAPI;
import org.eyman_.engine.SpeechRecognitionEngine;

import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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

    private static final int MICROPHONE_CHANNELS = 1;

    private final TranscribePlugin plugin;
    private final Logger logger;
    private final Map<UUID, CaptureSession> sessions = new ConcurrentHashMap<>();
    private volatile int debugLevel;
    private volatile CueSettings cues;

    public TranscribeAudioListener(TranscribePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void setDebugLevel(int debugLevel) {
        this.debugLevel = debugLevel;
    }

    public void setCueSettings(CueSettings cues) {
        this.cues = cues;
    }

    // runs on SVC's thread, not main

    public void onMicrophonePacket(MicrophonePacketEvent event) {
        UUID playerId = resolvePlayerId(event);
        if (playerId == null) {
            return;
        }

        // no window = drop the packet before decoding
        if (!TranscribeAPI.get().isListening(playerId)) {
            return;
        }

        CaptureSession session = sessions.get(playerId);
        if (session == null) {
            // window opened but session not created yet, skip this packet
            return;
        }

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) {
            return;
        }

        try {
            short[] pcm = session.decoder.decode(opusData);
            if (pcm != null && pcm.length > 0) {
                session.append(pcm);
                if (debugLevel >= 3) {
                    logger.info("[Transcribe debug] " + playerId + ": decoded " + opusData.length
                            + " opus bytes -> " + pcm.length + " PCM samples (buffer now "
                            + session.totalSamples() + " samples)");
                }
            }
        } catch (RuntimeException nativeDecodeFailure) {
            logger.log(Level.WARNING, "opus4j failed to decode a packet for " + playerId
                    + " - dropping this packet and continuing.", nativeDecodeFailure);
        }
    }

    private UUID resolvePlayerId(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) {
            return null;
        }
        ServerPlayer serverPlayer = connection.getPlayer();
        return serverPlayer == null ? null : serverPlayer.getUuid();
    }


    @Override
    public void onWindowOpened(UUID playerId) {
        OpusDecoder decoder;
        try {
            decoder = new OpusDecoder(MICROPHONE_SAMPLE_RATE_HZ, MICROPHONE_CHANNELS);
        } catch (Exception nativeInitFailure) {
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
    public void onWindowClosed(UUID playerId) {
        CaptureSession session = sessions.remove(playerId);
        runOnMainThread(() -> playCloseCue(playerId));

        if (session == null) {
            return;
        }
        try {
            session.decoder.close();
        } catch (RuntimeException closeFailure) {
            logger.log(Level.WARNING, "Failed to close the opus4j decoder for " + playerId, closeFailure);
        }

        short[] pcm = session.flatten();
        if (debugLevel >= 2) {
            logger.info("[Transcribe debug] Audio capture session closed for " + playerId
                    + " - " + pcm.length + " total PCM samples captured");
        }
        if (pcm.length == 0) {
            return;
        }

        SpeechRecognitionEngine engine;
        try {
            engine = plugin.getSpeechEngine();
        } catch (IllegalStateException notReadyYet) {
            return; // plugin is between reload() steps or shutting down
        }

        long startNanos = System.nanoTime();
        engine.processAudioDetailed(playerId, pcm, MICROPHONE_SAMPLE_RATE_HZ)
                .thenAccept(result -> {
                    String text = result.text();
                    if (debugLevel >= 1) {
                        double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
                        logger.info("[Transcribe debug] Recognition result for " + playerId + ": "
                                + (text.isEmpty() ? "(no keyword recognized)"
                                        : String.format("'%s' (confidence %.2f)", text, result.confidence()))
                                + String.format(" (%.1fms)", millis));
                    }
                    if (text.isEmpty()) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        plugin.handleRecognizedSpeech(player, text, result.confidence());
                    }
                })
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Speech recognition failed for " + playerId, error);
                    return null;
                });
    }


    private void playCloseCue(UUID playerId) {
        CueSettings activeCues = this.cues;
        if (activeCues == null) {
            return; // onEnable() hasn't finished calling setCueSettings() yet
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        activeCues.playCloseCue(player);
    }

    private void runOnMainThread(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static final class CaptureSession {
        private final OpusDecoder decoder;
        private final List<short[]> chunks = Collections.synchronizedList(new ArrayList<>());

        CaptureSession(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        void append(short[] pcm) {
            chunks.add(pcm);
        }

        int totalSamples() {
            synchronized (chunks) {
                int total = 0;
                for (short[] chunk : chunks) {
                    total += chunk.length;
                }
                return total;
            }
        }

        short[] flatten() {
            synchronized (chunks) {
                short[] out = new short[totalSamples()];
                int offset = 0;
                for (short[] chunk : chunks) {
                    System.arraycopy(chunk, 0, out, offset, chunk.length);
                    offset += chunk.length;
                }
                return out;
            }
        }
    }
}