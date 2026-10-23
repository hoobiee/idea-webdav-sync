package sync;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Minimal WebDAV client with strong ETag optimistic locking and no auto-redirect. */
public final class WebDavClient {
    public static final String FILE = "idea-settings-snapshot.zip";
    public record RemoteState(boolean exists, String etag, Snapshot snapshot) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static URI fileUri(String baseUrl) throws IOException {
        try {
            URI u = new URI(baseUrl.trim());
            String s = u.getScheme(), h = u.getHost();
            if (s == null || h == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null) throw new IOException("Invalid URL");
            boolean localhost = h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
            if (!s.equalsIgnoreCase("https") && !(s.equalsIgnoreCase("http") && localhost)) throw new IOException("HTTPS required (localhost HTTP allowed for tests)");
            String p = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
            if (!p.endsWith("/")) p += "/";
            return new URI(s.toLowerCase(), null, h, u.getPort(), p, null, null);
        } catch (URISyntaxException e) { throw new IOException("Invalid URL", e); }
    }
    public static URI dirUri(String baseUrl) throws IOException { return fileUri(baseUrl); }
    private static URI snapUri(String baseUrl) throws IOException {
        URI d = dirUri(baseUrl);
        try { return new URI(d.getScheme(), null, d.getHost(), d.getPort(), d.getPath() + FILE, null, null); }
        catch (URISyntaxException e) { throw new IOException("Invalid URL", e); }
    }
    static String strongEtag(String etag) throws IOException {
        if (etag == null) throw new IOException("Server did not return ETag; unsafe overwrite refused");
        String t = etag.trim();
        if (t.startsWith("W/") || t.startsWith("w/") || !t.startsWith("\"") || !t.endsWith("\"") || t.length() < 3) throw new IOException("Strong ETag required: " + etag);
        return t;
    }
    private HttpRequest.Builder reqTo(URI target, String user, String pass) {
        var b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20));
        if (user != null && !user.isBlank()) {
            String token = Base64.getEncoder().encodeToString((user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b;
    }
    private HttpRequest.Builder req(String url, String user, String pass) throws IOException { return reqTo(snapUri(url), user, pass); }
    private void verifyDirectoryExists(String url, String user, String pass) throws IOException, InterruptedException {
        var r = http.send(reqTo(dirUri(url), user, pass).header("Depth", "0").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 404) throw new IOException("Remote directory does not exist (HTTP 404)");
        if (r.statusCode() != 207 && r.statusCode() != 200) throw new IOException("Directory check failed HTTP " + r.statusCode());
    }
    public String test(String url, String user, String pass) throws IOException, InterruptedException {
        var r = http.send(req(url, user, pass).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 404) { verifyDirectoryExists(url, user, pass); return "Connected (no remote snapshot yet)"; }
        if (r.statusCode() == 200 || r.statusCode() == 204) return "Connected, ETag=" + strongEtag(r.headers().firstValue("ETag").orElse(null));
        throw new IOException("HTTP " + r.statusCode());
    }
    public RemoteState download(String url, String user, String pass) throws IOException, InterruptedException {
        var r = http.send(req(url, user, pass).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = r.body()) {
            if (r.statusCode() == 404) { verifyDirectoryExists(url, user, pass); return new RemoteState(false, null, null); }
            if (r.statusCode() != 200) throw new IOException("Download failed HTTP " + r.statusCode());
            String etag = strongEtag(r.headers().firstValue("ETag").orElse(null));
            return new RemoteState(true, etag, Snapshot.decode(Snapshot.bounded(body, Snapshot.MAX_ARCHIVE)));
        }
    }
    public String upload(String url, String user, String pass, Snapshot snap, RemoteState expected) throws IOException, InterruptedException {
        if (expected == null) throw new IOException("Download and preview the remote version before uploading");
        var b = req(url, user, pass).header("Content-Type", "application/octet-stream");
        if (expected.exists()) b.header("If-Match", strongEtag(expected.etag()));
        else b.header("If-None-Match", "*");
        var r = http.send(b.PUT(HttpRequest.BodyPublishers.ofByteArray(snap.encode())).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 412) throw new IOException("Remote snapshot changed (HTTP 412); download and preview again");
        if (r.statusCode() != 200 && r.statusCode() != 201 && r.statusCode() != 204) throw new IOException("Upload failed HTTP " + r.statusCode());
        var head = http.send(req(url, user, pass).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        return strongEtag(head.headers().firstValue("ETag").orElse(r.headers().firstValue("ETag").orElse(null)));
    }
}
