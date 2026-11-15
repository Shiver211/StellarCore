package github.kasuminova.stellarcore.common.pool;

import github.kasuminova.stellarcore.common.mod.Mods;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class LowerCaseStringPoolTest {

    private static final Mods[] OPTIONAL_POOLS = {Mods.CENSORED_ASM, Mods.FERMIUM_OR_BLAHAJ_ASM};
    private static final boolean[][] ORIGINAL_FLAGS = new boolean[2][2];

    @BeforeClass
    public static void isolateOptionalModIntegrations() throws Exception {
        // 单元测试只验证星核自己的字符串池，不启动可选模组的加载器。
        Field initialized = Mods.class.getDeclaredField("initialized");
        Field loaded = Mods.class.getDeclaredField("loaded");
        initialized.setAccessible(true);
        loaded.setAccessible(true);
        for (int i = 0; i < OPTIONAL_POOLS.length; i++) {
            ORIGINAL_FLAGS[i][0] = initialized.getBoolean(OPTIONAL_POOLS[i]);
            ORIGINAL_FLAGS[i][1] = loaded.getBoolean(OPTIONAL_POOLS[i]);
            initialized.setBoolean(OPTIONAL_POOLS[i], true);
            loaded.setBoolean(OPTIONAL_POOLS[i], false);
        }
    }

    @AfterClass
    public static void restoreOptionalModIntegrations() throws Exception {
        LowerCaseStringPool.INSTANCE.getWorker().stop();
        Field initialized = Mods.class.getDeclaredField("initialized");
        Field loaded = Mods.class.getDeclaredField("loaded");
        initialized.setAccessible(true);
        loaded.setAccessible(true);
        for (int i = 0; i < OPTIONAL_POOLS.length; i++) {
            initialized.setBoolean(OPTIONAL_POOLS[i], ORIGINAL_FLAGS[i][0]);
            loaded.setBoolean(OPTIONAL_POOLS[i], ORIGINAL_FLAGS[i][1]);
        }
    }

    @Test
    public void concurrentLookupsShareTheSameStringAndCountEveryCall() throws Exception {
        LowerCaseStringPool pool = LowerCaseStringPool.INSTANCE;
        pool.clear();
        int threads = 8;
        int iterations = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    String first = null;
                    for (int j = 0; j < iterations; j++) {
                        String value = pool.canonicalize(new String("MINECRAFT:BLOCK/STONE"));
                        if (first == null) {
                            first = value;
                        } else {
                            assertSame(first, value);
                        }
                    }
                    return first;
                }));
            }
            start.countDown();
            String expected = results.get(0).get(10, TimeUnit.SECONDS);
            assertEquals("minecraft:block/stone", expected);
            for (Future<String> result : results) {
                assertSame(expected, result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, pool.getUniqueCount());
            assertEquals((long) threads * iterations, pool.getProcessedCount());
            pool.clear();
            assertEquals(0, pool.getUniqueCount());
            assertEquals(0, pool.getProcessedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void normalizationDoesNotDependOnTheOperatingSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("i:block", LowerCaseStringPool.INSTANCE.canonicalize("I:BLOCK"));
            assertNull(LowerCaseStringPool.INSTANCE.canonicalize(null));
        } finally {
            Locale.setDefault(original);
        }
    }
}
