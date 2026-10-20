package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.PackCache;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Marks where a reload begins and where all atlases have their new contents. */
@Mixin(AtlasManager.class)
public class AtlasManagerMixin {

    @Shadow
    private int maxMipmapLevels;

    @Shadow
    @Final
    private Map<Identifier, ?> atlasById;

    // prepareSharedState runs for every reload, on the main thread, before any listener starts.
    @Inject(method = "prepareSharedState", at = @At("HEAD"))
    private void drmfrp$begin(PreparableReloadListener.SharedState currentReload, CallbackInfo ci) {
        PackCache.beginReload(currentReload.resourceManager(), this.maxMipmapLevels, this.atlasById.size());
    }

    @Inject(method = "updateSpriteMaps", at = @At("RETURN"))
    private void drmfrp$atlasesDone(CallbackInfo ci) {
        PackCache.atlasesDone();
    }
}
