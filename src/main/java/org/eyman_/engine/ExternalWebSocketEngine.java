package org.eyman_.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ExternalWebSocketEngine implements SpeechRecognitionEngine {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Logger logger;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<UUID, CompletableFuture<RecognitionResult>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("transcribe-ws-reconnect", 0).factory());
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private volatile TranscribeSocketClient client;
    private volatile CompletableFuture<Void> connectionReady = new CompletableFuture<>();
    private volatile boolean shutdown;

    public ExternalWebSocketEngine(URI endpoint,
                                    Duration requestTimeout,
                                    Logger logger) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.logger = logger;

        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive, was: " + requestTimeout);
        }

        connect();
    }

    private synchronized void connect() {
        if (shutdown) {
            return;
        }
        this.connectionReady = new CompletableFuture<>();
        final TranscribeSocketClient newClient = new TranscribeSocketClient(endpoint);
        this.client = newClient;
        newClient.connect(); // non blocking
    }

    private void scheduleReconnect() {
        if (shutdown) {
            return;
        }
        final int attempt = reconnectAttempt.incrementAndGet();
        final long backoffMillis = Math.min(
                INITIAL_BACKOFF.toMillis() * (1L << Math.min(attempt - 1, 10)),
                MAX_BACKOFF.toMillis()
        );
        logger.warning("STT connection lost, retrying in " + backoffMillis + "ms (attempt " + attempt + ")");
        reconnectScheduler.schedule(this::connect, backoffMillis, TimeUnit.MILLISECONDS);
    }

    private void failAllPending(Throwable cause) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(cause));
        pendingRequests.clear();
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
                    new IllegalStateException("ExternalWebSocketEngine has been shut down"));
        }

        final UUID requestId = UUID.randomUUID();
        final CompletableFuture<RecognitionResult> resultFuture = new CompletableFuture<>();
        pendingRequests.put(requestId, resultFuture);

        final CompletableFuture<RecognitionResult> boundedFuture = resultFuture
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pendingRequests.remove(requestId));

        // grab the current future so a reconnect mid-send fails fast
        final CompletableFuture<Void> readySnapshot = connectionReady;

        readySnapshot.whenComplete((v, connectError) -> {
            if (connectError != null) {
                resultFuture.completeExceptionally(connectError);
                return;
            }
            try {
                sendAudioSegment(requestId, playerId, pcm16Samples, sampleRateHertz);
            } catch (RuntimeException e) {
                resultFuture.completeExceptionally(e);
            }
        });

        return boundedFuture;
    }

    private void sendAudioSegment(UUID requestId,
                                   UUID playerId,
                                   short[] pcm16Samples,
                                   int sampleRateHertz) {
        final TranscribeSocketClient activeClient = this.client;
        if (activeClient == null || !activeClient.isOpen()) {
            throw new IllegalStateException("STT socket not open");
        }

        final StartMessage start = new StartMessage("start", requestId.toString(), playerId.toString(), sampleRateHertz);
        activeClient.send(gson.toJson(start));

        activeClient.send(shortsToLittleEndianBuffer(pcm16Samples));

        final EndMessage end = new EndMessage("end", requestId.toString());
        activeClient.send(gson.toJson(end));
    }

    private static ByteBuffer shortsToLittleEndianBuffer(short[] samples) {
        final ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        buffer.flip();
        return buffer;
    }

    private void handleIncomingMessage(String message) {
        final JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException bad) {
            logger.log(Level.WARNING, "Received bad JSON from external STT service: " + message, bad);
            return;
        }

        if (!json.has("requestId") || json.get("requestId").isJsonNull()) {
            logger.warning("STT reply has no requestId: " + message);
            return;
        }

        final UUID requestId;
        try {
            requestId = UUID.fromString(json.get("requestId").getAsString());
        } catch (IllegalArgumentException e) {
            logger.warning("STT reply has a bad requestId: " + message);
            return;
        }

        final CompletableFuture<RecognitionResult> future = pendingRequests.remove(requestId);
        if (future == null) {
            // timed out already
            return;
        }

        if (json.has("error") && !json.get("error").isJsonNull()) {
            future.completeExceptionally(new RuntimeException("External STT error: " + json.get("error").getAsString()));
            return;
        }

        final String text = (json.has("text") && !json.get("text").isJsonNull())
                ? json.get("text").getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
        final float confidence = (json.has("confidence") && json.get("confidence").isJsonPrimitive())
                ? json.get("confidence").getAsFloat()
                : 1f;
        future.complete(text.isEmpty() ? RecognitionResult.NONE : new RecognitionResult(text, confidence));
    }

    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;

        failAllPending(new CancellationException("ExternalWebSocketEngine is shutting down"));

        reconnectScheduler.shutdownNow();

        final TranscribeSocketClient activeClient = this.client;
        if (activeClient != null) {
            try {
                activeClient.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("External STT engine stopped");
    }

    private record StartMessage(String type, String requestId, String playerId, int sampleRate) {
    }

    private record EndMessage(String type, String requestId) {
    }

    private final class TranscribeSocketClient extends WebSocketClient {

        TranscribeSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            reconnectAttempt.set(0);
            connectionReady.complete(null);
            logger.info("Connected to STT at " + endpoint);
        }

        @Override
        public void onMessage(String message) {
            handleIncomingMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            final IllegalStateException closedError =
                    new IllegalStateException("WebSocket connection closed (code=" + code + ", reason=" + reason + ")");
            connectionReady.completeExceptionally(closedError);
            failAllPending(closedError);

            if (!shutdown) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception ex) {
            logger.log(Level.WARNING, "External STT WebSocket error", ex);
            connectionReady.completeExceptionally(ex);
        }
    }
}