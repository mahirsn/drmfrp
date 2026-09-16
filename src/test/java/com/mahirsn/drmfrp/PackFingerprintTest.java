package com.mahirsn.drmfrp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole mod is one decision — skip this reload or not — so that decision is
 * what gets tested. A stale pack is the failure that would actually hurt, so
 * every case that must NOT skip is here too.
 */
class PackFingerprintTest {

    @BeforeEach
    void reset() {
        PackFingerprint.resetForTests();
    }

    @Test
    void firstJoinReloads() {
        assertFalse(PackFingerprint.canSkip(List.of("aaa")));
    }

    @Test
    void rejoinWithSamePackSkips() {
        PackFingerprint.remember(List.of("aaa"));
        assertTrue(PackFingerprint.canSkip(List.of("aaa")));
    }

    @Test
    void changedPackReloads() {
        PackFingerprint.remember(List.of("aaa"));
        assertFalse(PackFingerprint.canSkip(List.of("bbb")));
    }

    @Test
    void addedPackReloads() {
        PackFingerprint.remember(List.of("aaa"));
        assertFalse(PackFingerprint.canSkip(List.of("aaa", "bbb")));
    }

    @Test
    void removedPackReloads() {
        PackFingerprint.remember(List.of("aaa", "bbb"));
        assertFalse(PackFingerprint.canSkip(List.of("aaa")));
    }

    @Test
    void reorderedPackReloads() {
        // Order decides which pack wins a conflict, so a swap is a real change.
        PackFingerprint.remember(List.of("aaa", "bbb"));
        assertFalse(PackFingerprint.canSkip(List.of("bbb", "aaa")));
    }

    @Test
    void disconnectKeepsPacksApplied() {
        // The empty reload on disconnect is the one worth suppressing: dropping
        // the packs there is what makes the next join expensive.
        PackFingerprint.remember(List.of("aaa"));
        assertTrue(PackFingerprint.canSkip(List.of()));
    }

    @Test
    void emptyWithNothingAppliedDoesNotSkip() {
        assertFalse(PackFingerprint.canSkip(List.of()));
    }

    @Test
    void changedPackAfterDisconnectStillReloads() {
        // The case the whole question is about: hold packs across a disconnect,
        // then have the server come back with different content.
        PackFingerprint.remember(List.of("aaa"));
        assertTrue(PackFingerprint.canSkip(List.of()));   // disconnect, held
        assertFalse(PackFingerprint.canSkip(List.of("bbb")));
    }
}
