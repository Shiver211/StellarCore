package github.kasuminova.stellarcore.client.resource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

public class ClasspathAssetIndexTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void indexesAllNamespacesEvenWithoutExplicitDirectoryEntries() throws Exception {
        File jar = jar("resources.jar", null, "assets/minecraft/textures/stone.png", "assets/example/models/block.json");
        try (URLClassLoader loader = loader(jar)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertTrue(index.isComplete());
            assertEquals(2, index.getAssetCount());
            assertSameLookup(index, loader, "assets/minecraft/textures/stone.png");
            assertSameLookup(index, loader, "assets/example/models/block.json");
            assertSameLookup(index, loader, "assets/example/textures/missing.png");
        }
    }

    @Test
    public void directoriesStayLiveAndKeepPathCaseSemantics() throws Exception {
        File root = temporary.newFolder("classes");
        File asset = new File(root, "assets/example/ExactCase.txt");
        Files.createDirectories(asset.toPath().getParent());
        try (URLClassLoader loader = loader(root)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertTrue(index.definitelyMissing("/assets/example/ExactCase.txt"));
            Files.write(asset.toPath(), new byte[]{1});
            assertFalse(index.definitelyMissing("/assets/example/ExactCase.txt"));
            // Windows 上大小写不匹配最多回退，不应由索引伪造存在结果。
            if (loader.getResource("assets/example/exactcase.txt") != null) {
                assertFalse(index.definitelyMissing("/assets/example/exactcase.txt"));
            }
            Files.delete(asset.toPath());
            assertTrue(index.definitelyMissing("/assets/example/ExactCase.txt"));
        }
    }

    @Test
    public void includesParentLoaderResources() throws Exception {
        File parentJar = jar("parent.jar", null, "assets/parent/item.png");
        File childJar = jar("child.jar", null, "assets/child/item.png");
        try (URLClassLoader parent = loader(parentJar);
             URLClassLoader child = new URLClassLoader(new URL[]{childJar.toURI().toURL()}, parent)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(child);
            assertTrue(index.isComplete());
            assertSameLookup(index, child, "assets/parent/item.png");
            assertSameLookup(index, child, "assets/child/item.png");
            assertSameLookup(index, child, "assets/unknown/item.png");
        }
    }

    @Test
    public void followsManifestClassPathAndStopsCycles() throws Exception {
        Manifest first = manifest();
        first.getMainAttributes().put(Attributes.Name.CLASS_PATH, "second%20file.jar absent-library.jar");
        Manifest second = manifest();
        second.getMainAttributes().put(Attributes.Name.CLASS_PATH, "first.jar");
        File firstJar = jar("first.jar", first, "assets/first/item.png");
        jar("second file.jar", second, "assets/second/item.png");
        try (URLClassLoader loader = loader(firstJar)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertTrue(index.isComplete());
            assertEquals(2, index.getJarCount());
            assertSameLookup(index, loader, "assets/second/item.png");
            assertSameLookup(index, loader, "assets/missing/item.png");
        }
    }

    @Test
    public void incompleteOrCustomSourcesNeverSuppressLookup() throws Exception {
        File invalid = temporary.newFile("invalid.jar");
        try (URLClassLoader loader = loader(invalid)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertFalse(index.isComplete());
            assertFalse(index.definitelyMissing("/assets/virtual/item.png"));
            assertTrue(index.getIncompleteReasons().get(0).contains("invalid.jar"));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null) {
            @Override
            public URL getResource(String name) {
                return super.getResource(name);
            }
        }) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertFalse(index.isComplete());
            assertFalse(index.definitelyMissing("/assets/virtual/item.png"));
        }
    }

    @Test
    public void virtualSourcesStayLiveWithoutDisablingJarIndex() throws Exception {
        File jar = jar("normal.jar", null, "assets/example/normal.png");
        Map<String, byte[]> virtualFiles = new HashMap<>();
        URL virtual = new URL(null, "virtual:/", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public InputStream getInputStream() throws IOException {
                        byte[] content = virtualFiles.get(url.getPath());
                        if (content == null) throw new FileNotFoundException(url.toString());
                        return new ByteArrayInputStream(content);
                    }
                };
            }
        });
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL(), virtual}, null);
             ClasspathAssetIndex index = ClasspathAssetIndex.create(loader)) {
            assertTrue(index.isComplete());
            assertEquals(1, index.getJarCount());
            assertEquals(1, index.getFallbackSources().size());
            assertSameLookup(index, loader, "assets/example/normal.png");
            assertSameLookup(index, loader, "assets/virtual/item.png");
            virtualFiles.put("/assets/virtual/item.png", new byte[]{1});
            assertSameLookup(index, loader, "assets/virtual/item.png");
            virtualFiles.clear();
            assertSameLookup(index, loader, "assets/virtual/item.png");
            index.close();
            assertFalse(index.definitelyMissing("/assets/virtual/item.png"));
        }
    }

    @Test
    public void jarUrlsPreservePrefixAndNonDirectoryLookupSemantics() throws Exception {
        File jar = jar("nested.jar", null, "inner/assets/example/item.png", "inner/Plugin.class");
        for (String suffix : new String[]{"!/inner/", "!/inner/Plugin.class"}) {
            URL source = new URL("jar:" + jar.toURI().toURL() + suffix);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{source}, null);
                 ClasspathAssetIndex index = ClasspathAssetIndex.create(loader)) {
                assertTrue(index.isComplete());
                assertEquals(1, index.getFallbackSources().size());
                assertSameLookup(index, loader, "assets/example/item.png");
                assertSameLookup(index, loader, "assets/example/missing.png");
            }
        }
    }

    @Test
    public void includesEffectiveMultiReleaseAssets() throws Exception {
        Manifest manifest = manifest();
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        File jar = jar("versions.jar", manifest, "assets/example/base.txt",
                "META-INF/versions/9/assets/example/modern.txt", "META-INF/versions/99/assets/example/future.txt");
        try (URLClassLoader loader = loader(jar)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            assertSameLookup(index, loader, "assets/example/base.txt");
            assertSameLookup(index, loader, "assets/example/modern.txt");
            assertSameLookup(index, loader, "assets/example/future.txt");
        }
    }

    @Test
    public void rebuildUsesTheUpdatedClassPath() throws Exception {
        File first = jar("initial.jar", null, "assets/example/initial.png");
        File added = jar("added.jar", null, "assets/example/added.png");
        try (MutableLoader loader = new MutableLoader(first.toURI().toURL())) {
            assertTrue(ClasspathAssetIndex.create(loader).definitelyMissing("/assets/example/added.png"));
            loader.add(added.toURI().toURL());
            ClasspathAssetIndex refreshed = ClasspathAssetIndex.create(loader);
            assertFalse(refreshed.definitelyMissing("/assets/example/added.png"));
            assertEquals(2, refreshed.getJarCount());
        }
    }

    @Test
    public void publishedIndexSupportsParallelTextureQueries() throws Exception {
        File jar = jar("parallel.jar", null, "assets/example/present.png");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try (URLClassLoader loader = loader(jar)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < 1000; i++) {
                        if (index.definitelyMissing("/assets/example/present.png")
                                || !index.definitelyMissing("/assets/example/absent_" + i + ".png")) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                assertTrue(result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void onlyAssetJarIndexesMakeTheSnapshotIncomplete() throws Exception {
        for (String prefix : new String[]{"org/example", "assets/example"}) {
            File file = new File(temporary.getRoot(), prefix.startsWith("assets") ? "asset-index.jar" : "class-index.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
                output.putNextEntry(new JarEntry("META-INF/INDEX.LIST"));
                output.write(("JarIndex-Version: 1.0\n\nother.jar\n" + prefix + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            try (URLClassLoader loader = loader(file)) {
                ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
                assertEquals(!prefix.startsWith("assets"), index.isComplete());
                assertEquals(!prefix.startsWith("assets"), index.definitelyMissing("/assets/example/item.png"));
            }
        }
    }

    @Test
    public void leavesNonCanonicalPathsToTheOriginalLoader() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            ClasspathAssetIndex index = ClasspathAssetIndex.create(loader);
            for (String path : new String[]{"/pack.png", "assets/example/a.png", "/assets/example/../a.png",
                    "/assets/example/./a.png", "/assets//example/a.png", "/assets/example\\a.png"}) {
                assertFalse(path, index.definitelyMissing(path));
            }
        }
    }

    private static void assertSameLookup(ClasspathAssetIndex index, ClassLoader loader, String path) {
        assertEquals(path, loader.getResource(path) == null, index.definitelyMissing('/' + path));
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    private File jar(String name, Manifest manifest, String... entries) throws IOException {
        File file = new File(temporary.getRoot(), name);
        try (JarOutputStream output = manifest == null ? new JarOutputStream(Files.newOutputStream(file.toPath()))
                : new JarOutputStream(Files.newOutputStream(file.toPath()), manifest)) {
            for (String entry : entries) {
                output.putNextEntry(new JarEntry(entry));
                output.write("resource".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    private static URLClassLoader loader(File file) throws IOException {
        return new URLClassLoader(new URL[]{file.toURI().toURL()}, null);
    }

    private static final class MutableLoader extends URLClassLoader {
        private MutableLoader(URL url) {
            super(new URL[]{url}, null);
        }

        private void add(URL url) {
            addURL(url);
        }
    }
}
