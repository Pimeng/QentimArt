package qentimart.internal;

import qentimart.Manifest;
import qentimart.QentimArtException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** {@code <dir>/.qentimart} 中的本地状态（与 Node.js / Python SDK 格式相同，可互相接管）。 */
public final class State {
    private State() {
    }

    public static final String STATE_FILE = "state.json";
    public static final String MANIFEST_FILE = "manifest.json.gz";
    public static final String TMP_DIR = "tmp";
    public static final String LOCK_FILE = "lock";

    public static final class Read {
        public final Map<String, Object> state;   // null 表示无/不可用
        public final Manifest manifest;            // null 表示无/版本不匹配
        public final byte[] gz;                    // 当前版本清单原文

        Read(Map<String, Object> state, Manifest manifest, byte[] gz) {
            this.state = state;
            this.manifest = manifest;
            this.gz = gz;
        }
    }

    public static Read read(Path root) {
        Path sd = root.resolve(Wire.STATE_DIR);
        Map<String, Object> state = null;
        Manifest manifest = null;
        byte[] gz = null;
        try {
            Map<String, Object> s = Json.parseObject(Files.readAllBytes(sd.resolve(STATE_FILE)));
            Object fv = s.get("formatVersion");
            state = (fv instanceof Long && (Long) fv == 1L) ? s : null;
        } catch (IOException | RuntimeException e) {
            state = null;
        }
        if (state != null) {
            try {
                gz = Files.readAllBytes(sd.resolve(MANIFEST_FILE));
                manifest = Wire.parseManifest(gz);
                if (!Objects.equals(manifest.version, state.get("version"))) manifest = null;
            } catch (IOException | RuntimeException e) {
                manifest = null;
            }
        }
        return new Read(state, manifest, gz);
    }

    public static void write(Path root, Map<String, Object> state, byte[] gz) throws IOException {
        Path sd = root.resolve(Wire.STATE_DIR);
        Files.createDirectories(sd);
        LocalFs.writeAtomic(sd.resolve(MANIFEST_FILE), gz);
        LocalFs.writeAtomic(sd.resolve(STATE_FILE), Json.writeBytes(state, false));
    }

    /** 获取同步锁；返回释放函数。另一个活着的进程持锁时抛 ELOCKED。 */
    public static Runnable acquireLock(Path root) {
        Path sd = root.resolve(Wire.STATE_DIR);
        try {
            Files.createDirectories(sd);
        } catch (IOException e) {
            throw new QentimArtException("cannot create " + sd + ": " + e.getMessage(), e);
        }
        Path lock = sd.resolve(LOCK_FILE);
        Runnable release = () -> {
            try {
                Files.deleteIfExists(lock);
            } catch (IOException ignored) {
                // 忽略
            }
        };
        for (int i = 0; i < 2; i++) {
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("pid", LocalFs.currentPid());
                info.put("at", System.currentTimeMillis());
                ch.write(ByteBuffer.wrap(Json.writeBytes(info, false)));
                return release;
            } catch (FileAlreadyExistsException e) {
                boolean stale = true;
                try {
                    Map<String, Object> info = Json.parseObject(Files.readAllBytes(lock));
                    long pid = info.get("pid") instanceof Number ? ((Number) info.get("pid")).longValue() : 0;
                    if (pid != 0 && pid != LocalFs.currentPid() && LocalFs.pidAlive(pid)) stale = false;
                } catch (IOException | RuntimeException ex) {
                    // 无法解析 → 视为陈旧
                }
                if (!stale) throw new QentimArtException("another sync is running on " + root, -1, "ELOCKED");
                release.run();
            } catch (IOException e) {
                throw new QentimArtException("cannot lock " + root + ": " + e.getMessage(), e);
            }
        }
        throw new QentimArtException("could not lock " + root, -1, "ELOCKED");
    }
}
