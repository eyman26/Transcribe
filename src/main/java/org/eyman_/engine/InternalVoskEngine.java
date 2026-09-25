package org.eyman_.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In nprocess STT via the Vosk JNI bindings. One read-only shared {@link Model} for the plugin's
 * lifetime, but a fresh {@link Recognizer} per call, so it's thread safe across concurrent
 * speakers with zero locking.
 */
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

    public InternalVoskEngine(@NotNull final Path modelDirectory,
                               @NotNull final List<String> vocabulary,
                               final int targetSampleRateHz,
                               @NotNull final Logger logger) throws IOException {
        Objects.requireNonNull(modelDirectory, "modelDirectory");
        Objects.requireNonNull(vocabulary, "vocabulary");
        this.logger = Objects.requireNonNull(logger, "logger");

        if (vocabulary.isEmpty()) {
            throw new IllegalArgumentException(
                    "vocabulary must not be empty - an unconstrained Vosk grammar defeats the CPU-gating design goal.");
        }
        if (targetSampleRateHz <= 0) {
            throw new IllegalArgumentException("targetSampleRateHz must be positive, was: " + targetSampleRateHz);
        }
        if (!modelDirectory.toFile().isDirectory()) {
            throw new IOException("Vosk model directory does not exist or is not a directory: " + modelDirectory);
        }

        // Vosks native Kaldi logs straight to stderr; quiet it down.
        LibVosk.setLogLevel(LogLevel.WARNINGS);

        this.model = new Model(modelDirectory.toAbsolutePath().toString());
        this.allowedKeywords = buildAllowedKeywordSet(vocabulary);
        this.grammarJson = buildGrammarJson(allowedKeywords);
        this.targetSampleRateHz = targetSampleRateHz;
        this.recognitionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("transcribe-vosk-worker-", 0).factory());

        this.logger.info(() -> "InternalVoskEngine ready. grammar=" + grammarJson
                + " targetSampleRateHz=" + targetSampleRateHz);
    }

    public InternalVoskEngine(@NotNull final Path modelDirectory,
                               @NotNull final List<String> vocabulary,
                               @NotNull final Logger logger) throws IOException {
        this(modelDirectory, vocabulary, DEFAULT_TARGET_SAMPLE_RATE_HZ, logger);
    }

    @Override
    @NotNull
    public CompletableFuture<String> processAudio(@NotNull final UUID playerId,
                                                    @NotNull final short[] pcm16Samples,
                                                    final int sampleRateHertz) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(pcm16Samples, "pcm16Samples");

        if (shutdown) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("InternalVoskEngine has already been shut down."));
        }
        if (pcm16Samples.length == 0) {
            return CompletableFuture.completedFuture("");
        }

        return CompletableFuture.supplyAsync(
                () -> recognizeBlocking(playerId, pcm16Samples, sampleRateHertz), recognitionExecutor);
    }

    // Runs on a virtual worker thread, each call owns its own Recognizer, so no locking needed.
    @NotNull
    private String recognizeBlocking(@NotNull final UUID playerId,
                                      @NotNull final short[] rawSamples,
                                      final int sourceSampleRateHertz) {
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
            return extractRecognizedKeyword(resultJson);
        } catch (final IOException e) {
            logger.log(Level.WARNING, "Vosk recognizer failed for player " + playerId, e);
            throw new CompletionException(e);
        }
    }

    /**
     * Returns the recognized keyword only if it is an exact member of the constrained grammar
     * anything else (including Vosks unknown token sentinel) is normalized to an empty string
     */
    @NotNull
    private String extractRecognizedKeyword(@NotNull final String resultJson) {
        final String text = switch (safeParseText(resultJson)) {
            case null -> "";
            case String t -> t.trim().toLowerCase(Locale.ROOT);
        };

        if (text.isEmpty() || text.equals(UNKNOWN_TOKEN)) {
            return "";
        }
        return allowedKeywords.contains(text) ? text : "";
    }

    private String safeParseText(@NotNull final String resultJson) {
        try {
            final JsonObject obj = JsonParser.parseString(resultJson).getAsJsonObject();
            if (obj.has("text") && !obj.get("text").isJsonNull()) {
                return obj.get("text").getAsString();
            }
            return "";
        } catch (final RuntimeException malformed) {
            return "";
        }
    }

    @NotNull
    private static Set<String> buildAllowedKeywordSet(@NotNull final List<String> vocabulary) {
        final Set<String> normalized = new LinkedHashSet<>(vocabulary.size());
        for (final String word : vocabulary) {
            normalized.add(word.toLowerCase(Locale.ROOT).trim());
        }
        return Set.copyOf(normalized);
    }

    @NotNull
    private String buildGrammarJson(@NotNull final Set<String> vocabulary) {
        final LinkedHashSet<String> words = new LinkedHashSet<>(vocabulary);
        words.add(UNKNOWN_TOKEN);
        final JsonArray array = new JsonArray();
        words.forEach(array::add);
        return gson.toJson(array);
    }

    // Naive linear interpolation, fine for keyword recognition, not general audio.
    @NotNull
    private static short[] resampleLinear(@NotNull final short[] input, final int fromHz, final int toHz) {
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

    @NotNull
    private static byte[] shortsToLittleEndianBytes(@NotNull final short[] samples) {
        final ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (final short sample : samples) {
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
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            recognitionExecutor.shutdownNow();
        }

        try {
            model.close();
        } catch (final RuntimeException e) {
            logger.log(Level.WARNING, "Error closing Vosk model", e);
        }

        logger.info("InternalVoskEngine shut down cleanly.");
    }
}