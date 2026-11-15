package github.kasuminova.stellarcore.client.resource;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class DirectoryResourceIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After public void reset() {
        DirectoryResourceIndex.disable();
        System.clearProperty("stellarcore.verifyDirectoryResourceIndex");
    }

    @Test public void matchesFileAndDirectoryQueriesIncludingNativeCaseRules() throws Exception {
        File root = temporary.newFolder();
        File folder = new File(root, "test/models");
        assertTrue(folder.mkdirs());
        File model = new File(folder, "Case.json");
        assertTrue(model.createNewFile());
        DirectoryResourceIndex.enable();
        for (String name : new String[]{"test/models", "test/models/Case.json", "test/models/case.json", "absent.json"}) {
            File original = new File(root, name);
            assertEquals(original.isFile(), DirectoryResourceIndex.query(root, name, true));
            assertEquals(original.exists(), DirectoryResourceIndex.query(root, name, false));
        }
    }

    @Test public void rebuildsAfterReloadAndFallsBackOutsideTheModelWindow() throws Exception {
        File root = temporary.newFolder();
        File added = new File(root, "added.json");
        assertNull(DirectoryResourceIndex.query(root, "added.json", true));
        DirectoryResourceIndex.enable();
        assertEquals(false, DirectoryResourceIndex.query(root, "added.json", true));
        assertTrue(added.createNewFile());
        DirectoryResourceIndex.disable();
        assertNull(DirectoryResourceIndex.query(root, "added.json", true));
        DirectoryResourceIndex.enable();
        assertEquals(true, DirectoryResourceIndex.query(root, "added.json", true));
        Files.delete(added.toPath());
        DirectoryResourceIndex.enable();
        assertEquals(false, DirectoryResourceIndex.query(root, "added.json", true));
    }

    @Test public void specialPathsAndUnreadableRootsUseOriginalQueries() {
        File root = temporary.getRoot();
        DirectoryResourceIndex.enable();
        for (String path : new String[]{"", "../outside", "absent/../test", "test/./file", "/test", "test/", "test//file", "test\\file", "C:/file", "test/file.", "test/file "}) {
            assertNull(path, DirectoryResourceIndex.query(root, path, false));
        }
        assertNull(DirectoryResourceIndex.query(new File(root, "absent"), "test", true));
    }

    @Test public void parallelPacksShareOneSnapshotUntilReload() throws Exception {
        File root = temporary.newFolder();
        File sameRoot = new File(root, ".");
        assertTrue(new File(root, "old").createNewFile());
        DirectoryResourceIndex.enable();
        assertEquals(true, DirectoryResourceIndex.query(root, "old", true));
        assertTrue(new File(root, "new").createNewFile());
        IntStream.range(0, 1000).parallel().forEach(i -> {
            assertEquals(true, DirectoryResourceIndex.query(sameRoot, "old", true));
            assertEquals(false, DirectoryResourceIndex.query(sameRoot, "new", true));
        });
    }

    @Test public void diagnosticModeReturnsOriginalResultsAfterDirectoryChanges() throws Exception {
        File root = temporary.newFolder();
        System.setProperty("stellarcore.verifyDirectoryResourceIndex", "true");
        DirectoryResourceIndex.enable();
        assertEquals(false, DirectoryResourceIndex.query(root, "generated", true));
        assertTrue(new File(root, "generated").createNewFile());
        assertEquals(true, DirectoryResourceIndex.query(root, "generated", true));
    }
}
