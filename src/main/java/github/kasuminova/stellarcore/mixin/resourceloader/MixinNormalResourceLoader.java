package github.kasuminova.stellarcore.mixin.resourceloader;

import github.kasuminova.stellarcore.client.resource.DirectoryResourceIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

@Pseudo
@Mixin(targets = "lumien.resourceloader.loader.NormalResourceLoader", remap = false)
public abstract class MixinNormalResourceLoader {
    @Shadow boolean debug;

    @Inject(method = {"resourceExists", "func_110589_b"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void stellar_core$resourceExists(ResourceLocation location, CallbackInfoReturnable<Boolean> cir) {
        // 调试模式继续执行原日志；自定义 ResourceLocation 也保留原调用语义。
        if (debug || location.getClass() != ResourceLocation.class) return;
        Boolean result = DirectoryResourceIndex.query(new File(Minecraft.getMinecraft().gameDir, "resources"),
                location.getNamespace() + "/" + location.getPath(), true);
        if (result != null) cir.setReturnValue(result);
    }
}
