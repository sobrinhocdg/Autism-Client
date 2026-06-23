package autismclient.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackModuleOptionTest {
    @Test
    void ordinarySettingsAreAvailableOffline() {
        PackModuleOption option = PackModuleOption.bool("enabled", "Enabled", false);

        assertTrue(option.isAvailable(false, false));
    }

    @Test
    void actionsRequireAWorldUnlessExplicitlyMarkedSafe() {
        PackModuleOption action = PackModuleOption.action("capture", "Capture", () -> {});

        assertFalse(action.isAvailable(false, false));
        assertTrue(action.isAvailable(true, false));
        assertTrue(action.availableOffline().isAvailable(false, false));
    }

    @Test
    void containerActionsRequireAnOpenContainer() {
        PackModuleOption option = PackModuleOption.action("pick", "Pick", () -> {}).requiresContainer();

        assertFalse(option.isAvailable(false, false));
        assertFalse(option.isAvailable(true, false));
        assertTrue(option.isAvailable(true, true));
    }
}
