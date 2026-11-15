package github.kasuminova.stellarcore.client.resource;

import github.kasuminova.stellarcore.common.util.StellarLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 仅在一次模型加载期间共享目录快照；资源重载和加载结束都释放。 */
public final class DirectoryResourceIndex {
    private static volatile Session session;

    private DirectoryResourceIndex() {}

    public static void enable() {
        session = new Session(Boolean.getBoolean("stellarcore.verifyDirectoryResourceIndex"));
    }

    public static void disable() {
        Session previous = session;
        session = null;
        if (previous != null && previous.verify) {
            StellarLog.LOG.info("[StellarCore-DirectoryResourceIndex] Verified {} queries, {} mismatches.",
                    previous.queries.get(), previous.mismatches.get());
        }
    }

    /** null 表示使用原文件查询。fileOnly 区分 ResourceLoader.isFile 和 BASE.exists。 */
    public static Boolean query(File root, String name, boolean fileOnly) {
        Session current = session;
        if (current == null || !isSimplePath(name)) return null;
        try {
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Snapshot snapshot = current.roots.computeIfAbsent(rootPath, DirectoryResourceIndex::scan);
            if (snapshot.entries == null) return null;
            Boolean file = snapshot.entries.get(rootPath.getFileSystem().getPath(name));
            boolean found = file != null && (!fileOnly || file);
            if (current.verify) {
                File original = new File(root, name);
                boolean actual = fileOnly ? original.isFile() : original.exists();
                current.queries.incrementAndGet();
                if (actual != found) {
                    if (current.mismatches.incrementAndGet() <= 10) {
                        StellarLog.LOG.warn("[StellarCore-DirectoryResourceIndex] Mismatch: {}, indexed={}, actual={}",
                                original, found, actual);
                    }
                }
                // 诊断运行使用原结果，避免诊断本身改变资源选择。
                return actual;
            }
            return found;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean isSimplePath(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/")
                || name.contains("\\") || name.contains(":") || name.contains("//")) return false;
        for (String part : name.split("/")) {
            if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return false;
        }
        return true;
    }

    private static Snapshot scan(Path root) {
        long start = System.nanoTime();
        Map<Path, Boolean> entries = new HashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    checkAttributes(attrs);
                    entries.put(root.relativize(dir), false);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkAttributes(attrs);
                    entries.put(root.relativize(file), true);
                    return FileVisitResult.CONTINUE;
                }

                private void checkAttributes(BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
                        throw new IOException("unsupported directory entry");
                    }
                }
            });
            StellarLog.LOG.info("[StellarCore-DirectoryResourceIndex] Indexed {} entries in {}ms: {}",
                    entries.size(), (System.nanoTime() - start) / 1_000_000, root);
            return new Snapshot(entries);
        } catch (IOException | SecurityException e) {
            StellarLog.LOG.info("[StellarCore-DirectoryResourceIndex] Using original queries for {}: {}", root, e.toString());
            return new Snapshot(null);
        }
    }

    private static final class Snapshot {
        final Map<Path, Boolean> entries;
        Snapshot(Map<Path, Boolean> entries) { this.entries = entries; }
    }

    private static final class Session {
        final Map<Path, Snapshot> roots = new ConcurrentHashMap<>();
        final boolean verify;
        final AtomicLong queries = new AtomicLong();
        final AtomicLong mismatches = new AtomicLong();
        Session(boolean verify) { this.verify = verify; }
    }
}
