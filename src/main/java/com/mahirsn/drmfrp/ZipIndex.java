package com.mahirsn.drmfrp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Experimental: answers a zip pack's directory listings from a sorted index.
 *
 * Vanilla walks every entry of the zip for every listing. A pack with a few
 * thousand files, split into overlays, listed dozens of times per reload by
 * mods looking for folders it does not have, spends most of the reload there.
 * The index is built once per opened zip and gives the same entries in the
 * same (zip) order, so listings are unchanged.
 */
public final class ZipIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");

    private static final Map<ZipFile, ZipIndex> INDEXES = Collections.synchronizedMap(new WeakHashMap<>());

    private static Field accessField;
    private static Method openMethod;
    private static boolean broken;

    private final String[] names;
    private final ZipEntry[] entries;
    /** Positions in zip order, sorted by name. */
    private final int[] byName;

    private ZipIndex(ZipFile zip) {
        List<ZipEntry> list = new ArrayList<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            if (!entry.isDirectory()) {
                list.add(entry);
            }
        }
        entries = list.toArray(new ZipEntry[0]);
        names = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            names[i] = entries[i].getName();
        }
        Integer[] order = new Integer[entries.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing(i -> names[i]));
        byName = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            byName[i] = order[i];
        }
    }

    public static boolean enabled() {
        Config.load();
        return Config.enabled && Config.zipIndex && !broken;
    }

    /** The pack's open zip, or null when it cannot be had; then vanilla handles the call. */
    public static ZipFile zipOf(Object filePackResources) {
        try {
            if (accessField == null) {
                Field f = filePackResources.getClass().getDeclaredField("zipFileAccess");
                f.setAccessible(true);
                Method m = f.getType().getDeclaredMethod("getOrCreateZipFile");
                m.setAccessible(true);
                openMethod = m;
                accessField = f;
            }
            return (ZipFile) openMethod.invoke(accessField.get(filePackResources));
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOGGER.warn("[drmfrp] zip index unavailable, using vanilla listing", e);
            return null;
        }
    }

    /** Every file entry whose name starts with prefix, in zip order. */
    public static void forEach(ZipFile zip, String prefix, BiConsumer<String, ZipEntry> out) {
        ZipIndex index;
        synchronized (INDEXES) {
            index = INDEXES.computeIfAbsent(zip, ZipIndex::new);
        }
        index.find(prefix, out);
    }

    private void find(String prefix, BiConsumer<String, ZipEntry> out) {
        int lo = 0, hi = byName.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (names[byName[mid]].compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int end = lo;
        while (end < byName.length && names[byName[end]].startsWith(prefix)) {
            end++;
        }
        int[] hits = Arrays.copyOfRange(byName, lo, end);
        Arrays.sort(hits);
        for (int i : hits) {
            out.accept(names[i], entries[i]);
        }
    }
}
