package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.EarlyApply;
import com.mahirsn.drmfrp.LastPacks;
import com.mahirsn.drmfrp.PackCache;
import com.mahirsn.drmfrp.PackFingerprint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Skips the resource reload when the server asks for packs that are already
 * applied.
 *
 * Vanilla keys its download cache on the UUID the server sends, so a server
 * that makes up a fresh UUID per connection defeats it entirely and every
 * rejoin pays for a full reload of identical bytes. The content hash is right
 * there in the downloaded file's name, so comparing that instead costs nothing
 * and catches the case the UUID misses.
 */
@Mixin(DownloadedPackSource.class)
public class DownloadedPackSourceMixin implements EarlyApply.Target {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");

    private static Path drmfrp$lastPacksFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("drmfrp-last-packs.txt");
    }

    @Shadow
    private RepositorySource packSource;

    @Shadow
    private PackReloadConfig.Callbacks pendingReload;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ServerPackManager manager;

    /** The reload showing on-disk copies while the real packs download, if any. */
    @Unique
    private static CompletableFuture<Void> drmfrp$early = null;

    /**
     * Completes when the early reload has applied, or fails when it broke.
     * Vanilla's own future for a reload never completes on failure, so this
     * one is failed from the recovery hook instead.
     */
    @Unique
    private static CompletableFuture<Void> drmfrp$earlyPending = null;

    /** Whether packSource holds copies shown early rather than packs a server handed over. */
    @Unique
    private static boolean drmfrp$earlyActive = false;

    /** Whether the packs currently in packSource came from the startup preload. */
    @Unique
    private static boolean drmfrp$preloaded = false;

    /** Packs to remember for next launch once -- and only if -- they load. */
    @Unique
    private static List<PackReloadConfig.IdAndPath> drmfrp$toSave = null;

    @Shadow
    private List<Pack> loadRequestedPacks(List<PackReloadConfig.IdAndPath> packs) {
        throw new AssertionError();
    }

    @Shadow
    private static RepositorySource configureSource(List<Pack> packs) {
        throw new AssertionError();
    }

    /**
     * Hands the last server's packs to the startup load.
     *
     * The constructor runs before the pack repository is built and before the
     * first reload, and at its end every field loadRequestedPacks reads is set,
     * so setting packSource here puts the packs into that first load. They are
     * marked required, so the options' pack list cannot leave them out.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void drmfrp$preloadLastPacks(Minecraft minecraft, Path directory, GameConfig.UserData user, CallbackInfo ci) {
        PackCache.loadConfig();
        try {
            List<PackReloadConfig.IdAndPath> last = LastPacks.load(drmfrp$lastPacksFile());
            if (last.isEmpty()) {
                return;
            }
            PackCache.rememberHashes(last);
            this.packSource = configureSource(loadRequestedPacks(last));
            PackFingerprint.remember(PackFingerprint.of(last));
            drmfrp$preloaded = true;
            LOGGER.info("[drmfrp] loading {} pack(s) from the last server at startup", last.size());
        } catch (Exception e) {
            // A startup that fails to preload is only a startup without the
            // shortcut; it must never be a startup that fails.
            LOGGER.warn("[drmfrp] could not preload the last server's packs", e);
        }
    }

    @Shadow
    private void startReload(PackReloadConfig.Callbacks callbacks) {
        throw new AssertionError();
    }

    @Override
    public boolean drmfrp$applyEarly(List<PackReloadConfig.IdAndPath> packs) {
        if (this.pendingReload != null) {
            return false;
        }
        List<String> fingerprints = PackFingerprint.of(packs);
        if (!PackFingerprint.allHashed(fingerprints) || fingerprints.equals(PackFingerprint.applied())) {
            return false;
        }
        List<Pack> loaded = loadRequestedPacks(packs);
        if (loaded == null) {
            return false;
        }
        PackFingerprint.remember(fingerprints);
        PackCache.rememberHashes(packs);
        PackCache.markServerReload();
        this.packSource = configureSource(loaded);
        drmfrp$preloaded = false;
        drmfrp$earlyActive = true;
        LOGGER.info("[drmfrp] showing {} pack(s) from disk while the server's copy downloads", packs.size());
        CompletableFuture<Void> done = new CompletableFuture<>();
        drmfrp$early = done;
        drmfrp$earlyPending = done;
        this.minecraft.reloadResourcePacks().thenRun(() -> done.complete(null));
        return true;
    }

    @Inject(method = "startReload", at = @At("HEAD"), cancellable = true)
    private void drmfrp$skipUnchangedReload(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
        for (PackReloadConfig.IdAndPath p : callbacks.packsToLoad()) {
            String url = EarlyApply.urlOf(this.manager, p.id());
            if (url != null && !url.startsWith("file:")) {
                EarlyApply.rememberUrl(url, p.path());
            }
        }
        CompletableFuture<Void> early = drmfrp$early;
        drmfrp$early = null;
        boolean wasEarly = drmfrp$earlyActive;
        drmfrp$earlyActive = false;

        List<String> incoming;
        try {
            incoming = PackFingerprint.of(callbacks.packsToLoad());
        } catch (Exception e) {
            // Never let this mod be the reason a pack fails to load.
            LOGGER.warn("[drmfrp] could not read the pack list, letting vanilla reload", e);
            return;
        }

        // After copies were shown early, an empty list means the download
        // failed, not a disconnect: the copies must go, as vanilla would have
        // shown nothing.
        boolean earlyFailed = wasEarly && incoming.isEmpty();
        if (!earlyFailed && PackFingerprint.canSkip(incoming)) {
            LOGGER.info("[drmfrp] same {} pack(s) already applied, skipping reload", PackFingerprint.applied().size());
            ci.cancel();
            if (early != null && !early.isDone()) {
                // The copies shown early are still loading. The server hears
                // "loaded" only once they are, and a failure there falls back
                // to the ordinary reload of what was downloaded.
                early.whenComplete((v, e) -> this.minecraft.execute(() -> {
                    if (e == null) {
                        drmfrp$finishEarly(callbacks, wasEarly);
                    } else {
                        this.startReload(callbacks);
                    }
                }));
                return;
            }
            // The caller waits on this before telling the server the pack is
            // accepted, so it has to be answered even when nothing was done.
            drmfrp$finishEarly(callbacks, wasEarly);
            return;
        }

        LOGGER.info("[drmfrp] pack set changed ({} -> {}), reloading", PackFingerprint.applied().size(), incoming.size());
        PackCache.rememberHashes(callbacks.packsToLoad());
        PackCache.markServerReload();
        PackFingerprint.remember(incoming);
        drmfrp$preloaded = false;
        drmfrp$toSave = !incoming.isEmpty() && PackFingerprint.allHashed(incoming)
                ? List.copyOf(callbacks.packsToLoad()) : null;
    }

    /** Answers the server; the packs shown early also become the next launch's preload. */
    @Unique
    private void drmfrp$finishEarly(PackReloadConfig.Callbacks callbacks, boolean wasEarly) {
        if (wasEarly) {
            try {
                LastPacks.save(drmfrp$lastPacksFile(), callbacks.packsToLoad());
            } catch (Exception e) {
                LOGGER.warn("[drmfrp] could not remember these packs for next launch", e);
            }
        }
        callbacks.onSuccess();
    }

    /**
     * Saved only after the reload has actually succeeded. Saving at the start
     * meant a pack that failed to load was preloaded again on the next launch,
     * and failed again, every launch.
     */
    @Inject(method = "onReloadSuccess", at = @At("HEAD"))
    private void drmfrp$saveAfterSuccess(CallbackInfo ci) {
        List<PackReloadConfig.IdAndPath> packs = drmfrp$toSave;
        drmfrp$toSave = null;
        if (pendingReload == null || packs == null) {
            return;
        }
        try {
            LastPacks.save(drmfrp$lastPacksFile(), packs);
        } catch (Exception e) {
            LOGGER.warn("[drmfrp] could not remember these packs for next launch", e);
        }
    }

    /**
     * A reload failed. Vanilla's recovery only undoes reloads a server asked
     * for; the startup load does not go through startReload, so pendingReload
     * is null there and a preloaded pack that broke it would stay in
     * packSource, fail the retry too, and do the same on every launch. So the
     * preload is dropped along with the file that would bring it back.
     */
    @Inject(method = {"onRecovery", "onRecoveryFailure"}, at = @At("HEAD"))
    private void drmfrp$forgetAfterFailure(CallbackInfo ci) {
        drmfrp$toSave = null;
        PackCache.clear();
        PackFingerprint.forget();
        CompletableFuture<Void> pending = drmfrp$earlyPending;
        if (pending != null && !pending.isDone()) {
            pending.completeExceptionally(new IllegalStateException("the packs shown early failed to load"));
        }
        if (pendingReload == null && drmfrp$earlyActive) {
            // The copies shown early broke the reload; the download will bring the real ones.
            drmfrp$earlyActive = false;
            this.packSource = configureSource(List.of());
            LOGGER.warn("[drmfrp] the packs shown early failed to load; dropped them");
        }
        if (pendingReload == null && drmfrp$preloaded) {
            drmfrp$preloaded = false;
            this.packSource = configureSource(List.of());
            try {
                Files.deleteIfExists(drmfrp$lastPacksFile());
            } catch (Exception ignored) {
                // Nothing more to do; the next successful join rewrites it.
            }
            LOGGER.warn("[drmfrp] the last server's packs failed to load at startup; dropped them");
        }
    }
}
