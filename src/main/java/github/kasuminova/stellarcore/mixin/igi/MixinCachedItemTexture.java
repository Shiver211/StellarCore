package github.kasuminova.stellarcore.mixin.igi;

import github.kasuminova.stellarcore.client.hudcaching.IGIFramebufferContext;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.github.lunatrius.ingameinfo.client.gui.overlay.InfoItem$CachedItemTexture", remap = false)
public abstract class MixinCachedItemTexture {

    @Unique
    private int stellar_core$previousFramebuffer;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 1)
    private void stellar_core$rememberFramebuffer(CallbackInfo ci) {
        if (IGIFramebufferContext.renderingItems) {
            stellar_core$previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        }
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V",
                    ordinal = 1),
            remap = true, require = 1)
    private void stellar_core$restoreFramebuffer(Framebuffer minecraftFramebuffer, boolean viewport) {
        if (IGIFramebufferContext.renderingItems) {
            // 生成 16×16 图标纹理后回到原 IGI FBO，后面的 glPopAttrib 会恢复 viewport。
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, stellar_core$previousFramebuffer);
        } else {
            minecraftFramebuffer.bindFramebuffer(viewport);
        }
    }
}
