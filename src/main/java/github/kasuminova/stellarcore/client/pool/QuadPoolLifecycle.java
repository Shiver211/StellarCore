package github.kasuminova.stellarcore.client.pool;

import github.kasuminova.stellarcore.common.util.StellarLog;

/** 加载完成后冻结全局模型池，避免运行期生成的网格重新成为全局强引用。 */
public final class QuadPoolLifecycle {

    private static volatile boolean frozen = false;

    private QuadPoolLifecycle() {
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static void freeze() {
        if (!frozen) {
            frozen = true;
            StellarLog.LOG.info("[StellarCore] Quad pools frozen after loading.");
        }
    }
}
