package github.kasuminova.stellarcore.client.resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * 一次资源加载期间的类路径资源索引。只跳过确定不存在的查询；
 * 命中仍走原加载器，保留路径大小写校验、资源优先级和流打开语义。
 */
public final class ClasspathAssetIndex implements AutoCloseable {

    private final Set<String> assets = new HashSet<>();
    private final List<File> directories = new ArrayList<>();
    private final Set<URL> fallbackSources = new LinkedHashSet<>();
    private final List<String> incompleteReasons = new ArrayList<>();
    private URLClassLoader fallbackLoader;
    private volatile boolean complete = true;
    private int jarCount;

    private ClasspathAssetIndex() {
    }

    public static ClasspathAssetIndex create(final ClassLoader loader) {
        ClasspathAssetIndex index = new ClasspathAssetIndex();
        Set<URL> sources = new LinkedHashSet<>();
        index.collectSources(loader, sources);
        Set<File> visited = new HashSet<>();
        for (URL source : sources) {
            index.scan(source, visited);
        }
        if (!index.fallbackSources.isEmpty()) {
            // 保留 URL 自带的协议处理器；例如 Forge 的 asmgen:/ 无法由文件系统枚举。
            index.fallbackLoader = new URLClassLoader(index.fallbackSources.toArray(new URL[0]), null);
        }
        return index;
    }

    private void collectSources(final ClassLoader loader, final Set<URL> sources) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current instanceof URLClassLoader && hasStandardResourceLookup(current.getClass())) {
                Collections.addAll(sources, ((URLClassLoader) current).getURLs());
            } else if (current.getClass().getName().equals("jdk.internal.loader.ClassLoaders$AppClassLoader")) {
                // Java 9+ 的系统加载器不再继承 URLClassLoader。
                for (String entry : System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator))) {
                    try {
                        sources.add(new File(entry.isEmpty() ? "." : entry).toURI().toURL());
                    } catch (IOException e) {
                        incomplete("Invalid system classpath entry: " + entry + " (" + e + ")");
                    }
                }
            } else if (!current.getClass().getName().equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader")) {
                // 自定义资源来源不可枚举时，不能把索引未命中当作资源不存在。
                incomplete("Unsupported resource loader: " + current.getClass().getName());
            }
        }
    }

    private static boolean hasStandardResourceLookup(final Class<?> loaderClass) {
        try {
            return loaderClass.getMethod("getResource", String.class).getDeclaringClass() == ClassLoader.class
                    && loaderClass.getMethod("findResource", String.class).getDeclaringClass() == URLClassLoader.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private void scan(final URL source, final Set<File> visited) {
        if (!"file".equals(source.getProtocol())) {
            // 只把不能枚举的来源交回 URLClassLoader，普通 jar 的索引仍然有效。
            fallbackSources.add(source);
            return;
        }
        final File file;
        try {
            file = new File(source.toURI()).getCanonicalFile();
        } catch (Exception e) {
            incomplete("Invalid file URL: " + source + " (" + e + ")");
            return;
        }
        if (!visited.add(file)) {
            return;
        }
        if (source.getPath().endsWith("/")) {
            // 保留目录根，查询时检查，开发目录新增文件不会得到过期的否定结果。
            directories.add(file);
            return;
        }
        // 启动器的 Manifest 常保留发行包中的相对路径；不存在的 jar 同样不会被加载器搜索到。
        if (Files.notExists(file.toPath())) {
            return;
        }
        try (JarFile jar = new JarFile(file, false)) {
            jarCount++;
            Manifest manifest = jar.getManifest();
            boolean multiRelease = manifest != null
                    && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("assets/")) {
                    assets.add(name);
                } else if (multiRelease && name.startsWith("META-INF/versions/")) {
                    addVersionedAsset(name);
                }
            }
            // URLClassLoader 也会搜索 Manifest 中的附加类路径。
            if (manifest != null) {
                String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (classPath != null && !classPath.trim().isEmpty()) {
                    for (String entry : classPath.trim().split("\\s+")) {
                        scan(new URL(source, entry), visited);
                    }
                }
            }
            JarEntry jarIndex = jar.getJarEntry("META-INF/INDEX.LIST");
            if (jarIndex != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(jarIndex), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("assets") || line.startsWith("assets/")) {
                            // 只有覆盖 assets 的旧式 JarIndex 才可能从其他 jar 补充资源。
                            incomplete("Asset JarIndex: " + source);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            incomplete("Unreadable jar: " + source + " (" + e + ")");
        }
    }

    private void addVersionedAsset(final String name) {
        int slash = name.indexOf('/', "META-INF/versions/".length());
        if (slash < 0) {
            return;
        }
        try {
            int version = Integer.parseInt(name.substring("META-INF/versions/".length(), slash));
            String runtime = System.getProperty("java.specification.version");
            int javaVersion = Integer.parseInt(runtime.startsWith("1.") ? runtime.substring(2) : runtime);
            String path = name.substring(slash + 1);
            if (version >= 9 && version <= javaVersion && path.startsWith("assets/")) {
                assets.add(path);
            }
        } catch (NumberFormatException ignored) {
            // 非版本目录不会被多版本 jar 查找机制采用。
        }
    }

    public boolean definitelyMissing(final String absolutePath) {
        // 不规范的路径交给原加载器解释，避免索引自行归一化改变原有行为。
        if (!complete || !absolutePath.startsWith("/assets/") || absolutePath.indexOf('\\') >= 0
                || absolutePath.contains("//") || absolutePath.contains("/./")
                || absolutePath.contains("/../") || absolutePath.endsWith("/..") || absolutePath.endsWith("/.")) {
            return false;
        }
        String path = absolutePath.substring(1);
        if (assets.contains(path)) {
            return false;
        }
        for (File directory : directories) {
            if (new File(directory, path).exists()) {
                return false;
            }
        }
        return fallbackLoader == null || missingFromFallback(path);
    }

    private synchronized boolean missingFromFallback(final String path) {
        // 与关闭串行，避免资源重载后在已关闭的加载器上得到错误的否定结果。
        return complete && fallbackLoader.findResource(path) == null;
    }

    private void incomplete(final String reason) {
        complete = false;
        incompleteReasons.add(reason);
    }

    @Override
    public synchronized void close() throws IOException {
        complete = false;
        if (fallbackLoader != null) {
            fallbackLoader.close();
        }
    }

    public Set<URL> getFallbackSources() {
        return Collections.unmodifiableSet(fallbackSources);
    }

    public List<String> getIncompleteReasons() {
        return Collections.unmodifiableList(incompleteReasons);
    }

    public boolean isComplete() {
        return complete;
    }

    public int getAssetCount() {
        return assets.size();
    }

    public int getJarCount() {
        return jarCount;
    }
}
