package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.LastPacks;
import com.mahirsn.drmfrp.PackCache;
import com.mahirsn.drmfrp.PackFingerprint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
public class DownloadedPackSourceMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("drmfrp");

    private static Path drmfrp$lastPacksFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("drmfrp-last-packs.txt");
    }

    @Shadow
    private RepositorySource packSource;

    @Shadow
    private PackReloadConfig.Callbacks pendingReload;

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

    @Inject(method = "startReload", at = @At("HEAD"), cancellable = true)
    private void drmfrp$skipUnchangedReload(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
        List<String> incoming;
        try {
            incoming = PackFingerprint.of(callbacks.packsToLoad());
        } catch (Exception e) {
            // Never let this mod be the reason a pack fails to load.
            LOGGER.warn("[drmfrp] could not read the pack list, letting vanilla reload", e);
            return;
        }

        if (PackFingerprint.canSkip(incoming)) {
            LOGGER.info("[drmfrp] same {} pack(s) already applied, skipping reload", PackFingerprint.applied().size());
            // The caller waits on this before telling the server the pack is
            // accepted, so it has to be answered even when nothing was done.
            callbacks.onSuccess();
            ci.cancel();
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
