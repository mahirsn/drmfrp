package com.mahirsn.drmfrp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** The settings of config/drmfrp.properties, built from vanilla's own option widgets. */
public class ConfigScreen extends OptionsSubScreen {

    private OptionInstance<Boolean> enabled;
    private OptionInstance<Boolean> preload;
    private OptionInstance<Boolean> zipIndex;
    private OptionInstance<Boolean> packCache;
    private OptionInstance<Integer> packCacheSize;
    private OptionInstance<Boolean> earlyApply;

    public ConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("drmfrp.options.title"));
    }

    private static OptionInstance<Boolean> toggle(String key, boolean value) {
        return OptionInstance.createBoolean("drmfrp.options." + key,
                OptionInstance.cachedConstantTooltip(Component.translatable("drmfrp.options." + key + ".tooltip")), value);
    }

    @Override
    protected void addOptions() {
        Config.load();
        enabled = toggle("enabled", Config.enabled);
        preload = toggle("preloadLastServerPacks", Config.preloadLastServerPacks);
        zipIndex = toggle("zipIndex", Config.zipIndex);
        packCache = toggle("packCache", Config.packCache);
        packCacheSize = new OptionInstance<>("drmfrp.options.packCacheSize",
                OptionInstance.cachedConstantTooltip(Component.translatable("drmfrp.options.packCacheSize.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(1, 10), Config.packCacheSize, value -> {
                });
        earlyApply = toggle("earlyApply", Config.earlyApply);
        this.list.addBig(enabled);
        this.list.addSmall(preload, zipIndex, packCache, packCacheSize, earlyApply);
    }

    @Override
    public void removed() {
        super.removed();
        Config.enabled = enabled.get();
        Config.preloadLastServerPacks = preload.get();
        Config.zipIndex = zipIndex.get();
        Config.packCache = packCache.get();
        Config.packCacheSize = packCacheSize.get();
        Config.earlyApply = earlyApply.get();
        Config.save();
        // Takes effect now: kept packs beyond the new limit, or all of them when turned off, are freed.
        PackCache.trim();
    }
}
