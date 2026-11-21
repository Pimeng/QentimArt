package qentimart.internal;

import qentimart.QentimArtException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** 静态模式的只读传输：从公开 baseUrl 按对象键 GET，不需要鉴权。 */
public final class StaticTransport implements AutoCloseable {
    private final HttpClient client;
    private final String origin;
    private final String basePath;
    private final Duration timeout;
    final int retries;
    final int concurrency;
    private volatile Map<String, Object> index;
    private volatile long indexAt;

    public StaticTransport(String baseUrl, int concurrency, long timeoutMs, int retries) {
        URI u;
        try {
            u = URI.create(baseUrl);
        } catch (RuntimeException e) {
            throw new QentimArtException("invalid baseUrl " + baseUrl);
        }
        String scheme = u.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || u.getHost() == null) {
            throw new QentimArtException("invalid baseUrl " + baseUrl);
        }
        String portPart = u.getPort() >= 0 ? ":" + u.getPort() : "";
        this.origin = scheme + "://" + u.getHost() + portPart;
        String base = u.getRawPath() == null ? "" : u.getRawPath();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.basePath = base;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.retries = retries;
        this.concurrency = concurrency;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.timeout)
                .build();
    }

    public byte[] getBuffer(String rel, long max, AtomicBoolean cancel) {
        Throwable last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (cancel != null && cancel.get()) throw new QentimArtException("sync cancelled", -1, "ECANCELED");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(origin + basePath + "/" + rel))
                        .timeout(timeout).GET().build();
                HttpResponse<InputStream> resp = client.send(req, BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 300) {
                    try (InputStream in = resp.body()) {
                        in.readNBytes(8192);
                    }
                    throw new QentimArtException("GET " + rel + ": HTTP " + resp.statusCode(), resp.statusCode(), null);
                }
                // 按 max 设上限读取，防止被攻陷的公开静态源用超大响应体耗尽客户端内存。
                byte[] raw;
                try (InputStream in = resp.body()) {
                    raw = Wire.readCapped(in, max, "GET " + rel);
                }
                // 按 Content-Encoding 透明解压（CDN 常对 index.json 强制 gzip）。
                String enc = resp.headers().firstValue("Content-Encoding").orElse(null);
                return Wire.decodeBody(raw, enc, max);
            } catch (QentimArtException e) {
                if (e.status >= 0 && e.status < 500 && e.status != 429) throw e;
                last = e;
            } catch (Exception e) {
                last = e;
            }
            if (attempt < retries) LocalFs.sleep((long) (300 * Math.pow(2, attempt)));
        }
        if (last instanceof QentimArtException) throw (QentimArtException) last;
        throw new QentimArtException("GET " + rel + " failed: " + last, -1, "ENETWORK");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> index(AtomicBoolean cancel) {
        long now = System.currentTimeMillis();
        Map<String, Object> idx = index;
        if (idx != null && now - indexAt < 5000) return idx;
        idx = (Map<String, Object>) Json.parse(getBuffer("index.json", Wire.MAX_JSON, cancel));
        index = idx;
        indexAt = now;
        return idx;
    }

    public void fetchChunks(List<String> hashes, BiConsumer<String, byte[]> onChunk, Stats stats, AtomicBoolean cancel) {
        Pool.run(hashes, concurrency, h -> {
            byte[] buf = getBuffer("chunks/" + h.substring(0, 2) + "/" + h, Wire.MAX_BLOB_SIZE, cancel);
            byte[] raw = Wire.decodeBlob(h, buf);
            stats.downloadedWire.addAndGet(buf.length);
            stats.downloadedRaw.addAndGet(raw.length);
            onChunk.accept(h, raw);
        });
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> versionFromIndex(Map<String, Object> idx, String version, boolean prerelease) {
        String v = version;
        if ("latest".equals(version)) {
            Object o = prerelease ? idx.get("latestPrerelease") : idx.get("latest");
            v = o == null ? null : o.toString();
        }
        if (v == null || v.isEmpty()) throw new QentimArtException("project has no versions");
        Object versions = idx.get("versions");
        if (versions instanceof List) {
            for (Object o : (List<Object>) versions) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (v.equals(m.get("version"))) return m;
            }
        }
        throw new QentimArtException("version " + v + " not found", 404, null);
    }

    @Override
    public void close() {
        client.close();
    }
}
