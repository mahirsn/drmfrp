package com.mahirsn.drmfrp;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * config/drmfrp.properties, read once at startup. The file is rewritten with
 * every key and its comment, so missing keys show up with their defaults.
 */
public final class Config {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");

    private static boolean loaded;

    public static boolean enabled = true;
    public static boolean preloadLastServerPacks = true;
    public static boolean zipIndex = true;
    public static boolean packCache = true;
    public static int packCacheSize = 3;
    public static boolean earlyApply = true;

    private Config() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("drmfrp.properties");
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Properties p = new Properties();
        if (Files.isRegularFile(file())) {
            try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                LOGGER.warn("[drmfrp] could not read {}, using defaults", file(), e);
            }
        }
        enabled = bool(p, "enabled", enabled);
        preloadLastServerPacks = bool(p, "preloadLastServerPacks", preloadLastServerPacks);
        zipIndex = bool(p, "zipIndex", zipIndex);
        packCache = bool(p, "packCache", packCache);
        packCacheSize = Math.max(1, integer(p, "packCacheSize", packCacheSize));
        earlyApply = bool(p, "earlyApply", earlyApply);
        save();
        LOGGER.info("[drmfrp] enabled={} preloadLastServerPacks={} zipIndex={} packCache={} packCacheSize={} earlyApply={}",
                enabled, preloadLastServerPacks, zipIndex, packCache, packCacheSize, earlyApply);
    }

    public static void save() {
        String text = """
                # Turns the whole mod on or off. When off, resource packs load exactly as in vanilla.
                enabled=%s
                # Loads the last server's packs together with the game at startup,
                # so the first join does not need a second reload.
                preloadLastServerPacks=%s
                # Lists files in zip resource packs from an index built once per reload,
                # instead of reading the whole zip for every lookup. Same result, much faster reloads.
                zipIndex=%s
                # Keeps the atlases and models of recently visited servers' packs,
                # so going back to one of them does not rebuild them.
                packCache=%s
                # How many other servers' packs to keep. Each one uses about 40 MB of video memory.
                packCacheSize=%d
                # While a server's pack downloads, shows the copy already on disk.
                # The download still runs; if the pack changed, it is loaded when the download ends.
                earlyApply=%s
                """.formatted(enabled, preloadLastServerPacks, zipIndex, packCache, packCacheSize, earlyApply);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[drmfrp] could not write {}", file(), e);
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
