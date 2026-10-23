package sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ScopeTest {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static Set<SyncCategory> only(SyncCategory c) { return EnumSet.of(c); }
    private static Set<String> entries(byte[] archive) throws IOException {
        Set<String> names = new TreeSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }
    @Test void categoryPathsAndPersistence() throws Exception {
        for (SyncCategory c : SyncCategory.values()) {
            String p = c == SyncCategory.PLUGINS ? "plugins.txt" : c.root + "/test.xml";
            assertEquals(c, SyncCategory.ofPath(p));
        }
        for (String bad : List.of("options/a.xml", "keymaps/a.icls", "fileTemplates/a.exe", "fileTemplates/../a.java", "dictionaries/../../a.xml", "quicklists/a\\b.xml"))
            assertThrows(IOException.class, () -> Snapshot.path(bad));
        var scope = EnumSet.of(SyncCategory.FILE_TEMPLATES, SyncCategory.PLUGINS);
        assertEquals(scope, SyncCategory.parse(SyncCategory.serialize(scope)));
        assertEquals(Set.of(), SyncCategory.parse(""));
        assertFalse(SyncCategory.serialize(Set.of()).isEmpty());
        assertEquals(Set.of(), SyncCategory.parse(SyncCategory.serialize(Set.of())));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Map.of(), null).filtered(Set.of()));
    }
    @Test void localScopeDoesNotReadUnselectedInvalidFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("keymaps"));
        Files.createDirectories(dir.resolve("colors"));
        Files.writeString(dir.resolve("keymaps/a.xml"), "<keymap/>");
        Files.writeString(dir.resolve("colors/b.xml"), "not xml");
        var store = new ConfigStore(dir);
        var snapshot = store.exportSnapshot("secret plugin list", only(SyncCategory.KEYMAPS));
        assertEquals(Set.of("manifest.sha256", "keymaps/a.xml"), entries(snapshot.encode()));
        assertNull(Snapshot.decode(snapshot.encode()).plugins);
        assertThrows(IOException.class, () -> store.exportSnapshot("", only(SyncCategory.COLORS)));
        var remote = new Snapshot(Map.of("colors/cloud.xml", bytes("<scheme/>")), "cloud plugin");
        String diff = store.previewDiff(remote, only(SyncCategory.KEYMAPS));
        assertTrue(diff.contains("- keymaps/a.xml"));
        assertFalse(diff.contains("colors/"));
        assertFalse(diff.contains("cloud plugin"));
    }
    @Test void templateTextAndNewXmlSafety(@TempDir Path dir) throws Exception {
        String velocity = "#if (${NAME})\nclass ${NAME} { <not-xml & raw> }\n#end\n";
        Map<String, byte[]> files = new TreeMap<>();
        files.put("fileTemplates/internal/Class.java", bytes(velocity));
        files.put("fileTemplates/includes/Header.java.ft", bytes("/** ${USER} */"));
        files.put("dictionaries/user.xml", bytes("<dictionary/>"));
        files.put("quicklists/actions.xml", bytes("<list/>"));
        var decoded = Snapshot.decode(new Snapshot(files, null).encode());
        assertEquals(velocity, new String(decoded.files.get("fileTemplates/internal/Class.java"), StandardCharsets.UTF_8));
        for (byte[] bad : List.of(new byte[]{(byte)0xc3, 0x28}, new byte[]{0, 1, 2}))
            assertThrows(IOException.class, () -> new Snapshot(Map.of("fileTemplates/a.java", bad), null).encode());
        for (String root : List.of("dictionaries", "quicklists")) {
            assertThrows(IOException.class, () -> new Snapshot(Map.of(root + "/a.xml", bytes("not XML")), null).encode());
            assertThrows(IOException.class, () -> new Snapshot(Map.of(root + "/a.xml", bytes("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///nonexistent'>]><x>&e;</x>")), null).encode());
        }
        Files.createSymbolicLink(dir.resolve("fileTemplates"), dir.resolve("missing"));
        // Existing symlink roots must be refused even when their targets are absent.
        assertThrows(IOException.class, () -> new ConfigStore(dir).exportSnapshot("", only(SyncCategory.FILE_TEMPLATES)));
    }
    @Test void legacyManifestAndPluginOnlyArchives() throws Exception {
        // Literal old format fixture, independent of the new encoder.
        var out = new ByteArrayOutputStream();
        byte[] xml = bytes("<keymap/>");
        byte[] plugins = bytes("legacy.plugin\n");
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        String manifest = "IDEA-WEBDAV-1\n" + HexFormat.of().formatHex(sha.digest(xml)) + " keymaps/old.xml\n" + HexFormat.of().formatHex(sha.digest(plugins)) + " plugins.txt\n";
        try (var zip = new ZipOutputStream(out)) {
            for (var e : Map.of("keymaps/old.xml", xml, "plugins.txt", plugins, "manifest.sha256", bytes(manifest)).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        var legacy = Snapshot.decode(out.toByteArray());
        assertEquals("legacy.plugin\n", legacy.plugins);
        assertEquals(Set.of("plugins.txt", "manifest.sha256"), entries(legacy.filtered(only(SyncCategory.PLUGINS)).encode()));
        assertEquals(Set.of("keymaps/old.xml", "manifest.sha256"), entries(legacy.filtered(only(SyncCategory.KEYMAPS)).encode()));
    }
    @Test void actualUploadPreservesCloudCategoriesAndDeletesSelectedExtras() throws Exception {
        var remote = new Snapshot(Map.of("keymaps/old.xml", bytes("<old/>"), "colors/cloud.icls", bytes("<scheme/>"), "quicklists/cloud.xml", bytes("<list/>")), "remote.plugin\n");
        AtomicReference<byte[]> stored = new AtomicReference<>(remote.encode());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/" + WebDavClient.FILE, ex -> {
            ex.getResponseHeaders().set("ETag", "\"v1\"");
            switch (ex.getRequestMethod()) {
                case "GET" -> { byte[] b = stored.get(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); }
                case "PUT" -> {
                    if (!"\"v1\"".equals(ex.getRequestHeaders().getFirst("If-Match"))) ex.sendResponseHeaders(412, -1);
                    else { stored.set(ex.getRequestBody().readAllBytes()); ex.sendResponseHeaders(204, -1); }
                }
                default -> ex.sendResponseHeaders(200, -1);
            }
            ex.close();
        });
        server.start();
        try {
            var client = new WebDavClient();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/";
            var expected = client.download(url, "", "");
            var scope = only(SyncCategory.KEYMAPS);
            var local = new Snapshot(Map.of("keymaps/new.xml", bytes("<new/>")), "local.plugin\n");
            client.upload(url, "", "", expected.snapshot().replacing(local, scope), expected);
            var actual = Snapshot.decode(stored.get());
            assertEquals(Set.of("keymaps/new.xml", "colors/cloud.icls", "quicklists/cloud.xml"), actual.files.keySet());
            assertArrayEquals(remote.files.get("colors/cloud.icls"), actual.files.get("colors/cloud.icls"));
            assertEquals(remote.plugins, actual.plugins);
            var removed = actual.replacing(new Snapshot(Map.of(), null), only(SyncCategory.QUICKLISTS));
            assertFalse(removed.files.containsKey("quicklists/cloud.xml"));
            assertTrue(removed.files.containsKey("keymaps/new.xml"));
            assertEquals("local.plugin\n", actual.replacing(local, only(SyncCategory.PLUGINS)).plugins);
        } finally { server.stop(0); }
    }
}
