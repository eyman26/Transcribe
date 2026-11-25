package org.eyman_.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class InternalVoskEngine implements SpeechRecognitionEngine {

    private static final int DEFAULT_TARGET_SAMPLE_RATE_HZ = 16_000;

    private static final String UNKNOWN_TOKEN = "[unk]";

    private final Gson gson = new Gson();
    private final Logger logger;
    private final Model model;
    private final String grammarJson;
    private final Set<String> allowedKeywords;
    private final int targetSampleRateHz;
    private final ExecutorService recognitionExecutor;
    private volatile boolean shutdown;

    public InternalVoskEngine(Path modelDirectory,
                               List<String> vocabulary,
                               int targetSampleRateHz,
                               Logger logger) throws IOException {
        this.logger = logger;

        if (vocabulary.isEmpty()) {
            throw new IllegalArgumentException(
                    "grammar is empty, add at least one word");
        }
        if (targetSampleRateHz <= 0) {
            throw new IllegalArgumentException("targetSampleRateHz must be positive, was: " + targetSampleRateHz);
        }
        if (!modelDirectory.toFile().isDirectory()) {
            throw new IOException("Vosk model folder not found: " + modelDirectory);
        }

        // Vosks native Kaldi logs straight to stderr; quiet it down.
        LibVosk.setLogLevel(LogLevel.WARNINGS);

        this.model = new Model(modelDirectory.toAbsolutePath().toString());
        this.allowedKeywords = buildAllowedKeywordSet(vocabulary);
        this.grammarJson = buildGrammarJson(allowedKeywords);
        this.targetSampleRateHz = targetSampleRateHz;
        this.recognitionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("transcribe-vosk-worker-", 0).factory());

        this.logger.info("Vosk ready, grammar=" + grammarJson
                + " targetSampleRateHz=" + targetSampleRateHz);
    }

    public InternalVoskEngine(Path modelDirectory,
                               List<String> vocabulary,
                               Logger logger) throws IOException {
        this(modelDirectory, vocabulary, DEFAULT_TARGET_SAMPLE_RATE_HZ, logger);
    }

    @Override
    public CompletableFuture<String> processAudio(UUID playerId,
                                                    short[] pcm16Samples,
                                                    int sampleRateHertz) {
        return processAudioDetailed(playerId, pcm16Samples, sampleRateHertz).thenApply(RecognitionResult::text);
    }

    @Override
    public CompletableFuture<RecognitionResult> processAudioDetailed(UUID playerId,
                                                                     short[] pcm16Samples,
                                                                     int sampleRateHertz) {

        if (shutdown) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("InternalVoskEngine has already been shut down."));
        }
        if (pcm16Samples.length == 0) {
            return CompletableFuture.completedFuture(RecognitionResult.NONE);
        }

        return CompletableFuture.supplyAsync(
                () -> recognizeBlocking(playerId, pcm16Samples, sampleRateHertz), recognitionExecutor);
    }

    private RecognitionResult recognizeBlocking(UUID playerId,
                                      short[] rawSamples,
                                      int sourceSampleRateHertz) {
        final short[] samples = (sourceSampleRateHertz == targetSampleRateHz)
                ? rawSamples
                : resampleLinear(rawSamples, sourceSampleRateHertz, targetSampleRateHz);

        try (Recognizer recognizer = new Recognizer(model, targetSampleRateHz)) {
            recognizer.setGrammar(grammarJson);
            recognizer.setWords(true);

            final byte[] pcmBytes = shortsToLittleEndianBytes(samples);
            recognizer.acceptWaveForm(pcmBytes, pcmBytes.length);

            final String resultJson = recognizer.getFinalResult();
            logger.info("[Transcribe debug] raw Vosk result for " + playerId + ": " + resultJson);
            return RecognitionResult.fromVoskJson(resultJson, allowedKeywords);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Vosk recognizer failed for player " + playerId, e);
            throw new CompletionException(e);
        }
    }

    private static Set<String> buildAllowedKeywordSet(List<String> vocabulary) {
        final Set<String> normalized = new LinkedHashSet<>(vocabulary.size());
        for (String word : vocabulary) {
            normalized.add(word.toLowerCase(Locale.ROOT).trim());
        }
        return Set.copyOf(normalized);
    }

    private String buildGrammarJson(Set<String> vocabulary) {
        final LinkedHashSet<String> words = new LinkedHashSet<>(vocabulary);
        words.add(UNKNOWN_TOKEN);
        final JsonArray array = new JsonArray();
        words.forEach(array::add);
        return gson.toJson(array);
    }

    // Naive linear interpolation, fine for keyword recognition, not general audio.
    private static short[] resampleLinear(short[] input, int fromHz, int toHz) {
        if (input.length == 0 || fromHz == toHz) {
            return input;
        }
        final double ratio = (double) toHz / (double) fromHz;
        final int outputLength = Math.max(1, (int) Math.round(input.length * ratio));
        final short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            final double sourceIndex = i / ratio;
            final int lowIndex = Math.min((int) Math.floor(sourceIndex), input.length - 1);
            final int highIndex = Math.min(lowIndex + 1, input.length - 1);
            final double fraction = sourceIndex - lowIndex;
            final double interpolated = input[lowIndex] + (input[highIndex] - input[lowIndex]) * fraction;
            output[i] = (short) Math.round(interpolated);
        }
        return output;
    }

    private static byte[] shortsToLittleEndianBytes(short[] samples) {
        final ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;

        recognitionExecutor.shutdown();
        try {
            if (!recognitionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                recognitionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recognitionExecutor.shutdownNow();
        }

        try {
            model.close();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error closing Vosk model", e);
        }

        logger.info("InternalVoskEngine shut down cleanly.");
    }
}