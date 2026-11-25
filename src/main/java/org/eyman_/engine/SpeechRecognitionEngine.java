package org.eyman_.engine;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SpeechRecognitionEngine {

    @NotNull
    CompletableFuture<String> processAudio(@NotNull UUID playerId, @NotNull short[] pcm16Samples, int sampleRateHertz);

    @NotNull
    default CompletableFuture<RecognitionResult> processAudioDetailed(@NotNull UUID playerId,
                                                                      @NotNull short[] pcm16Samples,
                                                                      int sampleRateHertz) {
        return processAudio(playerId, pcm16Samples, sampleRateHertz)
                .thenApply(t -> t.isEmpty() ? RecognitionResult.NONE : new RecognitionResult(t, 1f));
    }

    void shutdown();
}
