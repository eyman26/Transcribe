package org.eyman_;

import org.eyman_.activation.ActivationManager;
import org.eyman_.activation.CueSettings;
import org.eyman_.api.TranscribeAPI;
import org.eyman_.audio.TranscribeAudioListener;
import org.eyman_.api.AccessPolicy;
import org.eyman_.command.TranscribeCommand;
import org.eyman_.cooldown.CooldownTracker;
import org.eyman_.dispatch.ActionRunner;
import org.eyman_.hook.HookInvocation;
import org.eyman_.hook.HookRegistry;
import org.eyman_.hook.PluginHook;
import org.eyman_.keyword.KeywordDefinition;
import org.eyman_.keyword.KeywordRegistry;
import org.eyman_.keyword.WordParser;
import org.eyman_.mute.MuteManager;
import org.eyman_.placeholder.PlaceholderResolver;
import org.eyman_.engine.ExternalWebSocketEngine;
import org.eyman_.engine.InternalVoskEngine;
import org.eyman_.engine.SpeechRecognitionEngine;
import org.eyman_.events.VoiceWordSpokenEvent;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class TranscribePlugin extends JavaPlugin {

    private static final String SIMPLE_VOICE_CHAT_PLUGIN_NAME = "voicechat";
    private static final String VOICECHAT_HOOK_PLUGIN_ID = "transcribe";

    private SpeechRecognitionEngine speechEngine;
    private ActivationManager activationManager;
    private TranscribeAudioListener audioListener;
    private KeywordRegistry keywordRegistry;
    private MuteManager muteManager;
    private ActionRunner actionRunner;
    private AccessPolicy accessPolicy;
    private HookRegistry hookRegistry;
    // TODO: cooldowns aren't cleared on quit
    private final CooldownTracker cooldowns = new CooldownTracker();
    private List<String> consoleCommandTemplates = List.of();
    private volatile int debugLevel;

    private volatile VoicechatApi voicechatApi;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin(SIMPLE_VOICE_CHAT_PLUGIN_NAME) == null) {
            getLogger().severe("Simple Voice Chat is missing, disabling Transcribe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        TranscribeAPI.internalBootstrap();

        this.debugLevel = getConfig().getInt("debug.level", 0);

        this.audioListener = new TranscribeAudioListener(this);
        this.audioListener.setDebugLevel(debugLevel);
        try {
            this.audioListener.setCueSettings(buildCueSettings());
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Bad activation.feedback config, disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TranscribeAPI.get().internalAddLifecycleListener(audioListener);

        if (!registerVoicechatServiceHook()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.hookRegistry = new HookRegistry(getServer(), getLogger());
        this.keywordRegistry = new KeywordRegistry(this);
        KeywordRegistry.Snapshot keywords = keywordRegistry.scan();
        keywordRegistry.install(keywords);
        logKeywordProblems(keywords);
        this.muteManager = new MuteManager(getDataFolder().toPath().resolve("mutes.yml"), getLogger());
        this.muteManager.load();
        this.accessPolicy = new AccessPolicy(muteManager);
        this.actionRunner = new ActionRunner(this, new PlaceholderResolver(this));

        try {
            this.speechEngine = buildConfiguredEngine(keywords);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Couldn't start the speech engine, disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.consoleCommandTemplates = List.copyOf(getConfig().getStringList("recognition.console-commands"));

        try {
            this.activationManager = buildActivationManager();
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Bad activation config, disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        activationManager.register();

        PluginCommand transcribeCommand = getCommand("transcribe");
        if (transcribeCommand != null) {
            TranscribeCommand handler = new TranscribeCommand(this, keywordRegistry, muteManager);
            transcribeCommand.setExecutor(handler);
            transcribeCommand.setTabCompleter(handler);
        }

        getLogger().info("Transcribe enabled: engine=" + getConfig().getString("engine.mode", "internal")
                + " activation-window=" + getConfig().getDouble("activation.window-seconds", 3.0) + "s"
                + " keywords=" + keywords.byId().size() + " debug-level=" + debugLevel);
    }

    @Override
    public void onDisable() {
        if (muteManager != null) {
            muteManager.shutdown();
        }
        if (activationManager != null) {
            activationManager.shutdown();
        }
        if (speechEngine != null) {
            speechEngine.shutdown();
        }
        TranscribeAPI.internalShutdown();
        getLogger().info("Transcribe disabled.");
    }

    private boolean registerVoicechatServiceHook() {
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat service isn't available (load order?), disabling.");
            return false;
        }
        service.registerPlugin(new TranscribeVoicechatHook());
        return true;
    }

    private SpeechRecognitionEngine buildConfiguredEngine(KeywordRegistry.Snapshot keywords) throws IOException {
        String mode = getConfig().getString("engine.mode", "INTERNAL").toUpperCase(Locale.ROOT);
        int sampleRateHertz = getConfig().getInt("engine.sample-rate-hertz", 16_000);

        return switch (mode) {
            case "INTERNAL" -> {
                java.util.Set<String> merged = new java.util.LinkedHashSet<>(keywords.grammar());
                for (String word : getConfig().getStringList("engine.internal.grammar")) {
                    merged.add(WordParser.normalize(word));
                }
                List<String> grammar = List.copyOf(merged);
                Path modelPath = getDataFolder().toPath()
                        .resolve(getConfig().getString("engine.internal.model-path", "models/vosk-model-small-en-us-0.15"));
                yield new InternalVoskEngine(modelPath, grammar, sampleRateHertz, getLogger());
            }
            case "EXTERNAL" -> {
                URI endpoint = URI.create(getConfig().getString("engine.external.uri", "ws://localhost:2700"));
                Duration requestTimeout = Duration.ofMillis(
                        getConfig().getLong("engine.external.request-timeout-millis", 5_000));
                yield new ExternalWebSocketEngine(endpoint, requestTimeout, getLogger());
            }
            default -> throw new IOException(
                    "Unknown engine.mode '" + mode + "' in config.yml - expected INTERNAL or EXTERNAL.");
        };
    }

    private ActivationManager buildActivationManager() {
        Duration windowDuration = Duration.ofMillis(
                Math.round(getConfig().getDouble("activation.window-seconds", 3.0) * 1000.0));

        List<String> rawTriggers = getConfig().getStringList("activation.triggers");
        Set<ActivationManager.TriggerType> enabledTriggers = EnumSet.noneOf(ActivationManager.TriggerType.class);

        for (String rawTrigger : rawTriggers) {
            ActivationManager.TriggerType type = ActivationManager.TriggerType.parse(rawTrigger);
            if (type != null) {
                enabledTriggers.add(type);
            } else {
                getLogger().warning("Unknown activation trigger type in config.yml: '" + rawTrigger + "'");
            }
        }

        // Config slots are 1-indexed (1-9), Bukkit slots are 0-indexed (0-8).
        List<Integer> hotbarSlots = getConfig().getIntegerList("activation.hotbar-slots").stream()
                .map(slot -> slot - 1)
                .filter(slot -> slot >= 0 && slot <= 8)
                .toList();

        boolean cancelTriggerEvents = getConfig().getBoolean("activation.cancel-trigger-events", true);
        CueSettings cues = buildCueSettings();

        String rawPlaceholder = getConfig().getString("activation.placeholderapi.placeholder", "");
        String papiPlaceholder = (rawPlaceholder == null || rawPlaceholder.isBlank()) ? null : rawPlaceholder;
        String papiExpectedValue = getConfig().getString("activation.placeholderapi.expected-value", "true");
        long papiPollIntervalTicks = getConfig().getLong("activation.placeholderapi.poll-interval-ticks", 10);

        ActivationManager.ActivationConfig activationConfig = new ActivationManager.ActivationConfig(
                windowDuration, enabledTriggers, hotbarSlots, cancelTriggerEvents,
                cues, papiPlaceholder, papiExpectedValue, papiPollIntervalTicks,
                debugLevel);

        return new ActivationManager(this, activationConfig, this::isBlocked);
    }

    private CueSettings buildCueSettings() {
        boolean actionBarEnabled = getConfig().getBoolean("activation.feedback.action-bar.enabled", true);
        String openMessage = getConfig().getString("activation.feedback.action-bar.open-message", "&b🎤 Listening...");
        String closeMessage = getConfig().getString("activation.feedback.action-bar.close-message", "");

        boolean soundEnabled = getConfig().getBoolean("activation.feedback.sound.enabled", true);
        String openSound = getConfig().getString("activation.feedback.sound.open-sound", "minecraft:block.note_block.pling");
        float openVolume = (float) getConfig().getDouble("activation.feedback.sound.open-volume", 0.5);
        float openPitch = (float) getConfig().getDouble("activation.feedback.sound.open-pitch", 1.8);
        String closeSound = getConfig().getString("activation.feedback.sound.close-sound", "minecraft:block.note_block.bass");
        float closeVolume = (float) getConfig().getDouble("activation.feedback.sound.close-volume", 0.4);
        float closePitch = (float) getConfig().getDouble("activation.feedback.sound.close-pitch", 0.8);

        return new CueSettings(actionBarEnabled, soundEnabled, openMessage, closeMessage,
                openSound, openVolume, openPitch, closeSound, closeVolume, closePitch);
    }

    public void handleRecognizedSpeech(Player player, String keyword, float confidence) {
        if (!getServer().isPrimaryThread()) {
            getServer().getScheduler().runTask(this, () -> handleRecognizedSpeech(player, keyword, confidence));
            return;
        }
        if (!player.isOnline() || !accessPolicy.canUse(player)) {
            return;
        }

        KeywordDefinition definition = keywordRegistry.find(keyword).orElse(null);
        if (definition != null) {
            if (confidence < definition.minConfidence()) {
                if (debugLevel >= 1) {
                    getLogger().info(String.format(Locale.ROOT,
                            "[Transcribe debug] '%s' ignored for %s: confidence %.2f is below min-confidence %.2f in keywords/%s.yml",
                            keyword, player.getName(), confidence, definition.minConfidence(), definition.id()));
                }
                return;
            }
            if (!player.hasPermission(definition.permission())) {
                return;
            }
            boolean bypassCooldown = player.hasPermission("transcribe.bypass.cooldown");
            if (!bypassCooldown && cooldowns.remainingMillis(player.getUniqueId(), definition.id()) > 0) {
                return;
            }
            VoiceWordSpokenEvent kwEvent = new VoiceWordSpokenEvent(player, definition.word(), confidence);
            getServer().getPluginManager().callEvent(kwEvent);
            if (!kwEvent.isCancelled()) {
                if (!bypassCooldown) {
                    cooldowns.start(player.getUniqueId(), definition.id(), definition.cooldown());
                }
                actionRunner.runCommands(player, definition, definition.actions().executor(),
                        definition.actions().commands(), keyword, confidence);
                runHooks(player, definition, keyword, confidence);
            }
            return;
        }

        VoiceWordSpokenEvent event = new VoiceWordSpokenEvent(player, keyword, confidence);
        getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }

        dispatchConfiguredCommands(player, keyword);
    }

    private void runHooks(Player player, KeywordDefinition definition,
                          String spoken, float confidence) {
        for (KeywordDefinition.HookConfig hookConfig : definition.hooks().values()) {
            if (!hookConfig.enabled()) {
                continue;
            }
            PluginHook hook = hookRegistry.resolve(hookConfig).orElse(null);
            if (hook == null || !hook.isAvailable(getServer())) {
                continue;
            }
            try {
                hook.execute(new HookInvocation(player, definition, spoken, confidence, hookConfig, actionRunner));
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Hook '" + hookConfig.id() + "' failed for keyword '"
                        + definition.word() + "'", e);
            }
        }
    }

    private boolean isBlocked(Player player) {
        return accessPolicy == null || !accessPolicy.canUse(player);
    }

    private void logKeywordProblems(KeywordRegistry.Snapshot snapshot) {
        snapshot.problems().forEach(problem -> getLogger().warning("keywords/" + problem));
        hookRegistry.validate(snapshot, getLogger());
    }

    public void reloadKeywords(CommandSender sender) {
        reload(sender);
    }

    public void reload(CommandSender sender) {
        reloadConfig();

        this.debugLevel = getConfig().getInt("debug.level", 0);
        this.audioListener.setDebugLevel(debugLevel);

        CueSettings newCues;
        try {
            newCues = buildCueSettings();
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Reload failed (activation.feedback), keeping old config", e);
            sender.sendMessage("Reload failed: " + e.getMessage());
            return;
        }
        this.audioListener.setCueSettings(newCues);

        KeywordRegistry.Snapshot newKeywords = keywordRegistry.scan();

        SpeechRecognitionEngine newEngine;
        try {
            newEngine = buildConfiguredEngine(newKeywords);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Reload failed, couldn't build engine", e);
            sender.sendMessage("Reload failed: " + e.getMessage());
            return;
        }

        ActivationManager newActivationManager;
        try {
            newActivationManager = buildActivationManager();
        } catch (IllegalArgumentException e) {
            newEngine.shutdown();
            getLogger().log(Level.SEVERE, "Reload failed (activation)", e);
            sender.sendMessage("Reload failed: " + e.getMessage());
            return;
        }

        SpeechRecognitionEngine oldEngine = this.speechEngine;
        ActivationManager oldActivationManager = this.activationManager;

        this.keywordRegistry.install(newKeywords);
        logKeywordProblems(newKeywords);
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

        getLogger().info("Configuration reloaded. keywords=" + newKeywords.byId().size() + " debug-level=" + debugLevel);
        sender.sendMessage("Transcribe reloaded: " + newKeywords.grammar().size() + " spoken form(s) active, "
                + newKeywords.disabledCount() + " disabled keyword(s), " + newKeywords.problems().size() + " problem(s).");
        newKeywords.problems().stream().limit(5).forEach(p -> sender.sendMessage(" - " + p));
    }

    // main thread only
    private void dispatchConfiguredCommands(Player player, String keyword) {
        if (consoleCommandTemplates.isEmpty()) {
            return;
        }
        ConsoleCommandSender console = getServer().getConsoleSender();
        for (String template : consoleCommandTemplates) {
            String resolved = template
                    .replace("%player%", player.getName())
                    .replace("%keyword%", keyword);
            getServer().dispatchCommand(console, resolved);
        }
    }

    public SpeechRecognitionEngine getSpeechEngine() {
        if (speechEngine == null) {
            throw new IllegalStateException("Transcribe has not finished enabling yet.");
        }
        return speechEngine;
    }

    // SVC only lets you register events from inside registerEvents()
    private final class TranscribeVoicechatHook implements VoicechatPlugin {

        @Override
        public String getPluginId() {
            return VOICECHAT_HOOK_PLUGIN_ID;
        }

        @Override
        public void initialize(VoicechatApi api) {
            voicechatApi = api;
            getLogger().info("Simple Voice Chat API handshake complete.");
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(MicrophonePacketEvent.class, audioListener::onMicrophonePacket);
        }
    }
}