package github.kasuminova.stellarcore.client.hudcaching;

/** 仅由客户端渲染线程在 IGI FBO 刷新期间使用。 */
public final class IGIFramebufferContext {

    public static boolean renderingItems;

    private IGIFramebufferContext() {
    }
}
