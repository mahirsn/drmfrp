package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.EarlyApply;
import net.minecraft.client.resources.server.ServerPackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pack arrived or the player accepted the server's packs; see whether a copy can be shown now. */
@Mixin(ServerPackManager.class)
public class ServerPackManagerMixin {

    @Inject(method = {"pushPack", "allowServerPacks"}, at = @At("RETURN"))
    private void drmfrp$schedule(CallbackInfo ci) {
        EarlyApply.schedule(this);
    }
}
