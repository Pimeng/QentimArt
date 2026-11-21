package qentimart.internal;

import qentimart.QentimArtException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 动态模式传输：走服务端 HTTP API。HttpClient 线程安全、自带连接池/keep-alive。 */
public final class HttpTransport implements AutoCloseable {
    private final HttpClient client;
    private final String origin;   // scheme://host[:port]
    private final String prefix;   // <basePath>/api/v1/projects/<project>
    private final String token;
    private final Duration timeout;
    final int retries;

    public static String sourceKey(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String portPart = port < 0 || (port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"))
                ? "" : ":" + port;
        String path = uri.getRawPath();
        return scheme + "://" + host + portPart + (path == null ? "" : path.replaceFirst("/++$", ""));
    }

    public HttpTransport(String server, String project, String token, long timeoutMs, int retries) {
        URI u;
        try {
            u = URI.create(server);
        } catch (RuntimeException e) {
            throw new QentimArtException("invalid server URL " + server);
        }
        String scheme = u.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || u.getHost() == null) {
            throw new QentimArtException("invalid server URL " + server);
        }
        String portPart = u.getPort() >= 0 ? ":" + u.getPort() : "";
        this.origin = scheme + "://" + u.getHost() + portPart;
        String base = u.getRawPath() == null ? "" : u.getRawPath();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.prefix = base + "/api/v1/projects/" + pctEncode(project);
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.retries = retries;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.timeout)
                .build();
    }

    public interface Attempt<T> {
        T run(int attempt) throws Exception;
    }

    /** 对网络错误、被截断的流以及 5xx 响应重试。 */
    public <T> T withRetry(Attempt<T> fn, AtomicBoolean cancel) {
        Throwable last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (cancel != null && cancel.get()) throw new QentimArtException("sync cancelled", -1, "ECANCELED");
            try {
                return fn.run(attempt);
            } catch (QentimArtException e) {
                if ((e.status >= 0 && e.status < 500) || "ECANCELED".equals(e.code) || "ECONFLICT".equals(e.code)) throw e;
                last = e;
            } catch (Exception e) {
                last = e;
            }
            if (attempt < retries) LocalFs.sleep((long) (300 * Math.pow(2, attempt)));
        }
        if (last instanceof QentimArtException) throw (QentimArtException) last;
        throw new QentimArtException("request failed after " + (retries + 1) + " attempts: " + last, -1, "ENETWORK");
    }

    private HttpResponse<InputStream> sendStream(String method, String path, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(origin + prefix + path)).timeout(timeout);
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> resp = client.send(b.build(), BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 300) {
            byte[] data;
            try (InputStream in = resp.body()) {
                data = in.readNBytes(64 * 1024); // 错误体只取前 64 KiB，避免恶意服务端用超大错误体耗尽内存
            }
            throw httpError(method, path, resp.statusCode(), data);
        }
        return resp;
    }

    public byte[] buffer(String method, String path, byte[] body) {
        return withRetry(n -> {
            HttpResponse<InputStream> r = sendStream(method, path, body, null);
            try (InputStream in = r.body()) {
                return Wire.readCapped(in, Wire.MAX_JSON, method + " " + path);
            }
        }, null);
    }

    /** 带大小上限读取的下载：响应体字节 + 一个响应头的值。 */
    public static final class Download {
        public final byte[] body;
        public final String header;

        Download(byte[] body, String header) {
            this.body = body;
            this.header = header;
        }
    }

    /** GET 一个响应，body 读取上限为 max 字节，并取出 headerName 指定的响应头（如 X-QentimArt-Version）。 */
    public Download download(String method, String path, long max, String headerName) {
        return withRetry(n -> {
            HttpResponse<InputStream> r = sendStream(method, path, null, null);
            byte[] body;
            try (InputStream in = r.body()) {
                body = Wire.readCapped(in, max, method + " " + path);
            }
            String h = headerName == null ? null : r.headers().firstValue(headerName).orElse(null);
            return new Download(body, h);
        }, null);
    }

    public Object json(String method, String path) {
        return Json.parse(buffer(method, path, null));
    }

    /** 发起一次流式请求（调用方负责读 body、关流、重试）。 */
    public HttpResponse<InputStream> stream(String method, String path, byte[] body) throws Exception {
        return sendStream(method, path, body, "application/octet-stream");
    }

    private static QentimArtException httpError(String method, String path, int status, byte[] data) {
        String msg = new String(data, StandardCharsets.UTF_8);
        try {
            Object o = Json.parse(msg);
            if (o instanceof Map && ((Map<?, ?>) o).get("error") != null) {
                msg = ((Map<?, ?>) o).get("error").toString();
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体，原样使用
        }
        return new QentimArtException(method + " " + path + ": HTTP " + status + ": " + msg, status, null);
    }

    /** 等价于 Python urllib.parse.quote(s, safe="")：只保留 unreserved 字符。 */
    public static String pctEncode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte v : s.getBytes(StandardCharsets.UTF_8)) {
            int c = v & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append((char) c);
            } else {
                b.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return b.toString();
    }

    @Override
    public void close() {
        client.close();
    }
}
