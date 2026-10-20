package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.PackCache;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the outgoing contents instead of freeing them, and puts kept contents back. */
@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void drmfrp$swap(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (PackCache.beforeUpload((TextureAtlas) (Object) this, preparations)) {
            ci.cancel();
        }
    }
}
