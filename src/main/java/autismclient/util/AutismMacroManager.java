package autismclient.util;

import autismclient.AutismClientAddon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class AutismMacroManager {
    private static AutismMacroManager INSTANCE;
    private List<AutismMacro> macros = new ArrayList<>();
    private final File saveFile;
    private volatile long revision;

    private AutismMacroManager() {
        saveFile = new File(AutismClientAddon.FOLDER, "autism_macros.nbt");
        load();
    }

    public static synchronized AutismMacroManager get() {
        if (INSTANCE == null) {
            INSTANCE = new AutismMacroManager();
        }
        return INSTANCE;
    }

    public synchronized String createUniqueName(String preferredName) {
        String baseName = preferredName == null || preferredName.isBlank() ? "New Macro" : preferredName.trim();
        String candidate = baseName;
        int suffix = 1;
        while (get(candidate) != null) {
            candidate = baseName + " (" + suffix++ + ")";
        }
        return candidate;
    }

    public synchronized AutismMacro addImportedCopy(AutismMacro source, String preferredName) {
        if (source == null) return null;

        AutismMacro copy = source.deepCopy();
        copy.name = createUniqueName(preferredName != null && !preferredName.isBlank() ? preferredName : source.name);
        add(copy);
        return copy;
    }

    public synchronized void add(AutismMacro macro) {
        if (macro == null) return;
        macros.add(macro);
        save();
    }

    public synchronized AutismMacro get(String name) {
        if (name == null) return null;
        for (AutismMacro macro : macros) {
            if (macro != null && macro.name != null && macro.name.equalsIgnoreCase(name)) return macro;
        }
        return null;
    }

    public synchronized List<AutismMacro> getAll() {
        return new ArrayList<>(macros);
    }

    public long getRevision() {
        return revision;
    }

    public synchronized void remove(AutismMacro macro) {
        if (macro == null) return;
        if (autismclient.util.macro.MacroExecutor.isMacroRunning(macro.name)) {
            autismclient.util.macro.MacroExecutor.stopMacro(macro.name);
            AutismClientMessaging.sendPrefixed("§eStopped running macro before deletion: " + macro.name);
        }

        if (macros.remove(macro)) {
            save();
            AutismClientMessaging.sendPrefixed("§aDeleted macro: " + macro.name);

        AutismMacroEditorOverlay editor = AutismMacroEditorOverlay.getSharedOverlay();
            if (editor != null && editor.isEditingMacro(macro)) {
                editor.close();
            }

            if (AutismLANSync.getInstance().isInSession()) {
                AutismLANSync.getInstance().broadcastMacroDeletion(macro.name);
            }
        }
    }

    public void delete(AutismMacro macro) {
        remove(macro);
    }

    public void executeMacro(String name) {
        AutismMacro macro = get(name);
        if (macro != null) {
            macro.execute();
            AutismClientMessaging.sendPrefixed("§aExecuting macro: " + macro.name);
        } else {
            AutismClientMessaging.sendPrefixed("§cMacro not found: " + name);
        }
    }

    public void stopMacro() {
        if (autismclient.util.macro.MacroExecutor.isVisibleRunning()) {
            autismclient.util.macro.MacroExecutor.stop();
        } else {
            AutismClientMessaging.sendPrefixed("§eNo macro is currently running.");
        }
    }

    public synchronized void save() {
        revision++;
        Path target = saveFile.toPath();
        Path temp = target.resolveSibling(saveFile.getName() + ".tmp");
        Path backup = target.resolveSibling(saveFile.getName() + ".bak");
        try {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (AutismMacro macro : macros) {
                if (macro != null) list.add(macro.toTag());
            }
            tag.put("macros", list);
            Files.createDirectories(target.getParent());
            NbtIo.write(tag, temp);
            if (Files.exists(target)) {
                try {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception backupError) {
                    AutismClientAddon.LOG.warn("Could not update macro backup {}; continuing with atomic save", backup, backupError);
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to save Autism macros", e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
        }

        if (AutismLANSync.getInstance().isInSession()) {
            AutismLANSync.getInstance().broadcastMacroList();
        }
    }

    public synchronized void load() {
        Path target = saveFile.toPath();
        Path backup = target.resolveSibling(saveFile.getName() + ".bak");
        if (!Files.exists(target) && !Files.exists(backup)) return;

        try {
            if (!Files.exists(target)) throw new IllegalStateException("Main macro file is missing");
            macros = loadFile(target);
            revision++;
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to load Autism macros; trying backup", e);
            if (!Files.exists(backup)) return;
            try {
                macros = loadFile(backup);
                revision++;
                AutismClientAddon.LOG.warn("Recovered Autism macros from {}", backup);
            } catch (Exception backupError) {
                AutismClientAddon.LOG.error("Failed to load Autism macro backup", backupError);
            }
        }
    }

    private List<AutismMacro> loadFile(Path path) throws Exception {
        CompoundTag tag = NbtIo.read(path);
        if (tag == null) throw new IllegalStateException("Macro file was empty");
        if (!(tag.get("macros") instanceof ListTag list)) {
            throw new IllegalStateException("Macro file has no macro list");
        }
        List<AutismMacro> loaded = new ArrayList<>();
        for (Tag element : list) {
            if (!(element instanceof CompoundTag macroTag)) continue;
            try {
                loaded.add(new AutismMacro().fromTag(macroTag));
            } catch (Throwable macroError) {
                AutismClientAddon.LOG.warn("Skipping one damaged macro entry from {}", path, macroError);
            }
        }
        return loaded;
    }
}
