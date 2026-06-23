package autismclient.modules;

import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismConfig;
import autismclient.util.AutismContainerHold;
import autismclient.util.AutismInputClicker;
import autismclient.util.AutismInstaBreakRenderer;
import autismclient.util.AutismLANSync;
import autismclient.util.AutismNotifications;
import autismclient.util.AutismOverlayManager;
import autismclient.util.AutismPayloadChannelSubscriptionManager;
import autismclient.util.AutismPayloadStudySession;
import autismclient.util.AutismSharedState;
import autismclient.util.macro.MacroExecutor;
import autismclient.util.macro.PacketGateManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PackHideState {
    public static final String HIDE_ID = "hide";

    private static boolean silentOverride;

    private PackHideState() {
    }

    public static boolean isActive() {
        AutismConfig config = AutismConfig.getGlobal();
        AutismConfig.ModuleState state = config.modules.get(HIDE_ID);
        return state != null && state.enabled;
    }

    public static boolean isSilenced() {
        return silentOverride || isActive();
    }

    public static boolean isHardLocked() {
        return isActive();
    }

    public static boolean shouldSuppressClientOutput() {
        return silentOverride || isHardLocked();
    }

    public static boolean isHideModule(PackModule module) {
        return module != null && HIDE_ID.equals(module.id());
    }

    public static boolean isHideModuleName(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return false;
        String normalized = idOrName.toLowerCase(Locale.ROOT).replace(' ', '-').replace("_", "-");
        return HIDE_ID.equals(normalized) || "panic-mode".equals(normalized) || "panic".equals(normalized);
    }

    public static boolean blocksEnable(PackModule module) {
        return isActive() && !isHideModule(module);
    }

    public static void enable(PackModule hideModule) {
        withSilence(() -> {
            enterHardLockCleanup();
            AutismClientMessaging.clearClientMessages();
            AutismConfig config = AutismConfig.getGlobal();
            Set<String> enabled = new LinkedHashSet<>();
            for (PackModule module : PackModuleRegistry.all()) {
                if (module == null || isHideModule(module)) continue;
                if (module.isEnabled()) enabled.add(module.id());
            }
            config.hideRestoreModules = new ArrayList<>(enabled);

            stopRuntimeWork();
            for (PackModule module : PackModuleRegistry.all()) {
                if (module == null || isHideModule(module)) continue;
                if (module.isEnabled()) module.setEnabledSilently(false);
            }
            PackModuleRenderUtil.refreshWorldRenderer();

            autismclient.util.AutismMeteorBridge.disableAndSave(config);

            autismclient.util.AutismEssentialBridge.disable(config);
            config.save();
        });
    }

    public static void disableAndRestore(PackModule hideModule) {
        withSilence(() -> {
            AutismConfig config = AutismConfig.getGlobal();
            List<String> restore = config.hideRestoreModules == null ? List.of() : new ArrayList<>(config.hideRestoreModules);
            config.hideRestoreModules = new ArrayList<>();
            config.save();

            for (String id : restore) {
                PackModule module = PackModuleRegistry.get(id);
                if (module != null && !isHideModule(module)) module.setEnabledSilently(true);
            }
            PackModuleRenderUtil.refreshWorldRenderer();

            autismclient.util.AutismMeteorBridge.restore(config);

            autismclient.util.AutismEssentialBridge.restore(config);
            config.save();

            if (config.lanSyncEnabled) {
                AutismLANSync.getInstance().start();
            }
            PackModuleRegistry.clearKeyStates();
            AutismInputClicker.clear();
        });
    }

    public static void enforceStartupHidden() {
        if (!isActive()) return;
        withSilence(() -> {
            enterHardLockCleanup();
            stopRuntimeWork();
            for (PackModule module : PackModuleRegistry.all()) {
                if (module == null || isHideModule(module)) continue;
                if (module.isEnabled()) module.setEnabledSilently(false);
            }
            PackModuleRenderUtil.refreshWorldRenderer();

            autismclient.util.AutismMeteorBridge.enforceHidden();

            autismclient.util.AutismEssentialBridge.disable(AutismConfig.getGlobal());
            AutismClientMessaging.clearClientMessages();
        });
    }

    private static void enterHardLockCleanup() {
        stopRuntimeWork();
        AutismNotifications.clear();
        AutismOverlayManager.get().hideAllInteractiveOverlays();
        AutismInstaBreakRenderer.clear();
        AutismPayloadStudySession.stop();
        AutismPayloadChannelSubscriptionManager.clear();
        AutismClientMessaging.clearClientMessages();
    }

    public static void stopRuntimeWork() {
        PackModuleRegistry.clearKeyStates();
        AutismInputClicker.clear();
        AutismLANSync.getInstance().stopSilently();
        if (MacroExecutor.isRunning()) MacroExecutor.stop();
        AutismContainerHold.clearAll();
        PacketGateManager.clearAll();
        AutismSharedState shared = AutismSharedState.get();
        shared.setSendGuiPackets(true);
        shared.setDelayGuiPackets(false);
        shared.setStaggeredPacketSend(false);
        shared.setCaptureMode(false);
        shared.clearQueuedPackets();
    }

    private static void withSilence(Runnable action) {
        boolean previous = silentOverride;
        silentOverride = true;
        try {
            action.run();
        } finally {
            silentOverride = previous;
        }
    }
}
