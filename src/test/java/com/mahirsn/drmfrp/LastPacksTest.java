package com.mahirsn.drmfrp;

import net.minecraft.client.resources.server.PackReloadConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastPacksTest {

    @TempDir
    Path dir;

    /** Downloaded packs are named by the SHA-1 of their content. */
    private static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    private PackReloadConfig.IdAndPath pack(String name) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, "zip");
        return new PackReloadConfig.IdAndPath(UUID.randomUUID(), f);
    }

    @Test
    void roundTripKeepsOrderAndIdentity() throws Exception {
        Path file = dir.resolve("last.txt");
        List<PackReloadConfig.IdAndPath> packs = List.of(pack(sha('a')), pack(sha('b')));
        LastPacks.save(file, packs);
        List<PackReloadConfig.IdAndPath> back = LastPacks.load(file);
        assertEquals(PackFingerprint.of(packs), PackFingerprint.of(back));
        assertEquals(packs.get(0).id(), back.get(0).id());
    }

    @Test
    void packsWhoseFilesWereCleanedUpAreDropped() throws Exception {
        Path file = dir.resolve("last.txt");
        PackReloadConfig.IdAndPath kept = pack(sha('c'));
        PackReloadConfig.IdAndPath gone = pack(sha('d'));
        LastPacks.save(file, List.of(kept, gone));
        Files.delete(gone.path());
        assertEquals(List.of(sha('c')), PackFingerprint.of(LastPacks.load(file)));
    }

    @Test
    void noFileOrADamagedOneMeansNoPreload() throws Exception {
        assertTrue(LastPacks.load(dir.resolve("absent.txt")).isEmpty());
        Path bad = dir.resolve("bad.txt");
        Files.writeString(bad, "not-a-uuid\t/x\n");
        assertTrue(LastPacks.load(bad).isEmpty());
    }

    @Test
    void preloadedPacksMakeTheJoinASkip() throws Exception {
        PackFingerprint.resetForTests();
        Path file = dir.resolve("last.txt");
        List<PackReloadConfig.IdAndPath> packs = List.of(pack(sha('e')));
        LastPacks.save(file, packs);
        // What startup does:
        PackFingerprint.remember(PackFingerprint.of(LastPacks.load(file)));
        // The server then pushes the same content under a fresh UUID.
        PackReloadConfig.IdAndPath again = new PackReloadConfig.IdAndPath(UUID.randomUUID(), packs.get(0).path());
        assertTrue(PackFingerprint.canSkip(PackFingerprint.of(List.of(again))));
        PackFingerprint.resetForTests();
    }

    @Test
    void aWorldsResourcesZipNeverMatchesAnother() throws Exception {
        PackFingerprint.resetForTests();
        Path w1 = Files.createDirectories(dir.resolve("world1")).resolve("resources.zip");
        Path w2 = Files.createDirectories(dir.resolve("world2")).resolve("resources.zip");
        Files.writeString(w1, "a");
        Files.writeString(w2, "b");
        List<String> first = PackFingerprint.of(List.of(new PackReloadConfig.IdAndPath(UUID.randomUUID(), w1)));
        PackFingerprint.remember(first);
        List<String> second = PackFingerprint.of(List.of(new PackReloadConfig.IdAndPath(UUID.randomUUID(), w2)));
        assertTrue(!PackFingerprint.canSkip(second));
        assertTrue(!PackFingerprint.allHashed(first));
        PackFingerprint.resetForTests();
    }

    @Test
    void afterAFailedReloadTheNextPushReloadsForReal() throws Exception {
        PackFingerprint.resetForTests();
        List<String> set = List.of(sha('f'));
        PackFingerprint.remember(set);
        PackFingerprint.forget();
        assertTrue(!PackFingerprint.canSkip(set));
    }
}
