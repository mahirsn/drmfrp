package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.PackCache;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reuses the baked models of a kept pack set. The preparation is skipped; the
 * apply step runs as usual, so whatever hooks it still sees it.
 */
@Mixin(ModelManager.class)
public class ModelManagerMixin {

    private static Method drmfrp$apply;

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void drmfrp$cached(PreparableReloadListener.SharedState currentReload, Executor taskExecutor,
                               PreparableReloadListener.PreparationBarrier preparationBarrier, Executor reloadExecutor,
                               CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        Object cached = PackCache.cachedModels();
        if (cached == null) {
            return;
        }
        ModelManager self = (ModelManager) (Object) this;
        cir.setReturnValue(preparationBarrier.wait(cached).thenAcceptAsync(state -> {
            try {
                if (drmfrp$apply == null) {
                    Method m = ModelManager.class.getDeclaredMethod("apply", state.getClass());
                    m.setAccessible(true);
                    drmfrp$apply = m;
                }
                drmfrp$apply.invoke(self, state);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("drmfrp could not apply kept models", e);
            }
        }, reloadExecutor));
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void drmfrp$remember(@Coerce Object preparations, CallbackInfo ci) {
        PackCache.modelsApplied(preparations);
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void drmfrp$done(@Coerce Object preparations, CallbackInfo ci) {
        PackCache.afterModels();
    }
}
