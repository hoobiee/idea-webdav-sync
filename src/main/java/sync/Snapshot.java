package sync;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Untrusted archives are fully validated in memory before any local write. */
public final class Snapshot {
    public static final List<String> ROOTS = Arrays.stream(SyncCategory.values()).filter(c -> c != SyncCategory.PLUGINS).map(c -> c.root).toList();
    public static final int MAX_FILE = 2 * 1024 * 1024, MAX_TOTAL = 20 * 1024 * 1024, MAX_ARCHIVE = 22 * 1024 * 1024, MAX_ENTRIES = 1000;
    public final SortedMap<String, byte[]> files;
    public final String plugins;
    public Snapshot(Map<String, byte[]> files, String plugins) {
        this.files = new TreeMap<>();
        files.forEach((p, b) -> this.files.put(p, b.clone()));
        this.plugins = plugins;
    }
    public static boolean supportedFile(String p) {
        return SyncCategory.ofPath(p) != null && SyncCategory.ofPath(p) != SyncCategory.PLUGINS;
    }
    public static void path(String p) throws IOException {
        String[] parts = p.split("/", -1);
        if (parts.length < 2 || !ROOTS.contains(parts[0]) || !supportedFile(p) || p.contains("\\") || p.contains(":")) throw new IOException("Unsupported settings path: " + p);
        for (String part : parts) if (!part.matches("[A-Za-z0-9_. ()@+\\-]+") || part.equals(".") || part.equals("..")) throw new IOException("Invalid path segment");
    }
    private static String sha(byte[] b) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IOException(e); }
    }
    static byte[] bounded(InputStream in, int max) throws IOException {
        byte[] b = in.readNBytes(max + 1);
        if (b.length > max) throw new IOException("Data exceeds size limit");
        return b;
    }
    private static void xml(byte[] b) throws IOException {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            builder.parse(new ByteArrayInputStream(b));
        } catch (Exception e) { throw new IOException("Settings must contain safe, valid XML", e); }
    }
    private static String text(byte[] bytes) throws IOException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (value.chars().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13))
                throw new IOException("Binary control character in text file");
            return value;
        } catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid UTF-8 text", e); }
    }
    public Snapshot filtered(Set<SyncCategory> scope) {
        SyncCategory.requireSelection(scope);
        Map<String, byte[]> selected = new TreeMap<>();
        files.forEach((p, b) -> { if (scope.contains(SyncCategory.ofPath(p))) selected.put(p, b); });
        return new Snapshot(selected, scope.contains(SyncCategory.PLUGINS) ? plugins : null);
    }
    /** Replace selected categories, including deletions, preserving everything else remotely. */
    public Snapshot replacing(Snapshot local, Set<SyncCategory> scope) {
        SyncCategory.requireSelection(scope);
        Map<String, byte[]> merged = new TreeMap<>();
        files.forEach((p, b) -> { if (!scope.contains(SyncCategory.ofPath(p))) merged.put(p, b); });
        merged.putAll(local.filtered(scope).files);
        return new Snapshot(merged, scope.contains(SyncCategory.PLUGINS) ? local.plugins : plugins);
    }
    public byte[] encode() throws IOException {
        var all = new TreeMap<>(files);
        if (plugins != null) all.put("plugins.txt", plugins.getBytes(StandardCharsets.UTF_8));
        StringBuilder manifest = new StringBuilder("IDEA-WEBDAV-1\n");
        for (var e : all.entrySet()) manifest.append(sha(e.getValue())).append(' ').append(e.getKey()).append('\n');
        all.put("manifest.sha256", manifest.toString().getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : all.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry(); }
        }
        byte[] bytes = out.toByteArray();
        decode(bytes); // Apply identical limits to local exports.
        return bytes;
    }
    public static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_ARCHIVE) throw new IOException("Archive too large");
        Map<String, byte[]> all = new TreeMap<>(); int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String p = e.getName();
                if (!p.equals("manifest.sha256") && !p.equals("plugins.txt")) path(p);
                if (e.isDirectory() || all.containsKey(p) || all.size() >= MAX_ENTRIES) throw new IOException("Duplicate entry, directory or too many entries");
                byte[] b = bounded(zip, MAX_FILE); total += b.length;
                if (total > MAX_TOTAL) throw new IOException("Uncompressed data exceeds size limit");
                all.put(p, b);
            }
        }
        byte[] manifest = all.remove("manifest.sha256");
        if (manifest == null) throw new IOException("Missing manifest");
        String[] lines = new String(manifest, StandardCharsets.UTF_8).split("\n");
        if (lines.length == 0 || !lines[0].equals("IDEA-WEBDAV-1") || lines.length != all.size() + 1) throw new IOException("Invalid manifest");
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].length() < 66 || lines[i].charAt(64) != ' ') throw new IOException("Invalid manifest");
            String p = lines[i].substring(65); byte[] b = all.get(p);
            if (!seen.add(p) || b == null || !sha(b).equals(lines[i].substring(0, 64))) throw new IOException("SHA256 verification failed");
        }
        byte[] pluginBytes = all.remove("plugins.txt");
        String plugins = pluginBytes == null ? null : text(pluginBytes);
        for (var entry : all.entrySet()) {
            if (SyncCategory.ofPath(entry.getKey()) == SyncCategory.FILE_TEMPLATES) text(entry.getValue());
            else xml(entry.getValue());
        }
        return new Snapshot(all, plugins);
    }
}
