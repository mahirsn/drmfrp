package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.PackCache;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Answers an atlas's sprite loading from the cache. Everything waiting on the
 * atlas (models, particles) then gets the same sprites the cached atlas holds.
 */
@Mixin(targets = "net.minecraft.client.resources.model.sprite.AtlasManager$AtlasEntry")
public class AtlasEntryMixin {

    @Shadow
    @Final
    private TextureAtlas atlas;

    @Inject(method = "scheduleLoad", at = @At("HEAD"), cancellable = true)
    private void drmfrp$cached(ResourceManager resourceManager, Executor executor, int maxMipmapLevels,
                               CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        SpriteLoader.Preparations cached = PackCache.cachedPreparations(this.atlas.location());
        if (cached != null) {
            cir.setReturnValue(CompletableFuture.completedFuture(cached));
        }
    }
}
