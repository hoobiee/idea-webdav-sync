package sync;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

public class SyncTest {
    @Test void whitelistBackupRollbackAndSymlinkRejection(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("keymaps"));
        Files.createDirectories(dir.resolve("options"));
        Files.writeString(dir.resolve("keymaps/mine.xml"), "<keymap name='v1'/>");
        Files.writeString(dir.resolve("options/other.xml"), "<secret/>");
        var store = new ConfigStore(dir);
        Snapshot v1 = store.exportSnapshot("org.example\tExample\t1.0\tenabled\n");
        assertEquals(Set.of("keymaps/mine.xml"), v1.files.keySet());

        Snapshot v2 = new Snapshot(Map.of("keymaps/mine.xml", "<keymap name='v2'/>".getBytes(StandardCharsets.UTF_8),
            "colors/theme.xml", "<scheme/>".getBytes(StandardCharsets.UTF_8)), "p\n");
        store.applyWithBackup(v2);
        assertTrue(Files.exists(store.backupPath()));
        assertEquals("<keymap name='v2'/>", Files.readString(dir.resolve("keymaps/mine.xml")));
        assertTrue(Files.exists(dir.resolve("colors/theme.xml")));
        assertEquals("<secret/>", Files.readString(dir.resolve("options/other.xml")));

        store.rollback();
        assertEquals("<keymap name='v1'/>", Files.readString(dir.resolve("keymaps/mine.xml")));
        assertFalse(Files.exists(dir.resolve("colors/theme.xml")));

        Files.createSymbolicLink(dir.resolve("keymaps/link.xml"), dir.resolve("options/other.xml"));
        assertThrows(IOException.class, () -> store.exportSnapshot(""));
    }

    @Test void colorSchemesAreXmlAndRestrictedToColors(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("colors"));
        Files.createDirectories(dir.resolve("keymaps"));
        Files.writeString(dir.resolve("colors/custom.icls"), "<scheme name='custom'/>");
        Files.writeString(dir.resolve("keymaps/ignored.icls"), "<keymap/>");
        var store = new ConfigStore(dir);
        Snapshot exported = Snapshot.decode(store.exportSnapshot("").encode());
        assertEquals(Set.of("colors/custom.icls"), exported.files.keySet());
        assertThrows(IOException.class, () -> Snapshot.path("keymaps/evil.icls"));
        assertThrows(IOException.class, () -> new Snapshot(Map.of("colors/bad.icls",
            "not xml".getBytes(StandardCharsets.UTF_8)), "").encode());
        assertThrows(IOException.class, () -> new Snapshot(Map.of("colors/xxe.icls",
            "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>".getBytes(StandardCharsets.UTF_8)), "").encode());
        store.applyWithBackup(new Snapshot(Map.of(), ""));
        assertFalse(Files.exists(dir.resolve("colors/custom.icls")));
        store.rollback();
        assertTrue(Files.exists(dir.resolve("colors/custom.icls")));
        assertTrue(Files.exists(dir.resolve("keymaps/ignored.icls")));
    }

    @Test void archiveSecurityGuards() throws Exception {
        Snapshot ok = new Snapshot(Map.of("templates/user.xml", "<templateSet/>".getBytes(StandardCharsets.UTF_8)), "plug\n");
        byte[] encoded = ok.encode();
        assertEquals("plug\n", Snapshot.decode(encoded).plugins);

        assertThrows(IOException.class, () -> Snapshot.path("../evil.xml"));
        assertThrows(IOException.class, () -> Snapshot.path("options/evil.xml"));
        assertThrows(IOException.class, () -> Snapshot.path("keymaps/evil.txt"));
        assertThrows(IOException.class, () -> new Snapshot(Map.of("keymaps/xxe.xml",
            "<!DOCTYPE a [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><a>&xxe;</a>".getBytes(StandardCharsets.UTF_8)), "").encode());

        // Tamper payload -> SHA256 mismatch
        byte[] tampered = replaceEntry(encoded, "templates/user.xml", "<templateSet id='2'/>".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> Snapshot.decode(tampered));

        // Oversized single entry (>2 MiB) rejected by bounded stream read
        ByteArrayOutputStream bigOut = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bigOut)) {
            z.putNextEntry(new ZipEntry("keymaps/big.xml"));
            z.write(new byte[Snapshot.MAX_FILE + 10]);
            z.closeEntry();
        }
        assertThrows(IOException.class, () -> Snapshot.decode(bigOut.toByteArray()));
    }

    @Test void webdavStrongEtagAndConflict(@TempDir Path dir) throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> etag = new AtomicReference<>();
        AtomicReference<Boolean> omitEtag = new AtomicReference<>(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/", ex -> {
            if (!"Basic dXNlcjpwYXNz".equals(ex.getRequestHeaders().getFirst("Authorization"))) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            if ("/dav/".equals(ex.getRequestURI().getPath()) && "PROPFIND".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(207, -1); ex.close(); return; }
            ex.sendResponseHeaders(404, -1); ex.close();
        });
        server.createContext("/dav/" + WebDavClient.FILE, ex -> {
            if (!"Basic dXNlcjpwYXNz".equals(ex.getRequestHeaders().getFirst("Authorization"))) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            byte[] cur = body.get();
            if ("HEAD".equals(ex.getRequestMethod()) || "GET".equals(ex.getRequestMethod())) {
                if (cur == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                if (!omitEtag.get()) ex.getResponseHeaders().set("ETag", etag.get());
                ex.sendResponseHeaders(200, "HEAD".equals(ex.getRequestMethod()) ? -1 : cur.length);
                if ("GET".equals(ex.getRequestMethod())) ex.getResponseBody().write(cur);
                ex.close(); return;
            }
            if ("PUT".equals(ex.getRequestMethod())) {
                String inm = ex.getRequestHeaders().getFirst("If-None-Match");
                String im = ex.getRequestHeaders().getFirst("If-Match");
                if ((cur == null && !"*".equals(inm)) || (cur != null && !Objects.equals(etag.get(), im))) {
                    ex.sendResponseHeaders(412, -1); ex.close(); return;
                }
                byte[] next = ex.getRequestBody().readAllBytes();
                body.set(next);
                etag.set("\"" + UUID.randomUUID() + "\"");
                ex.getResponseHeaders().set("ETag", etag.get());
                ex.sendResponseHeaders(201, -1); ex.close();
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/";
            var client = new WebDavClient();
            assertThrows(IOException.class, () -> WebDavClient.fileUri("http://example.com/dav"));
            String missingDir = "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/missing/";
            assertThrows(IOException.class, () -> client.test(missingDir, "user", "pass"));
            assertThrows(IOException.class, () -> client.download(missingDir, "user", "pass"));
            var r0 = client.download(url, "user", "pass");
            assertFalse(r0.exists());

            Snapshot s1 = new Snapshot(Map.of("inspection/p.xml", "<profile/>".getBytes(StandardCharsets.UTF_8)), "p1\n");
            String e1 = client.upload(url, "user", "pass", s1, r0);
            var r1 = client.download(url, "user", "pass");
            assertEquals(e1, r1.etag());

            // Stale state (still thinking remote does not exist) must fail with 412
            assertThrows(IOException.class, () -> client.upload(url, "user", "pass", s1, r0));
            // Missing ETag from server must be refused
            omitEtag.set(true);
            assertThrows(IOException.class, () -> client.download(url, "user", "pass"));
        } finally { server.stop(0); }
    }

    private static byte[] replaceEntry(byte[] zip, String name, byte[] replacement) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip)); var zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(e.getName().equals(name) ? replacement : in.readAllBytes());
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
