package github.kasuminova.stellarcore.client.resource;

import github.kasuminova.stellarcore.mixin.minecraft.resources.MixinDefaultResourcePack;
import net.minecraft.client.resources.ResourceIndex;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

import static org.junit.Assert.*;

public class DefaultResourceProbeTest {

    @Test
    public void locatedUrlIsOpenedDirectlyAndOpenFailureReturnsNull() throws Exception {
        Method open = MixinDefaultResourcePack.class.getDeclaredMethod("stellar_core$openLocatedResource",
                Class.class, String.class, URL.class);
        open.setAccessible(true);
        final int[] opened = {0};
        URL url = new URL(null, "probe:/resource", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL source) {
                return new URLConnection(source) {
                    @Override public void connect() {}
                    @Override public InputStream getInputStream() throws IOException {
                        if (++opened[0] == 2) throw new IOException("模拟打开失败");
                        return new ByteArrayInputStream(new byte[]{42});
                    }
                };
            }
        });
        // 名称故意不存在：必须使用传入的 URL，不能重新按名称查找。
        try (InputStream stream = (InputStream) open.invoke(new Probe(), getClass(), "/absent", url)) {
            assertNotNull(stream);
            assertEquals(42, stream.read());
        }
        assertNull(open.invoke(new Probe(), getClass(), "/absent", url));
        assertEquals(2, opened[0]);
    }

    @Test
    public void existenceProbeClosesTheStreamEvenWhenCachingIsDisabled() {
        Probe probe = new Probe();
        assertTrue(query(probe, new ResourceLocation("test", "present.png")));
        assertEquals(1, probe.opened);
        assertEquals(1, probe.closed);
    }

    @Test
    public void missingClasspathResourceStillChecksTheVanillaAssetIndex() throws Exception {
        Probe probe = new Probe();
        probe.present = false;
        Field index = MixinDefaultResourcePack.class.getDeclaredField("resourceIndex");
        index.setAccessible(true);
        index.set(probe, new ResourceIndex() {
            @Override
            public boolean isFileExisting(ResourceLocation location) {
                return location.getPath().equals("indexed.ogg");
            }
        });
        assertTrue(query(probe, new ResourceLocation("test", "indexed.ogg")));
        assertFalse(query(probe, new ResourceLocation("test", "absent.ogg")));
    }

    @Test
    public void reloadDropsCachedResultsAndDynamicPathsBypassTheCache() throws Exception {
        Probe probe = new Probe();
        Field enabled = MixinDefaultResourcePack.class.getDeclaredField("stellar_core$cacheEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(probe, true);
        ResourceLocation location = new ResourceLocation("test", "present.png");
        query(probe, location);
        query(probe, location);
        assertEquals(1, probe.opened);
        probe.stellar_core$onReload();
        query(probe, location);
        assertEquals(2, probe.opened);
        ResourceLocation dynamic = new ResourceLocation("test", "present.png") {
            @Override
            public String getPath() {
                return "dynamic.png";
            }
        };
        query(probe, dynamic);
        query(probe, dynamic);
        assertEquals(4, probe.opened);
        assertEquals(probe.opened, probe.closed);
    }

    private static boolean query(Probe probe, ResourceLocation location) {
        CallbackInfoReturnable<Boolean> result = new CallbackInfoReturnable<>("resourceExists", true);
        probe.resourceExists(location, result);
        return result.getReturnValueZ();
    }

    private static class Probe extends MixinDefaultResourcePack {
        private int opened;
        private int closed;
        private boolean present = true;

        @Override
        protected InputStream getResourceStream(ResourceLocation location) {
            opened++;
            if (!present) {
                return null;
            }
            return new ByteArrayInputStream(new byte[]{1}) {
                @Override
                public void close() throws IOException {
                    closed++;
                    super.close();
                }
            };
        }
    }
}
