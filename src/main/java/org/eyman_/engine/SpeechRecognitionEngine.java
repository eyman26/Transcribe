package org.eyman_.engine;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy contract for a speech-to-text backend used by Transcribe. Implementations:
 * {@link InternalVoskEngine} (in-process JNI via {@code org.vosk:vosk}, grammar-constrained) and
 * {@link ExternalWebSocketEngine} (streams PCM to an external STT service).
 *
 * <p>Implementations MUST do all decoding/network I/O off the main thread; the returned
 * {@link CompletableFuture} may complete from any worker thread, and callers are responsible for
 * hopping back onto the main thread before touching Bukkit API.</p>
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
