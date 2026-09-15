package org.eyman_;

import org.eyman_.activation.ActivationManager;
import org.eyman_.api.TranscribeAPI;
import org.eyman_.engine.ExternalWebSocketEngine;
import org.eyman_.engine.InternalVoskEngine;
import org.eyman_.engine.SpeechRecognitionEngine;
import org.eyman_.events.VoiceWordSpokenEvent;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Main Paper plugin entry point for Transcribe.
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *     <li>Verify Simple Voice Chat (hard dependency) is present; disable self if missing.</li>
 *     <li>Register the Transcribe hook with Simple Voice Chat's Bukkit service.</li>
 *     <li>Bootstrap the {@link TranscribeAPI} singleton.</li>
 *     <li>Load {@code config.yml} and construct the configured {@link SpeechRecognitionEngine}
 *     (internal Vosk or external WebSocket), disabling self if construction fails.</li>
 *     <li>Construct and register the {@link ActivationManager} that opens Listen Windows.</li>
 * </ol>
 *
 * <h2>Recognition dispatch</h2>
 * <p>{@link #handleRecognizedSpeech(Player, String, float)} is the single, thread-safe entry
 * point for reporting a recognized keyword: it hops back onto the main thread if necessary,
 * fires the cancellable {@link VoiceWordSpokenEvent}, and &mdash; unless a listener cancels it
 * &mdash; dispatches the configured console commands as the server console, substituting
 * {@code %player%} and {@code %keyword%}.</p>
 */
public final class TranscribePlugin extends JavaPlugin {

    private static final String SIMPLE_VOICE_CHAT_PLUGIN_NAME = "voicechat";
    private static final String VOICECHAT_HOOK_PLUGIN_ID = "transcribe";

    private SpeechRecognitionEngine speechEngine;
    private ActivationManager activationManager;
    private List<String> consoleCommandTemplates = List.of();

    /** Set once Simple Voice Chat calls back into {@link TranscribeVoicechatHook#initialize}. */
    private volatile VoicechatApi voicechatApi;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin(SIMPLE_VOICE_CHAT_PLUGIN_NAME) == null) {
            getLogger().severe("Simple Voice Chat is not installed on this server. "
                    + "Transcribe cannot function without it - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerVoicechatServiceHook()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        TranscribeAPI.internalBootstrap();

        try {
            this.speechEngine = buildConfiguredEngine();
        } catch (final Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize the configured speech recognition engine - disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.consoleCommandTemplates = List.copyOf(getConfig().getStringList("recognition.console-commands"));

        try {
            this.activationManager = buildActivationManager();
        } catch (final IllegalArgumentException badConfig) {
            getLogger().log(Level.SEVERE, "Invalid activation configuration in config.yml - disabling.", badConfig);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        activationManager.register();

        getLogger().info("Transcribe enabled: engine=" + getConfig().getString("engine.mode", "internal")
                + " activation-window=" + getConfig().getDouble("activation.window-seconds", 3.0) + "s");
    }

    @Override
    public void onDisable() {
        if (activationManager != null) {
            activationManager.shutdown();
        }
        if (speechEngine != null) {
            speechEngine.shutdown();
        }
        TranscribeAPI.internalShutdown();
        getLogger().info("Transcribe disabled.");
    }

    /**
     * Registers this plugin with Simple Voice Chat's {@link BukkitVoicechatService}, the
     * standard integration point third-party plugins use to hook into SVC's audio pipeline.
     *
     * @return {@code true} if the service was found and registration succeeded
     */
    private boolean registerVoicechatServiceHook() {
        final BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat is installed but its Bukkit service is not available yet "
                    + "(unexpected load order) - disabling.");
            return false;
        }
        service.registerPlugin(new TranscribeVoicechatHook());
        getLogger().info("Registered Transcribe with the Simple Voice Chat service.");
        return true;
    }

    /**
     * Builds the {@link SpeechRecognitionEngine} selected by {@code engine.mode} in
     * {@code config.yml} ({@code INTERNAL} or {@code EXTERNAL}).
     */
    @NotNull
    private SpeechRecognitionEngine buildConfiguredEngine() throws IOException {
        final String mode = getConfig().getString("engine.mode", "INTERNAL").toUpperCase(Locale.ROOT);
        final int sampleRateHertz = getConfig().getInt("engine.sample-rate-hertz", 16_000);

        return switch (mode) {
            case "INTERNAL" -> {
                final List<String> grammar = getConfig().getStringList("engine.internal.grammar");
                final Path modelPath = getDataFolder().toPath()
                        .resolve(getConfig().getString("engine.internal.model-path", "models/vosk-model-small-en-us"));
                yield new InternalVoskEngine(modelPath, grammar, sampleRateHertz, getLogger());
            }
            case "EXTERNAL" -> {
                final URI endpoint = URI.create(getConfig().getString("engine.external.uri", "ws://localhost:2700"));
                final Duration requestTimeout = Duration.ofMillis(
                        getConfig().getLong("engine.external.request-timeout-millis", 5_000));
                yield new ExternalWebSocketEngine(endpoint, requestTimeout, getLogger());
            }
            default -> throw new IOException(
                    "Unknown engine.mode '" + mode + "' in config.yml - expected INTERNAL or EXTERNAL.");
        };
    }

    /**
     * Builds the {@link ActivationManager} from {@code config.yml}'s {@code activation} section.
     */
    @NotNull
    private ActivationManager buildActivationManager() {
        final Duration windowDuration = Duration.ofMillis(
                Math.round(getConfig().getDouble("activation.window-seconds", 3.0) * 1000.0));
        final int hotbarSlot = getConfig().getInt("activation.hotbar-slot", -1);
        final String rawPlaceholder = getConfig().getString("activation.placeholderapi.placeholder", "");
        final String papiPlaceholder = (rawPlaceholder == null || rawPlaceholder.isBlank()) ? null : rawPlaceholder;
        final String papiExpectedValue = getConfig().getString("activation.placeholderapi.expected-value", "true");
        final long papiPollIntervalTicks = getConfig().getLong("activation.placeholderapi.poll-interval-ticks", 10);

        final ActivationManager.ActivationConfig activationConfig = new ActivationManager.ActivationConfig(
                windowDuration, hotbarSlot, papiPlaceholder, papiExpectedValue, papiPollIntervalTicks);

        return new ActivationManager(this, activationConfig);
    }

    /**
     * Single, thread-safe entry point for reporting that {@code keyword} was recognized from
     * {@code player}'s voice audio. Safe to call from any thread &mdash; including the async
     * worker threads used by both {@link SpeechRecognitionEngine} implementations.
     *
     * @param player     the player whose speech was recognized; ignored if no longer online
     * @param keyword    the recognized, in-grammar keyword
     * @param confidence the engine-reported confidence score, {@code [0.0, 1.0]}
     */
    public void handleRecognizedSpeech(@NotNull final Player player, @NotNull final String keyword, final float confidence) {
        if (!getServer().isPrimaryThread()) {
            getServer().getScheduler().runTask(this, () -> handleRecognizedSpeech(player, keyword, confidence));
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        final VoiceWordSpokenEvent event = new VoiceWordSpokenEvent(player, keyword, confidence);
        getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        dispatchConfiguredCommands(player, keyword);
    }

    /** Must only be called on the main thread. */
    private void dispatchConfiguredCommands(@NotNull final Player player, @NotNull final String keyword) {
        if (consoleCommandTemplates.isEmpty()) {
            return;
        }
        final ConsoleCommandSender console = getServer().getConsoleSender();
        for (final String template : consoleCommandTemplates) {
            final String resolved = template
                    .replace("%player%", player.getName())
                    .replace("%keyword%", keyword);
            getServer().dispatchCommand(console, resolved);
        }
    }

    @NotNull
    public SpeechRecognitionEngine getSpeechEngine() {
        if (speechEngine == null) {
            throw new IllegalStateException("Transcribe has not finished enabling yet.");
        }
        return speechEngine;
    }

    /**
     * Minimal Simple Voice Chat integration hook. Registration with SVC's service (see
     * {@link #registerVoicechatServiceHook()}) is what this step wires up; {@link #initialize}
     * simply captures the {@link VoicechatApi} reference for the audio-frame ingestion pipeline
     * that consumes it.
     */
    private final class TranscribeVoicechatHook implements VoicechatPlugin {

        @Override
        @NotNull
        public String getPluginId() {
            return VOICECHAT_HOOK_PLUGIN_ID;
        }

        @Override
        public void initialize(@NotNull final VoicechatApi api) {
            voicechatApi = api;
            getLogger().info("Simple Voice Chat API handshake complete.");
        }
    }
}
