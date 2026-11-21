package qentimart.internal;

import qentimart.Manifest;
import qentimart.QentimArtException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@code <dir>/.qentimart} 中的本地状态（与 Node.js / Python SDK 格式相同，可互相接管）。 */
public final class State {
    private State() {
    }

    public static final String STATE_FILE = "state.json";
    public static final String MANIFEST_FILE = "manifest.json.gz";
    public static final String TMP_DIR = "tmp";
    public static final String LOCK_FILE = "lock";
    private static final long INCOMPLETE_LOCK_GRACE_MS = 60_000;

    private static Path stateDir(Path root, boolean create) {
        Path sd = root.resolve(Wire.STATE_DIR);
        try {
            if (create) {
                try {
                    Files.createDirectory(sd);
                } catch (FileAlreadyExistsException ignored) {
                    // Validate the existing entry below.
                }
            }
            LocalFs.requireDirectory(sd);
        } catch (NoSuchFileException e) {
            if (create) throw new QentimArtException("cannot create state directory", e);
        } catch (IOException e) {
            throw new QentimArtException("cannot access state directory", e);
        }
        return sd;
    }

    public static Path tmpDir(Path root) throws IOException {
        Path tmp = stateDir(root, false).resolve(TMP_DIR);
        try {
            LocalFs.requireDirectory(tmp);
        } catch (NoSuchFileException ignored) {
            // A clean sync starts without a temporary directory.
        }
        return tmp;
    }

    private static byte[] readRegular(Path file, long limit) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new QentimArtException("unsafe state file: " + file, -1, "EUNSAFE");
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return Wire.readCapped(in, limit, file.getFileName().toString());
        }
    }

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
        Path sd = stateDir(root, false);
        Map<String, Object> state = null;
        Manifest manifest = null;
        byte[] gz = null;
        try {
            Map<String, Object> s = Json.parseObject(readRegular(sd.resolve(STATE_FILE), Wire.MAX_MANIFEST_RAW));
            Object fv = s.get("formatVersion");
            state = (fv instanceof Long && (Long) fv == 1L && s.get("files") instanceof Map<?, ?>) ? s : null;
        } catch (IOException | RuntimeException e) {
            if (e instanceof QentimArtException qe && "EUNSAFE".equals(qe.code)) throw qe;
            state = null;
        }
        if (state != null) {
            try {
                gz = readRegular(sd.resolve(MANIFEST_FILE), Wire.MAX_MANIFEST_GZ);
                manifest = Wire.parseManifest(gz);
                if (!Objects.equals(manifest.version, state.get("version"))) manifest = null;
            } catch (IOException | RuntimeException e) {
                if (e instanceof QentimArtException qe && "EUNSAFE".equals(qe.code)) throw qe;
                manifest = null;
            }
        }
        return new Read(state, manifest, gz);
    }

    public static void write(Path root, Map<String, Object> state, byte[] gz) throws IOException {
        Path sd = stateDir(root, true);
        LocalFs.writeAtomic(sd.resolve(MANIFEST_FILE), gz);
        LocalFs.writeAtomic(sd.resolve(STATE_FILE), Json.writeBytes(state, false));
    }

    // 同一进程内先按目录互斥，不必等另一个线程将 PID 写入锁文件。
    private static final Set<String> IN_PROCESS = ConcurrentHashMap.newKeySet();

    /** 获取同步锁；返回释放函数。另一个活着的进程（或本进程另一处）持锁时抛 ELOCKED。 */
    public static Runnable acquireLock(Path root) {
        String key = root.toAbsolutePath().normalize().toString();
        if (!IN_PROCESS.add(key)) {
            throw new QentimArtException("another sync is running on " + root + " (this process)", -1, "ELOCKED");
        }
        try {
            Path sd = stateDir(root, true);
            Path lock = sd.resolve(LOCK_FILE);
            Runnable release = () -> {
                try {
                    LocalFs.requireDirectory(sd);
                    Files.deleteIfExists(lock);
                } catch (IOException ignored) {
                    // 忽略
                } finally {
                    IN_PROCESS.remove(key);
                }
            };
            for (int i = 0; i < 2; i++) {
                try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("pid", LocalFs.currentPid());
                    info.put("at", System.currentTimeMillis());
                    ByteBuffer record = ByteBuffer.wrap(Json.writeBytes(info, false));
                    while (record.hasRemaining()) ch.write(record);
                    return release;
                } catch (FileAlreadyExistsException e) {
                    long pid = 0;
                    try {
                        Object record = Json.parse(readRegular(lock, 4096));
                        if (record instanceof Map<?, ?> info && info.get("pid") instanceof Long value
                                && value > 0 && value <= Integer.MAX_VALUE) pid = value;
                    } catch (NoSuchFileException ex) {
                        continue;
                    } catch (IllegalArgumentException ignored) {
                        // An interrupted write can leave a partial lock record.
                    } catch (QentimArtException ex) {
                        if (!"ETOOLARGE".equals(ex.code)) throw ex;
                    }
                    boolean stale;
                    try {
                        stale = pid > 0 ? !LocalFs.pidAlive(pid)
                                : System.currentTimeMillis() - Files.getLastModifiedTime(lock, LinkOption.NOFOLLOW_LINKS).toMillis() > INCOMPLETE_LOCK_GRACE_MS;
                    } catch (NoSuchFileException ex) {
                        continue;
                    }
                    if (!stale) throw new QentimArtException("another sync is running on " + root, -1, "ELOCKED");
                    Files.deleteIfExists(lock);
                }
            }
            throw new QentimArtException("could not lock " + root, -1, "ELOCKED");
        } catch (IOException e) {
            IN_PROCESS.remove(key);
            throw new QentimArtException("cannot lock " + root + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            IN_PROCESS.remove(key);
            throw e;
        }
    }
}
