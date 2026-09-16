package com.mahirsn.drmfrp;

import net.minecraft.client.resources.server.PackReloadConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which server packs are currently applied, so a reload that would
 * apply the very same ones can be skipped.
 *
 * The identity of a pack here is the name of its downloaded file, which
 * Minecraft sets to the SHA-1 of the content — the same hash the server
 * announced. Two pushes of identical content therefore look identical even
 * when the server sends a fresh UUID each time, which is exactly the case
 * this mod exists for: 985 recorded downloads on the machine this was written
 * on contained 24 packs fetched four times each under a different UUID and an
 * unchanged hash.
 */
public final class PackFingerprint {

    /** Hashes of the packs believed to be applied right now. Empty means none. */
    private static List<String> applied = List.of();

    /** Set once a server has pushed anything, so the menu's empty reload is recognisable. */
    private static boolean hasServerPacks = false;

    private PackFingerprint() {
    }

    public static List<String> of(List<PackReloadConfig.IdAndPath> packs) {
        List<String> out = new ArrayList<>(packs.size());
        for (PackReloadConfig.IdAndPath p : packs) {
            Path file = p.path().getFileName();
            out.add(file == null ? p.id().toString() : file.toString());
        }
        return out;
    }

    /**
     * Whether this reload can be skipped.
     *
     * Two cases are worth skipping. The obvious one is a rejoin that asks for
     * exactly what is already applied. The other is the empty reload the game
     * runs on disconnect: dropping the packs there is what makes the next join
     * expensive, so holding on to them is what turns a rejoin into a no-op.
     */
    public static boolean canSkip(List<String> incoming) {
        if (incoming.equals(applied) && !applied.isEmpty()) {
            return true;
        }
        return incoming.isEmpty() && hasServerPacks && !applied.isEmpty();
    }

    public static void remember(List<String> incoming) {
        applied = List.copyOf(incoming);
        if (!incoming.isEmpty()) {
            hasServerPacks = true;
        }
    }

    public static List<String> applied() {
        return applied;
    }

    /** Static state outlives a test method; each case needs a clean slate. */
    static void resetForTests() {
        applied = List.of();
        hasServerPacks = false;
    }
}
