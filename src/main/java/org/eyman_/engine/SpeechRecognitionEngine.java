package org.eyman_.engine;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy contract for a speech-to-text backend used by Transcribe.
 *
 * <p>Two implementations are provided out of the box, selected at runtime via config:</p>
 * <ul>
 *     <li>{@code VoskSpeechEngine} &mdash; in-process JNI recognition via {@code org.vosk:vosk},
 *     initialized with a restricted JSON grammar array so the recognizer's search space (and
 *     therefore CPU usage) stays minimal.</li>
 *     <li>{@code ExternalWebSocketSpeechEngine} &mdash; an async {@code Java-WebSocket} client
 *     that streams PCM frames to an external STT microservice and resolves the future once a
 *     transcript is received.</li>
 * </ul>
 *
 * <h2>Threading contract</h2>
 * <p>Implementations MUST perform all decoding/inference/network I/O off the main server thread.
 * The returned {@link CompletableFuture} may be completed from any worker thread (a JNI callback
 * thread, a virtual thread, or a WebSocket client thread) &mdash; callers are responsible for
 * hopping back onto the main thread via {@link org.bukkit.scheduler.BukkitScheduler} before
 * touching any Bukkit API as a result of the completed future.</p>
 */
public interface SpeechRecognitionEngine {

    /**
     * Submits a single closed audio segment (one "Listen Window" capture) for transcription.
     *
     * <p>This method must return immediately; it must never block the calling thread on
     * network I/O or native inference. Implementations should offload work to a virtual
     * thread executor or a dedicated worker pool.</p>
     *
     * @param playerId       the UUID of the player whose Simple Voice Chat audio is being processed
     * @param pcm16Samples   mono 16-bit PCM samples for the captured Listen Window, in the sample
     *                       rate expected by the engine implementation (engines are responsible
     *                       for resampling if Simple Voice Chat's capture rate differs)
     * @param sampleRateHertz the sample rate, in Hz, of {@code pcm16Samples}
     * @return a future that completes with the lower-cased, trimmed transcription result, or
     *         completes with an empty string if no in-grammar keyword was recognized; the future
     *         completes exceptionally if the engine fails (native error, socket failure, timeout)
     */
    @NotNull
    CompletableFuture<String> processAudio(@NotNull UUID playerId, @NotNull short[] pcm16Samples, int sampleRateHertz);

    /**
     * Releases all resources held by this engine: closes native Vosk model/recognizer handles,
     * or closes the external WebSocket connection and its executor.
     *
     * <p>Must be safe to call exactly once during {@code onDisable()}. Must not throw; any
     * underlying failure should be caught and logged internally by the implementation.</p>
     */
    void shutdown();
}
