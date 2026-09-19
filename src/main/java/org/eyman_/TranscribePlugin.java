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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import java.util.Set;
import java.util.logging.Level;

public final class TranscribePlugin extends JavaPlugin {

    private static final String SIMPLE_VOICE_CHAT_PLUGIN_NAME = "voicechat";
    private static final String VOICECHAT_HOOK_PLUGIN_ID = "transcribe";

    private SpeechRecognitionEngine speechEngine;
    private ActivationManager activationManager;
    private List<String> consoleCommandTemplates = List.of();

    // Populated by the VoicechatPlugin hook below.
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

    // Registers with Simple Voice Chat's Bukkit service, the standard third-party integration point.
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

    @NotNull
    private ActivationManager buildActivationManager() {
        final Duration windowDuration = Duration.ofMillis(
                Math.round(getConfig().getDouble("activation.window-seconds", 3.0) * 1000.0));

        final List<String> rawTriggers = getConfig().getStringList("activation.triggers");
        final Set<ActivationManager.TriggerType> enabledTriggers = java.util.EnumSet.noneOf(ActivationManager.TriggerType.class);

        for (final String rawTrigger : rawTriggers) {
            final ActivationManager.TriggerType type = ActivationManager.TriggerType.parse(rawTrigger);
            if (type != null) {
                enabledTriggers.add(type);
            } else {
                getLogger().warning("Unknown activation trigger type in config.yml: '" + rawTrigger + "'");
            }
        }

        // Config slots are 1-indexed (1-9), Bukkit slots are 0-indexed (0-8).
        final List<Integer> hotbarSlots = getConfig().getIntegerList("activation.hotbar-slots").stream()
                .map(slot -> slot - 1)
                .filter(slot -> slot >= 0 && slot <= 8)
                .toList();

        final boolean cancelTriggerEvents = getConfig().getBoolean("activation.cancel-trigger-events", true);
        final boolean enableActionBar = getConfig().getBoolean("activation.feedback.actionbar", true);
        final boolean enableSoundCues = getConfig().getBoolean("activation.feedback.sound", true);

        final String rawPlaceholder = getConfig().getString("activation.placeholderapi.placeholder", "");
        final String papiPlaceholder = (rawPlaceholder == null || rawPlaceholder.isBlank()) ? null : rawPlaceholder;
        final String papiExpectedValue = getConfig().getString("activation.placeholderapi.expected-value", "true");
        final long papiPollIntervalTicks = getConfig().getLong("activation.placeholderapi.poll-interval-ticks", 10);

        final ActivationManager.ActivationConfig activationConfig = new ActivationManager.ActivationConfig(
                windowDuration, enabledTriggers, hotbarSlots, cancelTriggerEvents,
                enableActionBar, enableSoundCues, papiPlaceholder, papiExpectedValue, papiPollIntervalTicks);

        return new ActivationManager(this, activationConfig);
    }

    /**
     * Thread-safe: recognized speech from any thread is marshalled onto the main thread before
     * firing {@link VoiceWordSpokenEvent}.
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

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command,
                              @NotNull final String label, @NotNull final String[] args) {
        if (!command.getName().equalsIgnoreCase("transcribe")) {
            return false;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /" + label + " reload");
            return true;
        }
        if (!sender.hasPermission("transcribe.reload")) {
            sender.sendMessage("You do not have permission to do that.");
            return true;
        }

        reload(sender);
        return true;
    }

    /**
     * Builds the replacement engine and activation manager against the reloaded config before
     * tearing anything down, so a bad config leaves the previous working setup untouched.
     */
    private void reload(@NotNull final CommandSender sender) {
        reloadConfig();

        final SpeechRecognitionEngine newEngine;
        try {
            newEngine = buildConfiguredEngine();
        } catch (final Exception e) {
            getLogger().log(Level.SEVERE, "Reload failed while building the speech recognition engine - "
                    + "keeping the previous engine running.", e);
            sender.sendMessage("Reload failed: " + e.getMessage() + " (see console; previous engine is still running)");
            return;
        }

        final ActivationManager newActivationManager;
        try {
            newActivationManager = buildActivationManager();
        } catch (final IllegalArgumentException badConfig) {
            newEngine.shutdown(); // don't leak the engine we just built but won't use
            getLogger().log(Level.SEVERE, "Reload failed due to invalid activation configuration - "
                    + "keeping the previous configuration running.", badConfig);
            sender.sendMessage("Reload failed: " + badConfig.getMessage() + " (see console; previous configuration is still running)");
            return;
        }

        final SpeechRecognitionEngine oldEngine = this.speechEngine;
        final ActivationManager oldActivationManager = this.activationManager;

        this.speechEngine = newEngine;
        this.activationManager = newActivationManager;
        this.activationManager.register();
        this.consoleCommandTemplates = List.copyOf(getConfig().getStringList("recognition.console-commands"));

        if (oldActivationManager != null) {
            oldActivationManager.shutdown();
        }
        if (oldEngine != null) {
            oldEngine.shutdown();
        }

        getLogger().info("Configuration reloaded.");
        sender.sendMessage("Transcribe configuration reloaded.");
    }

    // Must only be called on the main thread.
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

    // Captures the VoicechatApi reference for the audio-frame ingestion pipeline.
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