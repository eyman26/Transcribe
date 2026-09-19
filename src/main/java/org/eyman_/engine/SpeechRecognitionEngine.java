package org.eyman_.engine;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SpeechRecognitionEngine {

    @NotNull
    CompletableFuture<String> processAudio(@NotNull UUID playerId, @NotNull short[] pcm16Samples, int sampleRateHertz);

    void shutdown();
}
