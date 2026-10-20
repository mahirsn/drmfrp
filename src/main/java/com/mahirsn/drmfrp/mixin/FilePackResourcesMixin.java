package com.mahirsn.drmfrp.mixin;

import com.mahirsn.drmfrp.ZipIndex;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.zip.ZipFile;

/** Zip listings from {@link ZipIndex}; same entries, same order, same warnings as vanilla. */
@Mixin(FilePackResources.class)
public class FilePackResourcesMixin {

    private static final Logger drmfrp$LOGGER = LoggerFactory.getLogger(FilePackResources.class);

    @Shadow
    @Final
    private String prefix;

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void drmfrp$indexed(PackType type, String namespace, String directory, PackResources.ResourceOutput output, CallbackInfo ci) {
        if (!ZipIndex.enabled()) {
            return;
        }
        ZipFile zip = ZipIndex.zipOf(this);
        if (zip == null) {
            return;
        }
        String typeRoot = type.getDirectory() + "/" + namespace + "/";
        String root = this.prefix.isEmpty() ? typeRoot : this.prefix + "/" + typeRoot;
        ZipIndex.forEach(zip, root + directory + "/", (name, entry) -> {
            String path = name.substring(root.length());
            Identifier id = Identifier.tryBuild(namespace, path);
            if (id != null) {
                output.accept(id, IoSupplier.create(zip, entry));
            } else {
                drmfrp$LOGGER.warn("Invalid path in datapack: {}:{}, ignoring", namespace, path);
            }
        });
        ci.cancel();
    }
}
