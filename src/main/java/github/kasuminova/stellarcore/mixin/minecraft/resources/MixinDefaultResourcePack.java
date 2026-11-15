package github.kasuminova.stellarcore.mixin.minecraft.resources;

import com.llamalad7.mixinextras.sugar.Local;
import github.kasuminova.stellarcore.client.resource.ClasspathAssetIndex;
import github.kasuminova.stellarcore.common.util.StellarLog;
import github.kasuminova.stellarcore.mixin.util.StellarCoreResourcePack;
import github.kasuminova.stellarcore.shaded.org.jctools.maps.NonBlockingHashMap;
import net.minecraft.client.resources.DefaultResourcePack;
import net.minecraft.client.resources.ResourceIndex;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

@Mixin(DefaultResourcePack.class)
public abstract class MixinDefaultResourcePack implements StellarCoreResourcePack {

    @Shadow
    @Nullable
    protected abstract InputStream getResourceStream(final ResourceLocation location);

    @Final
    @Shadow
    private ResourceIndex resourceIndex;

    @Unique
    private final Map<ResourceLocation, Boolean> stellar_core$resourceExistsCache = new NonBlockingHashMap<>();

    @Unique
    private volatile boolean stellar_core$cacheEnabled = false;

    @Unique
    private volatile ClasspathAssetIndex stellar_core$assetIndex;

    /**
     * @author Kasumi_Nova
     * @reason Cache
     */
    @Inject(method = "resourceExists", at = @At("HEAD"), cancellable = true)
    public void resourceExists(final ResourceLocation location, final CallbackInfoReturnable<Boolean> cir) {
        if (stellar_core$cacheEnabled && location.getClass() == ResourceLocation.class) {
            cir.setReturnValue(stellar_core$resourceExistsCache.computeIfAbsent(location, this::stellar_core$probeResource));
        } else {
            // 有些模组的 ResourceLocation 子类会覆盖 getPath，不能复用基类的相等性缓存。
            cir.setReturnValue(stellar_core$probeResource(location));
        }
    }

    @Unique
    private boolean stellar_core$probeResource(final ResourceLocation location) {
        InputStream stream = this.getResourceStream(location);
        if (stream == null) {
            return this.resourceIndex.isFileExisting(location);
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
        return true;
    }

    // 仅拦截实际的类路径查找；OptiFine 在此前提供的资源流和原路径校验照常执行。
    @Redirect(method = "getResourceStream", at = @At(value = "INVOKE",
            target = "Ljava/lang/Class;getResource(Ljava/lang/String;)Ljava/net/URL;", remap = false), require = 1)
    private URL stellar_core$findClasspathResource(final Class<?> owner, final String path) {
        ClasspathAssetIndex index = stellar_core$assetIndex;
        return index != null && owner == DefaultResourcePack.class && index.definitelyMissing(path)
                ? null : owner.getResource(path);
    }

    // ordinal 0 是路径校验成功后的开流；catch 中的原回退不变。
    @Redirect(method = "getResourceStream", at = @At(value = "INVOKE",
            target = "Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;",
            ordinal = 0, remap = false), require = 1)
    private InputStream stellar_core$openLocatedResource(final Class<?> owner, final String path,
                                                        @Local final URL url) {
        try {
            return url.openStream();
        } catch (IOException ignored) {
            // Class.getResourceAsStream 打开失败时返回 null，不进入路径校验的回退分支。
            return null;
        }
    }

    @Unique
    @Override
    public void stellar_core$onReload() {
        stellar_core$resourceExistsCache.clear();
        stellar_core$closeAssetIndex();
    }

    @Override
    public void stellar_core$enableCache() {
        if (stellar_core$assetIndex == null) {
            long start = System.nanoTime();
            ClasspathAssetIndex index = ClasspathAssetIndex.create(DefaultResourcePack.class.getClassLoader());
            stellar_core$assetIndex = index;
            StellarLog.LOG.info("[StellarCore-ClasspathAssetIndex] Indexed {} assets in {} jars in {}ms; complete={}; fallbackSources={}.",
                    index.getAssetCount(), index.getJarCount(), (System.nanoTime() - start) / 1_000_000,
                    index.isComplete(), index.getFallbackSources());
            for (String reason : index.getIncompleteReasons()) {
                StellarLog.LOG.warn("[StellarCore-ClasspathAssetIndex] Disabled negative lookup optimization: {}", reason);
            }
        }
        stellar_core$cacheEnabled = true;
    }

    @Override
    public void stellar_core$disableCache() {
        stellar_core$cacheEnabled = false;
        stellar_core$closeAssetIndex();
        stellar_core$resourceExistsCache.clear();
    }

    @Unique
    private void stellar_core$closeAssetIndex() {
        ClasspathAssetIndex index = stellar_core$assetIndex;
        stellar_core$assetIndex = null;
        if (index != null) {
            try {
                index.close();
            } catch (IOException e) {
                StellarLog.LOG.warn("[StellarCore-ClasspathAssetIndex] Failed to close fallback loader.", e);
            }
        }
    }

}
