package com.mahirsn.drmfrp;

import net.minecraft.client.resources.server.PackReloadConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The last server's packs, kept on disk so the game can load them at startup.
 *
 * Without this the first join after launching pays twice: once for the
 * startup load without the pack, and again when the server hands it over.
 * Loading it at startup folds both into one, and the join then asks for
 * exactly what is applied, which {@link PackFingerprint} already skips.
 *
 * One line per pack, {@code uuid<TAB>path}, in the order the server gave.
 */
public final class LastPacks {

    private LastPacks() {
    }

    public static void save(Path file, List<PackReloadConfig.IdAndPath> packs) throws IOException {
        StringBuilder out = new StringBuilder();
        for (PackReloadConfig.IdAndPath p : packs) {
            out.append(p.id()).append('\t').append(p.path().toAbsolutePath()).append('\n');
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        // Written aside and moved, so a crash mid-write leaves the old list
        // rather than half of a new one.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Packs whose files still exist. The game prunes its download cache, and a
     * pack that has gone is simply left out rather than failing the load.
     * Any damage to the file means no preload at all, never a broken start.
     */
    public static List<PackReloadConfig.IdAndPath> load(Path file) {
        List<PackReloadConfig.IdAndPath> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                UUID id = UUID.fromString(line.substring(0, tab));
                Path path = Path.of(line.substring(tab + 1));
                if (Files.isRegularFile(path)) {
                    out.add(new PackReloadConfig.IdAndPath(id, path));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return new ArrayList<>();
        }
        return out;
    }
}
