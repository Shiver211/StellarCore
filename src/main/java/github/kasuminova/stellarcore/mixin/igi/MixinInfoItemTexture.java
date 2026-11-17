package github.kasuminova.stellarcore.mixin.igi;

import github.kasuminova.stellarcore.client.hudcaching.IGIFramebufferContext;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.github.lunatrius.ingameinfo.client.gui.overlay.InfoItem", remap = false)
public abstract class MixinInfoItemTexture {

    // 原版 IGI 没有纹理缓存方法，这个适配仅用于 CanoeEdition。
    @Redirect(method = "drawCachedTexture",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;tryBlendFuncSeparate(IIII)V"),
            remap = true, require = 0)
    private void stellar_core$accumulateFramebufferAlpha(int src, int dst, int srcAlpha, int dstAlpha) {
        // 沿用 IGI 的颜色混合；透明 FBO 还须保留下层文字和图标的 alpha。
        GlStateManager.tryBlendFuncSeparate(src, dst, srcAlpha,
                IGIFramebufferContext.renderingItems ? GL11.GL_ONE_MINUS_SRC_ALPHA : dstAlpha);
    }
}
