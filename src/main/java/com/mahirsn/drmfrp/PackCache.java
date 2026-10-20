package com.mahirsn.drmfrp;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Experimental: keeps the prepared atlases and models of the last few server
 * pack sets, so going back to a server reuses them instead of rebuilding.
 *
 * A reload still runs, so every listener and every mod hook on it still
 * fires, but the two expensive preparations are answered from here: the
 * atlases (sprite loading, stitching, GPU upload) and the baked models.
 * Measured on a real setup, these are the only listeners besides fonts,
 * shaders, sounds and language whose input changes between servers.
 *
 * Only reloads a server asked for use the cache. Any other reload (F3+T, the
 * pack screen, a changed option) empties it, since the packs under the
 * server pack may have changed.
 */
public final class PackCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");

    /** The prepared state of one pack set. */
    public static final class Entry {
        final String key;
        final Map<Identifier, SpriteLoader.Preparations> preps = new HashMap<>();
        final Map<Identifier, Map<Field, Object>> gpu = new HashMap<>();
        Object models;

        Entry(String key) {
            this.key = key;
        }

        boolean complete(int atlases) {
            return models != null && preps.size() == atlases && gpu.size() == atlases;
        }
    }

    private static final Pattern SERVER_PACK = Pattern.compile("server/[0-9A-F]{8}/([0-9a-f-]{36})");


    /** Server pack UUID to the SHA-1 its file is named by. */
    private static final Map<UUID, String> HASHES = new HashMap<>();

    /** Pack sets not applied right now, least recently used first. */
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    /** The applied state, or null when it must not be kept. */
    private static Entry live;
    /** The state being built by the reload in progress. */
    private static Entry incoming;
    /** A cached state the reload in progress takes its preparations from. */
    private static Entry hit;
    private static int atlasCount;

    private static boolean serverReload;

    private PackCache() {
    }

    public static boolean enabled() {
        Config.load();
        return Config.enabled && Config.packCache;
    }

    public static void rememberHashes(List<PackReloadConfig.IdAndPath> packs) {
        for (PackReloadConfig.IdAndPath p : packs) {
            Path name = p.path().getFileName();
            if (name != null) {
                HASHES.put(p.id(), name.toString().toLowerCase());
            }
        }
    }

    /** The reload about to start was asked for by a server. */
    public static void markServerReload() {
        serverReload = true;
    }

    /**
     * Identity of a pack stack: local packs by id, server packs by content
     * hash. Null when a server pack cannot be identified, which keeps it out
     * of the cache.
     */
    static String keyOf(ResourceManager manager, int mipLevels) {
        List<String> ids = new ArrayList<>();
        for (PackResources pack : manager.listPacks().toList()) {
            String id = pack.packId();
            Matcher m = SERVER_PACK.matcher(id);
            if (m.matches()) {
                String sha = HASHES.get(UUID.fromString(m.group(1)));
                if (sha == null) {
                    return null;
                }
                id = "server:" + sha;
            }
            ids.add(id);
        }
        return mipLevels + "|" + String.join(",", ids);
    }

    /** Called once at the start of every reload, before any listener runs. */
    public static void beginReload(ResourceManager manager, int mipLevels, int atlases) {
        boolean fromServer = serverReload;
        serverReload = false;
        hit = null;
        incoming = null;
        atlasCount = atlases;
        if (!enabled()) {
            clear();
            return;
        }
        String key = keyOf(manager, mipLevels);
        if (!fromServer) {
            // Anything below the server pack may have changed; nothing kept is trusted.
            clear();
        } else if (key != null && (live == null || !key.equals(live.key))) {
            Entry e = CACHE.get(key);
            if (e != null && e.complete(atlases)) {
                hit = e;
            }
        }
        incoming = new Entry(key);
        LOGGER.info("[drmfrp] {} reload: {}", fromServer ? "server" : "other",
                hit != null ? "reusing kept atlases and models" : "building");
    }

    /** The cached preparations for this atlas, or null to build it. */
    public static SpriteLoader.Preparations cachedPreparations(Identifier atlas) {
        return hit == null ? null : hit.preps.get(atlas);
    }

    public static Object cachedModels() {
        return hit == null ? null : hit.models;
    }

    public static void modelsApplied(Object state) {
        Entry e = incoming != null ? incoming : live;
        if (e != null) {
            e.models = state;
        }
    }

    /**
     * Before an atlas takes new contents. Returns true when the contents were
     * restored from the cache and the upload must not run.
     */
    public static boolean beforeUpload(TextureAtlas atlas, SpriteLoader.Preparations prep) {
        Identifier loc = atlas.location();
        if (incoming == null) {
            return false;
        }
        incoming.preps.put(loc, prep);
        if (live != null && live.key != null && !live.key.equals(incoming.key)) {
            live.gpu.put(loc, AtlasState.detach(atlas));
            if (!CACHE.containsKey(live.key)) {
                CACHE.put(live.key, live);
            }
        }
        if (hit != null && hit.preps.get(loc) == prep) {
            Map<Field, Object> state = hit.gpu.remove(loc);
            if (state != null) {
                AtlasState.attach(atlas, state);
                return true;
            }
        }
        return false;
    }

    /** All atlases have their new contents; the reload's state is now the applied one. */
    public static void atlasesDone() {
        if (incoming == null) {
            return;
        }
        if (hit != null) {
            CACHE.remove(hit.key);
            closeGpu(hit);
            if (incoming.models == null) {
                incoming.models = hit.models;
            }
        }
        live = incoming.key != null ? incoming : null;
        hit = null;
        evict();
    }

    public static void afterModels() {
        incoming = null;
    }

    /** A reload failed; nothing kept can be trusted to match what is applied. */
    public static void clear() {
        for (Entry e : CACHE.values()) {
            closeGpu(e);
        }
        CACHE.clear();
        live = null;
        hit = null;
    }

    private static void evict() {
        Iterator<Entry> it = CACHE.values().iterator();
        while (CACHE.size() > Config.packCacheSize && it.hasNext()) {
            Entry e = it.next();
            it.remove();
            closeGpu(e);
            LOGGER.info("[drmfrp] dropped a kept pack set");
        }
        if (!CACHE.isEmpty()) {
            LOGGER.info("[drmfrp] keeping {} pack set(s): {}", CACHE.size(),
                    CACHE.keySet().stream().map(k -> Integer.toHexString(k.hashCode())).collect(Collectors.joining(", ")));
        }
    }

    private static void closeGpu(Entry e) {
        for (Map.Entry<Identifier, Map<Field, Object>> g : e.gpu.entrySet()) {
            if (g.getValue() != null) {
                AtlasState.close(g.getKey(), g.getValue());
            }
        }
        e.gpu.clear();
    }

    /**
     * An atlas's contents, field by field. Taken generically so fields other
     * mods add to the atlas go with the texture they describe.
     */
    static final class AtlasState {
        private static List<Field> fields;

        private static List<Field> fields() {
            if (fields == null) {
                List<Field> out = new ArrayList<>();
                for (Class<?> c = TextureAtlas.class; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        int mod = f.getModifiers();
                        if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                            continue;
                        }
                        f.setAccessible(true);
                        out.add(f);
                    }
                }
                fields = out;
            }
            return fields;
        }

        static Map<Field, Object> detach(TextureAtlas atlas) {
            Map<Field, Object> state = new HashMap<>();
            try {
                for (Field f : fields()) {
                    state.put(f, f.get(atlas));
                }
                // Emptied so the coming upload has nothing to close.
                set(atlas, "texture", null);
                set(atlas, "textureView", null);
                set(atlas, "mipViews", new com.mojang.blaze3d.textures.GpuTextureView[0]);
                set(atlas, "sprites", List.of());
                set(atlas, "animatedTexturesStates", List.of());
                set(atlas, "texturesByName", Map.of());
                set(atlas, "missingSprite", null);
                set(atlas, "spriteUbos", null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("drmfrp could not detach atlas " + atlas.location(), e);
            }
            return state;
        }

        static void attach(TextureAtlas atlas, Map<Field, Object> state) {
            try {
                for (Map.Entry<Field, Object> e : state.entrySet()) {
                    e.getKey().set(atlas, e.getValue());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("drmfrp could not attach atlas " + atlas.location(), e);
            }
        }

        static void close(Identifier location, Map<Field, Object> state) {
            TextureAtlas holder = new TextureAtlas(location);
            attach(holder, state);
            holder.close();
        }

        private static void set(TextureAtlas atlas, String name, Object value) throws ReflectiveOperationException {
            for (Field f : fields()) {
                if (f.getName().equals(name)) {
                    f.set(atlas, value);
                    return;
                }
            }
            throw new NoSuchFieldException(name);
        }
    }
}
