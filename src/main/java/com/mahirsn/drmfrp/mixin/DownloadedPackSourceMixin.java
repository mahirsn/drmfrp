package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.PackFingerprint;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        PackFingerprint.remember(incoming);
    }
}
