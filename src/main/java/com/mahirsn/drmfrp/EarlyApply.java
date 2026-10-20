package com.mahirsn.drmfrp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.PackReloadConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Experimental: while a server's packs download, shows the copies already on
 * disk. The download still runs; when it ends the usual comparison either
 * finds the same content and skips the reload, or reloads with the new one.
 *
 * Packs sent with a hash are found by that hash. Packs sent without one, which
 * vanilla downloads again on every join, are found by the URL they last came
 * from.
 */
public final class EarlyApply {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");
    private static final int MAX_URLS = 64;
    /** Lets a server push all of its packs before deciding. */
    private static final long SETTLE_MS = 500;

    /** Implemented on DownloadedPackSource. */
    public interface Target {
        boolean drmfrp$applyEarly(List<PackReloadConfig.IdAndPath> packs);
    }

    private static Map<String, Path> urls;
    private static int generation;

    private EarlyApply() {
    }

    private static Path urlFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("drmfrp-url-cache.txt");
    }

    private static Map<String, Path> urls() {
        if (urls == null) {
            urls = new LinkedHashMap<>(16, 0.75f, true);
            try {
                if (Files.isRegularFile(urlFile())) {
                    for (String line : Files.readAllLines(urlFile(), StandardCharsets.UTF_8)) {
                        int tab = line.indexOf('\t');
                        if (tab > 0) {
                            urls.put(line.substring(0, tab), Path.of(line.substring(tab + 1)));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                urls.clear();
            }
        }
        return urls;
    }

    /** Where the pack last downloaded from this URL was stored. */
    public static void rememberUrl(String url, Path path) {
        Map<String, Path> m = urls();
        Path abs = path.toAbsolutePath();
        if (abs.equals(m.get(url))) {
            // The lookup already moved it to the recent end.
            return;
        }
        m.put(url, abs);
        while (m.size() > MAX_URLS) {
            m.remove(m.keySet().iterator().next());
        }
        StringBuilder out = new StringBuilder();
        m.forEach((u, p) -> out.append(u).append('\t').append(p).append('\n'));
        try {
            Path tmp = urlFile().resolveSibling(urlFile().getFileName() + ".tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, urlFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[drmfrp] could not save the pack URL list", e);
        }
    }

    /** A pack was pushed or accepted; decide shortly, once the server is done pushing. */
    public static void schedule(Object manager) {
        if (!PackCache.earlyApplyEnabled()) {
            return;
        }
        int gen = ++generation;
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> mc.execute(() -> {
            if (gen == generation) {
                check(mc, manager);
            }
        }), CompletableFuture.delayedExecutor(SETTLE_MS, TimeUnit.MILLISECONDS));
    }

    private static void check(Minecraft mc, Object manager) {
        try {
            List<PackReloadConfig.IdAndPath> packs = new ArrayList<>();
            boolean waiting = false;
            for (Object pack : (List<?>) get(manager, "packs")) {
                if (get(pack, "removalReason") != null) {
                    continue;
                }
                if (!(boolean) get(pack, "promptAccepted")) {
                    // The player has not said yes; nothing may be shown yet.
                    return;
                }
                UUID id = (UUID) get(pack, "id");
                boolean done = "DONE".equals(String.valueOf(get(pack, "downloadStatus")));
                Path file = done ? (Path) get(pack, "path") : known(id, get(pack, "url"), get(pack, "hash"));
                if (file == null) {
                    return;
                }
                waiting |= !done;
                packs.add(new PackReloadConfig.IdAndPath(id, file));
            }
            if (packs.isEmpty() || !waiting) {
                return;
            }
            ((Target) mc.getDownloadedPackSource()).drmfrp$applyEarly(packs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[drmfrp] could not check for packs to show early", e);
        }
    }

    private static Path known(UUID id, Object url, Object hash) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("downloads").resolve(id.toString());
        if (hash != null) {
            Path f = dir.resolve(hash.toString());
            return Files.isRegularFile(f) ? f : null;
        }
        Path f = url == null ? null : urls().get(url.toString());
        return f != null && Files.isRegularFile(f) ? f : null;
    }

    /** The URL a pack the manager holds was requested from. */
    public static String urlOf(Object manager, UUID id) {
        try {
            for (Object pack : (List<?>) get(manager, "packs")) {
                if (id.equals(get(pack, "id"))) {
                    Object url = get(pack, "url");
                    return url == null ? null : url.toString();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Nothing to remember then.
        }
        return null;
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
