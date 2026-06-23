package autismclient.modules;

import autismclient.api.AddonRegistrationResult;
import autismclient.util.AutismBindUtil;
import autismclient.util.AutismConfig;
import autismclient.util.AutismInputGate;
import autismclient.util.AutismPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PackModuleRegistry {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<String, PackModule> MODULES = new LinkedHashMap<>();
    private static final Map<String, Boolean> KEY_STATES = new LinkedHashMap<>();
    private static final Map<String, Boolean> OFFLINE_ENABLE_BASELINES = new LinkedHashMap<>();
    private static final Map<String, Boolean> OFFLINE_ENABLE_TRANSITIONS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> OFFLINE_SETTING_CHANGES = new LinkedHashMap<>();
    private static final Set<String> OFFLINE_SETTING_RESETS = new LinkedHashSet<>();
    private static final Map<PackModuleCategory, List<PackModule>> CATEGORY_CACHE = new LinkedHashMap<>();
    private static List<PackModule> activeModulesCache = List.of();
    private static int activeModulesCacheRevision = -1;
    private static List<PackModule> disabledTickModulesCache = List.of();
    private static int disabledTickModulesCacheRevision = -1;
    private static List<PackModule> keyboundModulesCache = List.of();
    private static int keyboundModulesCacheRevision = -1;
    private static List<PackModule> packetSendModulesCache = List.of();
    private static int packetSendModulesCacheRevision = -1;
    private static List<PackModule> packetReceiveModulesCache = List.of();
    private static int packetReceiveModulesCacheRevision = -1;
    private static List<PackModule> disabledPacketReceiveModulesCache = List.of();
    private static int disabledPacketReceiveModulesCacheRevision = -1;
    private static List<PackModule> soundModulesCache = List.of();
    private static int soundModulesCacheRevision = -1;
    private static List<PackModule> renderModulesCache = List.of();
    private static int renderModulesCacheRevision = -1;
    private static List<PackModule> blockBreakingProgressModulesCache = List.of();
    private static int blockBreakingProgressModulesCacheRevision = -1;
    private static List<PackModule> startBreakingModulesCache = List.of();
    private static int startBreakingModulesCacheRevision = -1;
    private static List<PackModule> preMovementModulesCache = List.of();
    private static int preMovementModulesCacheRevision = -1;
    private static List<PackModule> playerMoveModulesCache = List.of();
    private static int playerMoveModulesCacheRevision = -1;
    private static List<PackModule> mouseRotationModulesCache = List.of();
    private static int mouseRotationModulesCacheRevision = -1;
    private static List<PackModule> tooltipModulesCache = List.of();
    private static int tooltipModulesCacheRevision = -1;
    private static List<PackModule> attackUseModulesCache = List.of();
    private static int attackUseModulesCacheRevision = -1;
    private static boolean activePacketEventModulesCache;
    private static int activePacketEventModulesCacheRevision = -1;
    private static int revision;
    private static int activeRevision;
    private static boolean initialized;
    private static boolean menuKeyDown;

    private PackModuleRegistry() {
    }

    public static void initialize(AutismConfig config) {
        if (initialized) return;
        PackBuiltinModules.register();
        NameCensorModule.refreshFastFlagsFromRegistry();
        PackModuleWorldRenderer.initialize();
        initialized = true;
        PackModuleRenderUtil.refreshFastFlags();
        PackHideState.enforceStartupHidden();

        autismclient.util.AutismEssentialBridge.restoreIfOrphaned(config);
    }

    static void register(PackModule module) {
        if (module == null) return;
        if (module.id() == null || module.id().isBlank()) {
            throw new IllegalArgumentException("Built-in module id cannot be blank");
        }
        if (MODULES.containsKey(module.id())) {
            throw new IllegalStateException("Duplicate built-in module id: " + module.id());
        }
        MODULES.put(module.id(), module);
        invalidateCaches(true);
    }

    public static boolean registerAddonModule(PackModule module, String addonId) {
        return registerAddonModuleDetailed(module, addonId).accepted();
    }

    public static AddonRegistrationResult registerAddonModuleDetailed(PackModule module, String addonId) {
        if (module == null) return AddonRegistrationResult.rejected("module", "", "module was null");
        if (addonId == null || addonId.isBlank()) {
            return rejectAddonModule(addonId, "", "registration outside an addon lifecycle");
        }
        String id = module.id();
        if (id == null || !id.contains(":")) {
            return rejectAddonModule(addonId, id, "non-namespaced id");
        }
        if (!id.startsWith(addonId + ":")) {
            return rejectAddonModule(addonId, id, "foreign namespace");
        }
        if (MODULES.containsKey(id)) {
            return rejectAddonModule(addonId, id, "duplicate id");
        }
        module.markAddon();

        if (module.category() == null) {
            module.assignCategory(PackModuleCategory.registerAddon(addonId,
                autismclient.addons.AddonManager.scopedCategoryLabel(null)));
        }
        MODULES.put(id, module);
        invalidateCaches(true);
        autismclient.addons.AddonManager.recordAcceptedRegistration("module", id);
        return AddonRegistrationResult.accepted("module", id);
    }

    private static AddonRegistrationResult rejectAddonModule(String addonId, String id, String reason) {
        autismclient.AutismClientAddon.LOG.warn("[Modules] Rejecting addon module '{}': {}", id, reason);
        autismclient.addons.AddonManager.recordRejectedRegistration(addonId, "module", id, reason);
        return AddonRegistrationResult.rejected("module", id, reason);
    }

    public static void unregisterAddonModules(String addonId) {
        if (addonId == null || addonId.isBlank()) return;
        boolean removed = false;
        java.util.Iterator<Map.Entry<String, PackModule>> it = MODULES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PackModule> entry = it.next();
            PackModule module = entry.getValue();
            if (module != null && module.isAddon() && entry.getKey().startsWith(addonId + ":")) {
                try { module.setEnabled(false); } catch (Throwable ignored) {}
                it.remove();
                KEY_STATES.remove(entry.getKey());
                removed = true;
            }
        }
        if (removed) invalidateCaches(true);
    }

    private static void runGuarded(PackModule module, String hook, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            reportAddonModuleError(module, hook, t);
        }
    }

    private static boolean runGuardedBool(PackModule module, String hook, java.util.function.BooleanSupplier body) {
        try {
            return body.getAsBoolean();
        } catch (Throwable t) {
            reportAddonModuleError(module, hook, t);
            return false;
        }
    }

    private static void reportAddonModuleError(PackModule module, String hook, Throwable t) {
        autismclient.AutismClientAddon.LOG.error("[Modules] Addon module '{}' threw in {}; disabling it.", module.id(), hook, t);
        String id = module.id();
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon > 0) {
            autismclient.addons.AddonManager.recordRuntimeError(id.substring(0, colon), "Module " + id + " threw in " + hook);
        }
        try { module.setEnabled(false); } catch (Throwable ignored) {}
        try {
            autismclient.util.AutismClientMessaging.sendPrefixed("\u00a7cAddon module '" + module.name() + "' errored and was disabled.");
        } catch (Throwable ignored) {}
    }

    public static Collection<PackModule> all() {
        return Collections.unmodifiableCollection(MODULES.values());
    }

    public static List<PackModule> byCategory(PackModuleCategory category) {
        if (category == null) return List.of();
        List<PackModule> cached = CATEGORY_CACHE.get(category);
        if (cached != null) return cached;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.category() == category && module.showInModuleMenu()) modules.add(module);
        }
        List<PackModule> immutable = Collections.unmodifiableList(modules);
        CATEGORY_CACHE.put(category, immutable);
        return immutable;
    }

    public static PackModule get(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        PackModule exact = MODULES.get(idOrName);
        if (exact != null) return exact;
        String needle = normalize(idOrName);
        PackModule direct = MODULES.get(needle);
        if (direct != null) return direct;
        for (PackModule module : MODULES.values()) {
            if (normalize(module.name()).equals(needle)) return module;
        }
        return null;
    }

    public static boolean toggle(String idOrName, autismclient.util.macro.ToggleModuleAction.ToggleMode mode) {
        PackModule module = get(idOrName);
        if (module == null) return false;
        autismclient.util.macro.ToggleModuleAction.ToggleMode resolvedMode =
            mode == null ? autismclient.util.macro.ToggleModuleAction.ToggleMode.TOGGLE : mode;
        if (PackHideState.isActive()
            && !PackHideState.isHideModule(module)
            && (resolvedMode == autismclient.util.macro.ToggleModuleAction.ToggleMode.ENABLE
                || resolvedMode == autismclient.util.macro.ToggleModuleAction.ToggleMode.TOGGLE)) {
            return false;
        }
        switch (resolvedMode) {
            case ENABLE -> module.setEnabled(true);
            case DISABLE -> module.setEnabled(false);
            default -> module.toggle();
        }
        return true;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.showInModuleMenu()) names.add(module.name());
        }
        return names;
    }

    public static List<PackModule> activeModules() {
        if (activeModulesCacheRevision == activeRevision) return activeModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled() && !Boolean.TRUE.equals(OFFLINE_ENABLE_TRANSITIONS.get(module.id()))) modules.add(module);
        }
        activeModulesCache = Collections.unmodifiableList(modules);
        activeModulesCacheRevision = activeRevision;
        return activeModulesCache;
    }

    public static boolean hasActiveModules() {
        return !activeModules().isEmpty();
    }

    public static boolean isModuleEnabled(String idOrName) {
        PackModule module = get(idOrName);
        return module != null && module.isEnabled();
    }

    public static boolean hasActivePacketEventModules() {
        if (activePacketEventModulesCacheRevision == activeRevision) return activePacketEventModulesCache;
        boolean result = !packetSendModules().isEmpty() || !packetReceiveModules().isEmpty();
        activePacketEventModulesCache = result;
        activePacketEventModulesCacheRevision = activeRevision;
        return result;
    }

    private static List<PackModule> disabledTickModules() {
        if (disabledTickModulesCacheRevision == revision) return disabledTickModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.ticksWhenDisabled()) modules.add(module);
        }
        disabledTickModulesCache = Collections.unmodifiableList(modules);
        disabledTickModulesCacheRevision = revision;
        return disabledTickModulesCache;
    }

    private static List<PackModule> keyboundModules() {
        if (keyboundModulesCacheRevision == revision) return keyboundModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.keybind() != -1) modules.add(module);
        }
        keyboundModulesCache = Collections.unmodifiableList(modules);
        keyboundModulesCacheRevision = revision;
        return keyboundModulesCache;
    }

    private static List<PackModule> activeOverrideModules(String methodName, Class<?>... parameterTypes) {
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : activeModules()) {
            if (overridesModuleMethod(module, methodName, parameterTypes)) modules.add(module);
        }
        return Collections.unmodifiableList(modules);
    }

    private static List<PackModule> disabledOverrideModules(String methodName, Class<?>... parameterTypes) {
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : disabledTickModules()) {
            if (!module.isEnabled() && overridesModuleMethod(module, methodName, parameterTypes)) modules.add(module);
        }
        return Collections.unmodifiableList(modules);
    }

    private static List<PackModule> packetSendModules() {
        if (packetSendModulesCacheRevision == activeRevision) return packetSendModulesCache;
        packetSendModulesCache = activeOverrideModules("onPacketSend", Packet.class);
        packetSendModulesCacheRevision = activeRevision;
        return packetSendModulesCache;
    }

    private static List<PackModule> packetReceiveModules() {
        if (packetReceiveModulesCacheRevision == activeRevision) return packetReceiveModulesCache;
        packetReceiveModulesCache = activeOverrideModules("onPacketReceive", Packet.class);
        packetReceiveModulesCacheRevision = activeRevision;
        return packetReceiveModulesCache;
    }

    private static List<PackModule> disabledPacketReceiveModules() {
        if (disabledPacketReceiveModulesCacheRevision == revision) return disabledPacketReceiveModulesCache;
        disabledPacketReceiveModulesCache = disabledOverrideModules("onPacketReceive", Packet.class);
        disabledPacketReceiveModulesCacheRevision = revision;
        return disabledPacketReceiveModulesCache;
    }

    private static List<PackModule> soundModules() {
        if (soundModulesCacheRevision == activeRevision) return soundModulesCache;
        soundModulesCache = activeOverrideModules("onSoundPacket", ClientboundSoundPacket.class);
        soundModulesCacheRevision = activeRevision;
        return soundModulesCache;
    }

    private static List<PackModule> renderModules() {
        if (renderModulesCacheRevision == activeRevision) return renderModulesCache;
        renderModulesCache = activeOverrideModules("onRenderLevel", float.class);
        renderModulesCacheRevision = activeRevision;
        return renderModulesCache;
    }

    private static List<PackModule> blockBreakingProgressModules() {
        if (blockBreakingProgressModulesCacheRevision == activeRevision) return blockBreakingProgressModulesCache;
        blockBreakingProgressModulesCache = activeOverrideModules("onBlockBreakingProgress", BlockPos.class, Direction.class);
        blockBreakingProgressModulesCacheRevision = activeRevision;
        return blockBreakingProgressModulesCache;
    }

    private static List<PackModule> startBreakingModules() {
        if (startBreakingModulesCacheRevision == activeRevision) return startBreakingModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : activeModules()) {
            if (overridesModuleMethod(module, "shouldCancelStartBreakingBlock", BlockPos.class, Direction.class)
                || overridesModuleMethod(module, "onStartBreakingBlock", BlockPos.class, Direction.class)
                || overridesModuleMethod(module, "onStartDestroyBlock", BlockPos.class, Direction.class)) {
                modules.add(module);
            }
        }
        startBreakingModulesCache = Collections.unmodifiableList(modules);
        startBreakingModulesCacheRevision = activeRevision;
        return startBreakingModulesCache;
    }

    private static List<PackModule> preMovementModules() {
        if (preMovementModulesCacheRevision == activeRevision) return preMovementModulesCache;
        preMovementModulesCache = activeOverrideModules("preMovementTick");
        preMovementModulesCacheRevision = activeRevision;
        return preMovementModulesCache;
    }

    private static List<PackModule> playerMoveModules() {
        if (playerMoveModulesCacheRevision == activeRevision) return playerMoveModulesCache;
        playerMoveModulesCache = activeOverrideModules("onPlayerMove", MoverType.class, Vec3.class);
        playerMoveModulesCacheRevision = activeRevision;
        return playerMoveModulesCache;
    }

    private static List<PackModule> mouseRotationModules() {
        if (mouseRotationModulesCacheRevision == activeRevision) return mouseRotationModulesCache;
        mouseRotationModulesCache = activeOverrideModules("onMouseRotation", double.class, double.class);
        mouseRotationModulesCacheRevision = activeRevision;
        return mouseRotationModulesCache;
    }

    private static List<PackModule> tooltipModules() {
        if (tooltipModulesCacheRevision == activeRevision) return tooltipModulesCache;
        tooltipModulesCache = activeOverrideModules("appendTooltip", net.minecraft.world.item.ItemStack.class, List.class);
        tooltipModulesCacheRevision = activeRevision;
        return tooltipModulesCache;
    }

    private static List<PackModule> attackUseModules() {
        if (attackUseModulesCacheRevision == activeRevision) return attackUseModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : activeModules()) {
            if (overridesModuleMethod(module, "shouldCancelAttack", net.minecraft.world.phys.HitResult.class)
                || overridesModuleMethod(module, "shouldCancelUse", net.minecraft.world.phys.HitResult.class, net.minecraft.world.InteractionHand.class)) {
                modules.add(module);
            }
        }
        attackUseModulesCache = Collections.unmodifiableList(modules);
        attackUseModulesCacheRevision = activeRevision;
        return attackUseModulesCache;
    }

    private static boolean hasDisabledTickWorkModules() {
        for (PackModule module : disabledTickModules()) {
            if (!module.isEnabled() && module.hasDisabledTickWork()) return true;
        }
        return false;
    }

    public static boolean hasTickWork() {
        if (!initialized || MC == null) return false;
        return !keyboundModules().isEmpty()
            || !activeModules().isEmpty()
            || hasDisabledTickWorkModules();
    }

    public static boolean hasPreMovementHooks() {
        return !preMovementModules().isEmpty();
    }

    public static boolean hasMovementHooks() {
        return !preMovementModules().isEmpty() || !playerMoveModules().isEmpty();
    }

    public static boolean hasMouseRotationHooks() {
        return !mouseRotationModules().isEmpty();
    }

    public static boolean hasRenderLevelHooks() {
        return !renderModules().isEmpty();
    }

    public static boolean hasTooltipHooks() {
        return !tooltipModules().isEmpty();
    }

    public static boolean hasAttackUseHooks() {
        return !attackUseModules().isEmpty();
    }

    public static boolean hasSoundHooks() {
        return !soundModules().isEmpty();
    }

    public static boolean hasBlockBreakingProgressHooks() {
        return !blockBreakingProgressModules().isEmpty();
    }

    public static boolean hasStartBreakingHooks() {
        return !startBreakingModules().isEmpty();
    }

    static void markModuleSettingsChanged() {
        invalidateCaches(false);
    }

    static void markModuleEnabledChanged() {
        invalidateCaches(true);
    }

    static void recordOfflineConfiguredState(PackModule module, boolean oldEnabled, boolean newEnabled) {
        if (module == null) return;
        String id = module.id();
        boolean baseline = OFFLINE_ENABLE_BASELINES.computeIfAbsent(id, ignored -> oldEnabled);
        if (newEnabled == baseline) {
            OFFLINE_ENABLE_BASELINES.remove(id);
            OFFLINE_ENABLE_TRANSITIONS.remove(id);
        } else {
            OFFLINE_ENABLE_TRANSITIONS.put(id, newEnabled);
        }
    }

    static void recordOfflineConfiguredOption(PackModule module, String optionId, boolean reset) {
        if (module == null) return;
        if (reset) {
            OFFLINE_SETTING_RESETS.add(module.id());
            OFFLINE_SETTING_CHANGES.remove(module.id());
        } else if (optionId != null && !optionId.isBlank()) {
            OFFLINE_SETTING_CHANGES.computeIfAbsent(module.id(), ignored -> new LinkedHashSet<>()).add(optionId);
        }
    }

    public static int revision() {
        return revision;
    }

    public static int activeRevision() {
        return activeRevision;
    }

    private static void invalidateCaches(boolean activeChanged) {
        revision++;
        CATEGORY_CACHE.clear();
        disabledTickModulesCacheRevision = -1;
        keyboundModulesCacheRevision = -1;
        disabledPacketReceiveModulesCacheRevision = -1;
        if (activeChanged) {
            activeRevision++;
            activeModulesCacheRevision = -1;
            activePacketEventModulesCacheRevision = -1;
            packetSendModulesCacheRevision = -1;
            packetReceiveModulesCacheRevision = -1;
            soundModulesCacheRevision = -1;
            renderModulesCacheRevision = -1;
            blockBreakingProgressModulesCacheRevision = -1;
            startBreakingModulesCacheRevision = -1;
            preMovementModulesCacheRevision = -1;
            playerMoveModulesCacheRevision = -1;
            mouseRotationModulesCacheRevision = -1;
            tooltipModulesCacheRevision = -1;
            attackUseModulesCacheRevision = -1;
            activeModulesCache = List.of();
        }
        if (initialized) PackModuleRenderUtil.refreshFastFlags();
    }

    public static void tick() {
        if (!initialized || MC == null) return;
        if (!hasTickWork()) return;
        if (!keyboundModules().isEmpty()) tickModuleKeybinds();
        boolean profileJoin = AutismPerf.isJoinWindowActive();
        for (PackModule module : activeModules()) {
            if (profileJoin) {
                long perf = AutismPerf.beginJoin();
                tickModule(module);
                AutismPerf.endJoinSpike("join.module.tick." + module.id(), perf);
            } else {
                tickModule(module);
            }
        }
        for (PackModule module : disabledTickModules()) {
            if (module.isEnabled()) continue;
            if (!module.hasDisabledTickWork()) continue;
            if (profileJoin) {
                long perf = AutismPerf.beginJoin();
                tickModule(module);
                AutismPerf.endJoinSpike("join.module.tick." + module.id(), perf);
            } else {
                tickModule(module);
            }
        }
    }

    private static void tickModule(PackModule module) {
        if (module.isAddon()) runGuarded(module, "tick", module::tick);
        else module.tick();
    }

    public static void preMovementTick() {
        if (!initialized || MC == null || PackHideState.isActive()) return;
        for (PackModule module : preMovementModules()) {
            if (module.isAddon()) runGuarded(module, "preMovementTick", module::preMovementTick);
            else module.preMovementTick();
        }
    }

    public static void onRenderLevel(float partialTick) {
        if (!initialized || MC == null || MC.level == null || MC.player == null || MC.getConnection() == null || PackHideState.isActive()) return;
        if (!hasRenderLevelHooks()) return;
        for (PackModule module : renderModules()) {
            if (module.isAddon()) runGuarded(module, "onRenderLevel", () -> module.onRenderLevel(partialTick));
            else module.onRenderLevel(partialTick);
        }
    }

    public static void onMouseRotation(double deltaYaw, double deltaPitch) {
        if (!initialized || MC == null || PackHideState.isActive()) return;
        for (PackModule module : mouseRotationModules()) {
            if (module.isAddon()) runGuarded(module, "onMouseRotation", () -> module.onMouseRotation(deltaYaw, deltaPitch));
            else module.onMouseRotation(deltaYaw, deltaPitch);
        }
    }

    public static Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        if (!initialized || MC == null || movement == null) return movement;
        if (PackHideState.isActive()) return movement;
        Vec3 adjusted = movement;
        for (PackModule module : playerMoveModules()) {
            if (module.isAddon()) {
                Vec3 in = adjusted;
                adjusted = callGuarded(module, "onPlayerMove", () -> module.onPlayerMove(type, in), in);
            } else {
                adjusted = module.onPlayerMove(type, adjusted);
            }
        }
        return adjusted;
    }

    private static <T> T callGuarded(PackModule module, String hook, java.util.function.Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Throwable t) {
            reportAddonModuleError(module, hook, t);
            return fallback;
        }
    }

    public static boolean tickMenuKey(int keyCode) {
        if (MC == null || MC.getWindow() == null || keyCode == -1) return false;
        boolean pressed = AutismBindUtil.isBindPressed(MC, keyCode);
        boolean justPressed = pressed && !menuKeyDown;
        menuKeyDown = pressed;
        return justPressed;
    }

    public static void onGameJoin() {
        applyOfflineConfiguredTransitions();
        boolean profileJoin = AutismPerf.isJoinWindowActive();
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled() || module.ticksWhenDisabled()) {
                if (profileJoin) {
                    long perf = AutismPerf.beginJoin();
                    joinModule(module);
                    AutismPerf.endJoinSpike("join.module.onGameJoin." + module.id(), perf);
                } else {
                    joinModule(module);
                }
            }
        }
    }

    private static void joinModule(PackModule module) {
        if (module.isAddon()) runGuarded(module, "onGameJoin", module::onGameJoin);
        else module.onGameJoin();
    }

    public static void onGameLeft() {
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled() || module.ticksWhenDisabled()) {
                if (module.isAddon()) runGuarded(module, "onGameLeft", module::onGameLeft);
                else module.onGameLeft();
            }
        }
        KEY_STATES.clear();
        menuKeyDown = false;
    }

    public static boolean onPacketSend(Packet<?> packet) {
        if (!hasActivePacketEventModules()) return false;
        for (PackModule module : packetSendModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "onPacketSend", () -> module.onPacketSend(packet))
                : module.onPacketSend(packet);
            if (cancel) return true;
        }
        return false;
    }

    public static boolean onPacketReceive(Packet<?> packet) {
        if (!hasActivePacketEventModules() && disabledPacketReceiveModules().isEmpty()) return false;
        for (PackModule module : packetReceiveModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "onPacketReceive", () -> module.onPacketReceive(packet))
                : module.onPacketReceive(packet);
            if (cancel) return true;
        }
        for (PackModule module : disabledPacketReceiveModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "onPacketReceive", () -> module.onPacketReceive(packet))
                : module.onPacketReceive(packet);
            if (cancel) return true;
        }
        return false;
    }

    public static void onSoundPacket(ClientboundSoundPacket packet) {
        if (!initialized || PackHideState.isActive()) return;
        if (!hasSoundHooks()) return;
        for (PackModule module : soundModules()) {
            if (module.isAddon()) runGuarded(module, "onSoundPacket", () -> module.onSoundPacket(packet));
            else module.onSoundPacket(packet);
        }
    }

    public static void onBlockBreakingProgress(BlockPos pos, Direction direction) {
        if (!initialized || PackHideState.isActive()) return;
        if (!hasBlockBreakingProgressHooks()) return;
        for (PackModule module : blockBreakingProgressModules()) {
            if (module.isAddon()) runGuarded(module, "onBlockBreakingProgress", () -> module.onBlockBreakingProgress(pos, direction));
            else module.onBlockBreakingProgress(pos, direction);
        }
    }

    private static void applyOfflineConfiguredTransitions() {
        if (OFFLINE_ENABLE_TRANSITIONS.isEmpty() && OFFLINE_SETTING_CHANGES.isEmpty() && OFFLINE_SETTING_RESETS.isEmpty()) return;
        Map<String, Boolean> transitions = new LinkedHashMap<>(OFFLINE_ENABLE_TRANSITIONS);
        Map<String, Set<String>> settingChanges = new LinkedHashMap<>();
        OFFLINE_SETTING_CHANGES.forEach((id, values) -> settingChanges.put(id, new LinkedHashSet<>(values)));
        Set<String> settingResets = new LinkedHashSet<>(OFFLINE_SETTING_RESETS);
        OFFLINE_ENABLE_TRANSITIONS.clear();
        OFFLINE_ENABLE_BASELINES.clear();
        OFFLINE_SETTING_CHANGES.clear();
        OFFLINE_SETTING_RESETS.clear();
        for (PackModule module : MODULES.values()) {
            if (!module.isEnabled() || Boolean.TRUE.equals(transitions.get(module.id()))) continue;
            if (!settingResets.contains(module.id()) && !settingChanges.containsKey(module.id())) continue;
            try {
                module.applyConfiguredSettings(settingChanges.getOrDefault(module.id(), Set.of()), settingResets.contains(module.id()));
            } catch (Throwable error) {
                if (module.isAddon()) reportAddonModuleError(module, "deferred settings", error);
                else autismclient.AutismClientAddon.LOG.error("[Modules] Deferred title-screen settings failed for '{}'.", module.id(), error);
            }
        }
        for (Map.Entry<String, Boolean> entry : transitions.entrySet()) {
            PackModule module = MODULES.get(entry.getKey());
            if (module == null || module.isEnabled() != entry.getValue()) continue;
            try {
                if (entry.getValue()) module.onEnable();
                else module.onDisable();
            } catch (Throwable error) {
                if (module.isAddon()) reportAddonModuleError(module, entry.getValue() ? "deferred onEnable" : "deferred onDisable", error);
                else autismclient.AutismClientAddon.LOG.error("[Modules] Deferred title-screen transition failed for '{}'.", module.id(), error);
            }
        }
        invalidateCaches(true);
    }

    public static float modifyBlockDestroyProgress(float original, BlockPos pos) {
        if (!initialized || PackHideState.isActive()) return original;
        PackModule module = get("fast-break");
        if (!(module instanceof PackBuiltinModules.FastBreakModule fastBreak)
            || !fastBreak.isEnabled()
            || !fastBreak.usesNormalDestroyModifier()
            || MC == null
            || MC.level == null) {
            return original;
        }
        BlockState state = pos == null ? null : MC.level.getBlockState(pos);
        return fastBreak.modifyNormalDestroyProgress(original, state, pos);
    }

    public static boolean onStartDestroyBlock(BlockPos pos, Direction direction) {
        if (!initialized || PackHideState.isActive()) return false;
        if (!hasStartBreakingHooks()) return false;
        for (PackModule module : startBreakingModules()) {
            boolean handled = module.isAddon()
                ? runGuardedBool(module, "onStartDestroyBlock", () -> module.onStartDestroyBlock(pos, direction))
                : module.onStartDestroyBlock(pos, direction);
            if (handled) return true;
        }
        return false;
    }

    public static boolean dispatchStartBreakingBlock(BlockPos pos, Direction direction) {
        if (!initialized || PackHideState.isHardLocked() || !hasStartBreakingHooks()) return false;
        for (PackModule module : startBreakingModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "shouldCancelStartBreakingBlock",
                    () -> module.shouldCancelStartBreakingBlock(pos, direction))
                : module.shouldCancelStartBreakingBlock(pos, direction);
            if (cancel) return true;
            if (module.isAddon()) {
                runGuarded(module, "onStartBreakingBlock", () -> module.onStartBreakingBlock(pos, direction));
            } else {
                module.onStartBreakingBlock(pos, direction);
            }
        }
        return false;
    }

    public static boolean shouldCancelAttack(net.minecraft.world.phys.HitResult hitResult) {
        if (!initialized || PackHideState.isHardLocked() || !hasAttackUseHooks()) return false;
        for (PackModule module : attackUseModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "shouldCancelAttack", () -> module.shouldCancelAttack(hitResult))
                : module.shouldCancelAttack(hitResult);
            if (cancel) return true;
        }
        return false;
    }

    public static boolean shouldCancelUse(net.minecraft.world.phys.HitResult hitResult, net.minecraft.world.InteractionHand hand) {
        if (!initialized || PackHideState.isHardLocked() || !hasAttackUseHooks()) return false;
        for (PackModule module : attackUseModules()) {
            boolean cancel = module.isAddon()
                ? runGuardedBool(module, "shouldCancelUse", () -> module.shouldCancelUse(hitResult, hand))
                : module.shouldCancelUse(hitResult, hand);
            if (cancel) return true;
        }
        return false;
    }

    public static void appendTooltip(net.minecraft.world.item.ItemStack stack, List<?> lines) {
        if (!initialized || PackHideState.isHardLocked() || !hasTooltipHooks()) return;
        for (PackModule module : tooltipModules()) {
            if (module.isAddon()) runGuarded(module, "appendTooltip", () -> module.appendTooltip(stack, lines));
            else module.appendTooltip(stack, lines);
        }
    }

    private static void tickModuleKeybinds() {
        if (PackHideState.isActive()) {
            KEY_STATES.clear();
            return;
        }
        if (keyboundModules().isEmpty()) {
            KEY_STATES.clear();
            return;
        }
        for (PackModule module : keyboundModules()) {
            int bind = module.keybind();
            boolean pressed = AutismBindUtil.isBindPressed(MC, bind);
            boolean wasPressed = KEY_STATES.getOrDefault(module.id(), false);
            if (pressed && !wasPressed && AutismInputGate.canRunAutismKeybinds() && module.hasActivationToggle()) module.toggle();
            KEY_STATES.put(module.id(), pressed);
        }
    }

    private static boolean overridesPacketEvents(PackModule module) {
        if (module == null) return false;
        return overridesModuleMethod(module, "onPacketSend", Packet.class)
            || overridesModuleMethod(module, "onPacketReceive", Packet.class);
    }

    public static List<PackModule> startBreakingModulesForDispatch() {
        return startBreakingModules();
    }

    public static List<PackModule> attackUseModulesForDispatch() {
        return attackUseModules();
    }

    public static List<PackModule> tooltipModulesForDispatch() {
        return tooltipModules();
    }

    private static boolean overridesModuleMethod(PackModule module, String methodName, Class<?>... parameterTypes) {
        if (module == null || methodName == null || methodName.isBlank()) return false;
        try {
            return module.getClass().getMethod(methodName, parameterTypes).getDeclaringClass() != PackModule.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public static void clearKeyStates() {
        KEY_STATES.clear();
        menuKeyDown = false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace(' ', '-').replace("_", "-");
    }
}
