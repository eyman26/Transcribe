package org.eyman_.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
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

/**
 * Delegates transcription to a remote STT microservice over one persistent WebSocket connection.
 *
 * <p>Wire protocol: each Listen Window goes out as {@code {"type":"start",...}}, then raw 16-bit
 * little-endian mono PCM, then {@code {"type":"end",...}}; the server replies later on the same
 * connection with {@code {"requestId":"...","text":"...","confidence":0.94}}. Requests are
 * multiplexed by {@code requestId}, so many players' audio can be in flight on one socket.</p>
 *
 * <p>The client auto-reconnects with capped exponential backoff, and every in-flight request is
 * bounded by {@link #requestTimeout} so a silent server can never leak a never-completing future.</p>
 */
public final class ExternalWebSocketEngine implements SpeechRecognitionEngine {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Logger logger;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<UUID, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("transcribe-ws-reconnect", 0).factory());
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private volatile TranscribeSocketClient client;
    private volatile CompletableFuture<Void> connectionReady = new CompletableFuture<>();
    private volatile boolean shutdown;

    /**
     * @param endpoint       {@code ws://} or {@code wss://} URI of the external STT microservice
     * @param requestTimeout maximum time to wait for a transcription result before the returned
     *                        future fails with a {@link java.util.concurrent.TimeoutException}
     * @param logger         logger to use for connection lifecycle and error messages
     */
    public ExternalWebSocketEngine(@NotNull final URI endpoint,
                                    @NotNull final Duration requestTimeout,
                                    @NotNull final Logger logger) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.logger = Objects.requireNonNull(logger, "logger");

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
        newClient.connect(); // non-blocking; onOpen/onError/onClose fire on the client's own network thread
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
        logger.warning(() -> "External STT connection lost; reconnecting in " + backoffMillis + "ms (attempt " + attempt + ")");
        reconnectScheduler.schedule(this::connect, backoffMillis, TimeUnit.MILLISECONDS);
    }

    private void failAllPending(@NotNull final Throwable cause) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(cause));
        pendingRequests.clear();
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
                    new IllegalStateException("ExternalWebSocketEngine has been shut down"));
        }

        final UUID requestId = UUID.randomUUID();
        final CompletableFuture<String> resultFuture = new CompletableFuture<>();
        pendingRequests.put(requestId, resultFuture);

        final CompletableFuture<String> boundedFuture = resultFuture
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pendingRequests.remove(requestId));

        // Capture the connection-ready future in flight at call time; if a reconnect happens
        // mid-flight the *new* connectionReady simply won't be the one we're waiting on, and
        // this send attempt will fail fast (below) rather than silently hang.
        final CompletableFuture<Void> readySnapshot = connectionReady;

        readySnapshot.whenComplete((ignoredVoid, connectError) -> {
            if (connectError != null) {
                resultFuture.completeExceptionally(connectError);
                return;
            }
            try {
                sendAudioSegment(requestId, playerId, pcm16Samples, sampleRateHertz);
            } catch (final RuntimeException sendError) {
                resultFuture.completeExceptionally(sendError);
            }
        });

        return boundedFuture;
    }

    private void sendAudioSegment(@NotNull final UUID requestId,
                                   @NotNull final UUID playerId,
                                   @NotNull final short[] pcm16Samples,
                                   final int sampleRateHertz) {
        final TranscribeSocketClient activeClient = this.client;
        if (activeClient == null || !activeClient.isOpen()) {
            throw new IllegalStateException("WebSocket connection to external STT service is not open");
        }

        final StartMessage start = new StartMessage("start", requestId.toString(), playerId.toString(), sampleRateHertz);
        activeClient.send(gson.toJson(start));

        activeClient.send(shortsToLittleEndianBuffer(pcm16Samples));

        final EndMessage end = new EndMessage("end", requestId.toString());
        activeClient.send(gson.toJson(end));
    }

    @NotNull
    private static ByteBuffer shortsToLittleEndianBuffer(@NotNull final short[] samples) {
        final ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (final short sample : samples) {
            buffer.putShort(sample);
        }
        buffer.flip();
        return buffer;
    }

    private void handleIncomingMessage(@NotNull final String message) {
        final JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            logger.log(Level.WARNING, "Received malformed JSON from external STT service: " + message, malformed);
            return;
        }

        if (!json.has("requestId") || json.get("requestId").isJsonNull()) {
            logger.warning(() -> "External STT message missing requestId, ignoring: " + message);
            return;
        }

        final UUID requestId;
        try {
            requestId = UUID.fromString(json.get("requestId").getAsString());
        } catch (final IllegalArgumentException invalidUuid) {
            logger.warning(() -> "External STT message had an invalid requestId, ignoring: " + message);
            return;
        }

        final CompletableFuture<String> future = pendingRequests.remove(requestId);
        if (future == null) {
            // Already timed out, already completed, or an unsolicited message; safe to drop.
            return;
        }

        if (json.has("error") && !json.get("error").isJsonNull()) {
            future.completeExceptionally(new RuntimeException("External STT error: " + json.get("error").getAsString()));
            return;
        }

        final String text = (json.has("text") && !json.get("text").isJsonNull())
                ? json.get("text").getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
        future.complete(text);
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
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("ExternalWebSocketEngine shut down cleanly.");
    }

    /** Outbound "begin segment" control message. */
    private record StartMessage(String type, String requestId, String playerId, int sampleRate) {
    }

    /** Outbound "end segment" control message. */
    private record EndMessage(String type, String requestId) {
    }

    /**
     * Private inner class because Java-WebSocket requires extending {@link WebSocketClient}; wires
     * connection lifecycle callbacks back into the outer engine.
     */
    private final class TranscribeSocketClient extends WebSocketClient {

        TranscribeSocketClient(@NotNull final URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(@Nullable final ServerHandshake handshake) {
            reconnectAttempt.set(0);
            connectionReady.complete(null);
            logger.info(() -> "Connected to external STT service at " + endpoint);
        }

        @Override
        public void onMessage(@NotNull final String message) {
            handleIncomingMessage(message);
        }

        @Override
        public void onClose(final int code, @Nullable final String reason, final boolean remote) {
            final IllegalStateException closedError =
                    new IllegalStateException("WebSocket connection closed (code=" + code + ", reason=" + reason + ")");
            connectionReady.completeExceptionally(closedError);
            failAllPending(closedError);

            if (!shutdown) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(@NotNull final Exception ex) {
            logger.log(Level.WARNING, "External STT WebSocket error", ex);
            connectionReady.completeExceptionally(ex);
        }
    }
}
