package sync;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Isolated file operations on the five whitelisted subdirectories. */
public final class ConfigStore {
    private final Path configDir;
    public ConfigStore(Path configDir) { this.configDir = configDir.toAbsolutePath().normalize(); }
    public Snapshot exportSnapshot(String plugins) throws IOException {
        return exportSnapshot(plugins, EnumSet.allOf(SyncCategory.class));
    }
    public Snapshot exportSnapshot(String plugins, Set<SyncCategory> scope) throws IOException {
        SyncCategory.requireSelection(scope);
        Map<String, byte[]> files = new TreeMap<>(); int total = 0;
        for (String root : Snapshot.ROOTS) {
            if (!scope.contains(Arrays.stream(SyncCategory.values()).filter(c -> c.root.equals(root)).findFirst().orElseThrow())) continue;
            Path dir = safeResolve(root);
            if (Files.isSymbolicLink(dir)) throw new IOException("Symbolic link refused: " + root);
            if (!Files.exists(dir)) continue;
            if (!Files.isDirectory(dir)) throw new IOException("Invalid configuration directory: " + root);
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isSymbolicLink(p)) throw new IOException("Symbolic link refused: " + p);
                    if (!Files.isRegularFile(p)) continue;
                    String rel = configDir.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    if (!Snapshot.supportedFile(rel)) continue;
                    Snapshot.path(rel);
                    if (files.size() >= Snapshot.MAX_ENTRIES) throw new IOException("Too many settings files");
                    try (var in = Files.newInputStream(p)) {
                        byte[] b = Snapshot.bounded(in, Snapshot.MAX_FILE);
                        total += b.length;
                        if (total > Snapshot.MAX_TOTAL) throw new IOException("Settings exceed size limit");
                        files.put(rel, b);
                    }
                }
            }
        }
        return Snapshot.decode(new Snapshot(files, scope.contains(SyncCategory.PLUGINS) ? plugins : null).encode());
    }
    public String previewDiff(Snapshot remote) throws IOException {
        return previewDiff(remote, EnumSet.allOf(SyncCategory.class));
    }
    public String previewDiff(Snapshot remote, Set<SyncCategory> scope) throws IOException {
        remote = remote.filtered(scope);
        Snapshot local = exportSnapshot("", scope);
        Set<String> all = new TreeSet<>(local.files.keySet()); all.addAll(remote.files.keySet());
        StringBuilder sb = new StringBuilder();
        for (String p : all) {
            byte[] l = local.files.get(p), r = remote.files.get(p);
            if (l == null) sb.append("+ ").append(p).append('\n');
            else if (r == null) sb.append("- ").append(p).append('\n');
            else if (!Arrays.equals(l, r)) sb.append("~ ").append(p).append('\n');
        }
        if (scope.contains(SyncCategory.PLUGINS)) sb.append("=== Remote plugin list (manual review only) ===\n").append(remote.plugins == null ? "(none)" : remote.plugins);
        return sb.toString();
    }
    public Path backupPath() throws IOException { return safeResolve("webdav-sync-backup.zip"); }
    public void applyWithBackup(Snapshot s) throws IOException {
        Files.createDirectories(configDir);
        byte[] backup = exportSnapshot("").encode();
        Path tmp = safeResolve("webdav-sync-backup.zip.tmp");
        Files.write(tmp, backup);
        Files.move(tmp, backupPath(), StandardCopyOption.REPLACE_EXISTING);
        writeRaw(s);
    }
    public void rollback() throws IOException {
        Path b = backupPath();
        if (!Files.isRegularFile(b) || Files.isSymbolicLink(b)) throw new IOException("No local backup available");
        writeRaw(Snapshot.decode(Files.readAllBytes(b)));
    }
    private void writeRaw(Snapshot s) throws IOException {
        Set<Path> keep = new HashSet<>();
        for (var e : s.files.entrySet()) {
            Snapshot.path(e.getKey());
            Path target = safeResolve(e.getKey());
            for (Path cur = configDir.relativize(target), acc = configDir; cur.getNameCount() > 1; cur = cur.subpath(1, cur.getNameCount())) {
                acc = acc.resolve(cur.getName(0));
                if (Files.isSymbolicLink(acc)) throw new IOException("Refusing symbolic link directory");
            }
            if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic link overwrite");
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
            keep.add(target);
        }
        for (String root : Snapshot.ROOTS) {
            Path dir = safeResolve(root);
            if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
            try (Stream<Path> st = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Snapshot.supportedFile(configDir.relativize(p).toString().replace('\\', '/')) && !keep.contains(p.toAbsolutePath().normalize())) Files.delete(p);
                }
            }
        }
    }
    private Path safeResolve(String rel) throws IOException {
        Path p = configDir.resolve(rel).toAbsolutePath().normalize();
        if (!p.startsWith(configDir)) throw new IOException("Path outside allowed directory");
        return p;
    }
}
