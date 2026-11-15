package github.kasuminova.stellarcore.mixin.base;

import github.kasuminova.stellarcore.client.resource.DirectoryResourceIndex;
import net.minecraft.client.resources.AbstractResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

@Pseudo
@Mixin(targets = "com.teamacronymcoders.base.util.files.DirectoryResourcePack", remap = false)
public abstract class MixinDirectoryResourcePack extends AbstractResourcePack {
    protected MixinDirectoryResourcePack(File root) { super(root); }

    @Inject(method = {"hasResourceName", "func_110593_b"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void stellar_core$hasResourceName(String name, CallbackInfoReturnable<Boolean> cir) {
        // 与 BASE 原 getFile 完全相同：替换全部 assets/，查询也允许目录。
        Boolean result = DirectoryResourceIndex.query(resourcePackFile, name.replace("assets/", ""), false);
        if (result != null) cir.setReturnValue(result);
    }
}
