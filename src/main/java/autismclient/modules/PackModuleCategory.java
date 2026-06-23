package autismclient.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PackModuleCategory {
    private static final List<PackModuleCategory> CATEGORIES = new ArrayList<>();

    public static final PackModuleCategory MOVEMENT = register("Movement");
    public static final PackModuleCategory PLAYER = register("Player");
    public static final PackModuleCategory MISC = register("Misc");
    public static final PackModuleCategory RENDER = register("Render");

    private final String key;
    private final String label;
    private final boolean addon;

    private PackModuleCategory(String key, String label, boolean addon) {
        this.key = key;
        this.label = label;
        this.addon = addon;
    }

    public static PackModuleCategory register(String label) {
        return registerInternal(toKey(label), label, false);
    }

    public static PackModuleCategory registerAddon(String addonId, String label) {
        String ns = addonId == null || addonId.isBlank() ? "addon" : addonId;
        String safeLabel = label == null || label.isBlank() ? ns : label;
        String key = toKey("ADDON " + ns);
        return registerInternal(key, safeLabel, true);
    }

    private static synchronized PackModuleCategory registerInternal(String key, String label, boolean addon) {
        for (PackModuleCategory existing : CATEGORIES) {
            if (existing.key.equals(key)) return existing;
        }
        PackModuleCategory category = new PackModuleCategory(key, label, addon);
        CATEGORIES.add(category);
        return category;
    }

    public boolean isAddon() {
        return addon;
    }

    private static String toKey(String label) {
        return label == null ? "" : label.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    public static List<PackModuleCategory> values() {
        return Collections.unmodifiableList(new ArrayList<>(CATEGORIES));
    }

    public String name() {
        return key;
    }

    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackModuleCategory that)) return false;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        return label;
    }
}
