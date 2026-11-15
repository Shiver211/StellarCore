package github.kasuminova.stellarcore.client.pool;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class QuadPoolLifecycleTest {

    @Test
    public void poolsDeduplicateDuringLoadThenStayEmptyAfterFreeze() throws Exception {
        StellarUnpackedDataPool.VertexDataPool vertices = new StellarUnpackedDataPool.VertexDataPool();
        StellarUnpackedDataPool.UnpackedDataPoolLevel1 unpacked = new StellarUnpackedDataPool.UnpackedDataPoolLevel1();
        try {
            int[] originalVertices = {1, 2, 3};
            assertSame(originalVertices, vertices.canonicalize(originalVertices));
            assertSame(originalVertices, vertices.canonicalize(originalVertices.clone()));
            float[][][] originalUnpacked = {{{1.0F, 2.0F}}};
            assertSame(originalUnpacked, unpacked.canonicalize(originalUnpacked));
            assertSame(originalUnpacked, unpacked.canonicalize(new float[][][]{{{1.0F, 2.0F}}}));

            // 模拟启动时的大池，验证 clear 后不会继续保留大容量表。
            for (int i = 0; i < 4096; i++) {
                vertices.canonicalize(new int[]{i});
                unpacked.canonicalize(new float[][][]{{{i}}});
            }
            QuadPoolLifecycle.freeze();
            vertices.clearPool();
            unpacked.clearPool();
            assertEquals(0, vertices.getUniqueCount());
            assertEquals(0, unpacked.getUniqueCount());
            assertTrue(capacity(vertices.getPoolKeySet()) <= 32);
            assertTrue(capacity(unpacked.getPoolKeySet()) <= 32);

            int[] runtimeVertices = {4, 5, 6};
            float[][][] runtimeUnpacked = {{{3.0F}}};
            assertSame(runtimeVertices, vertices.canonicalize(runtimeVertices));
            assertSame(runtimeUnpacked, unpacked.canonicalize(runtimeUnpacked));

            // 即使旧队列还有任务，执行时也不能重新填充已冻结的池。
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<int[]> returned = new AtomicReference<>();
            vertices.canonicalizeAsync(runtimeVertices, value -> {
                returned.set(value);
                completed.countDown();
            });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertSame(runtimeVertices, returned.get());
            assertEquals(0, vertices.getUniqueCount());
            assertEquals(0, unpacked.getUniqueCount());
        } finally {
            vertices.getWorker().stop();
            unpacked.getWorker().stop();
        }
    }

    private static int capacity(Object pool) throws Exception {
        Field field = ObjectOpenCustomHashSet.class.getDeclaredField("n");
        field.setAccessible(true);
        return field.getInt(pool);
    }
}
