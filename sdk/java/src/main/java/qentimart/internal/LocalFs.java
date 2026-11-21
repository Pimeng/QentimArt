package qentimart.internal;

import qentimart.QentimArtException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 本地文件系统操作：扫描、哈希、定位读写、原子替换、目录字节统计、pid 存活判断。 */
public final class LocalFs {
    private LocalFs() {
    }

    public record LocalFile(long size, long mtimeMs) {
    }

    public record Scan(Map<String, LocalFile> files, Set<String> dirs, Set<String> special) {
    }

    static boolean realDirectory(Path p, BasicFileAttributes a) throws IOException {
        if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) return false;
        // Junctions and short-name aliases can look like ordinary directories on Windows.
        return java.io.File.separatorChar != '\\'
                || p.toRealPath().equals(p.getParent().toRealPath().resolve(p.getFileName()));
    }

    static void requireDirectory(Path p) throws IOException {
        if (!realDirectory(p, Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) {
            throw new QentimArtException("unsafe directory: " + p, -1, "EUNSAFE");
        }
    }

    static Path checkedParent(Path root, String rel) throws IOException {
        for (int slash = rel.indexOf('/'); slash >= 0; slash = rel.indexOf('/', slash + 1)) {
            requireDirectory(root.resolve(rel.substring(0, slash)));
        }
        return root.resolve(rel);
    }

    /** 列出 root 下的普通文件（跳过顶层 .qentimart）与所有目录；路径用 '/' 分隔，相对于 root。 */
    public static Scan scanDir(Path root) {
        Map<String, LocalFile> files = new HashMap<>();
        Set<String> dirs = new HashSet<>();
        Set<String> special = new HashSet<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, ""});
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            Path absDir = (Path) top[0];
            String rel = (String) top[1];
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(absDir)) {
                for (Path e : ds) {
                    String name = e.getFileName().toString();
                    if (rel.isEmpty() && name.equalsIgnoreCase(Wire.STATE_DIR)) continue;
                    String r = rel.isEmpty() ? name : rel + "/" + name;
                    BasicFileAttributes a;
                    try {
                        a = Files.readAttributes(e, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException ex) {
                        continue;
                    }
                    if (realDirectory(e, a)) {
                        dirs.add(r);
                        stack.push(new Object[]{e, r});
                    } else if (a.isRegularFile()) {
                        files.put(r, new LocalFile(a.size(), a.lastModifiedTime().toMillis()));
                    } else {
                        special.add(r);
                    }
                }
            } catch (NoSuchFileException ex) {
                // 目录在扫描过程中消失，跳过
            } catch (IOException ex) {
                throw new QentimArtException("scan failed at " + absDir + ": " + ex.getMessage(), ex);
            }
        }
        return new Scan(files, dirs, special);
    }

    public static long mtimeMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            throw new QentimArtException("stat failed: " + p, e);
        }
    }

    public static String hashFile(Path p) throws IOException {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file: " + p);
        MessageDigest md = Wire.sha256();
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return Wire.hex(md.digest());
    }

    public static String hashFile(Path root, String rel) throws IOException {
        return hashFile(checkedParent(root, rel));
    }

    /** 支持定位读写的文件句柄（FileChannel 的位置型读写线程安全，不动通道游标）。 */
    public static final class PositionedFile implements AutoCloseable {
        private final FileChannel ch;

        private PositionedFile(FileChannel ch) {
            this.ch = ch;
        }

        public static PositionedFile open(Path path, boolean write, long size) throws IOException {
            FileChannel ch;
            if (write) {
                ch = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } else {
                ch = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            }
            return new PositionedFile(ch);
        }

        public void writeAt(long offset, byte[] data) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(data);
            long off = offset;
            while (b.hasRemaining()) {
                off += ch.write(b, off);
            }
        }

        public byte[] readAt(long offset, int size) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(size);
            long off = offset;
            while (b.hasRemaining()) {
                int n = ch.read(b, off);
                if (n < 0) break;
                off += n;
            }
            if (b.position() == size) return b.array();
            return Arrays.copyOf(b.array(), b.position());
        }

        @Override
        public void close() {
            try {
                ch.close();
            } catch (IOException ignored) {
                // 忽略关闭错误
            }
        }
    }

    /** 带重试的原子替换，应对 Windows 上短暂的共享冲突。 */
    public static void replaceRetry(Path src, Path dst) throws IOException {
        int attempts = 8;
        for (int i = 0; ; i++) {
            try {
                try {
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException amns) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                // AccessDeniedException（Windows 共享冲突）等可重试的文件系统错误
                if (i >= attempts) throw e;
                sleep(50L * (1L << Math.min(i, 5)));
            }
        }
    }

    public static void writeAtomic(Path path, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, data, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            replaceRetry(tmp, path);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static long dirBytes(Path dir) {
        if (!Files.exists(dir)) return 0;
        long[] n = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    return realDirectory(d, a) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    n[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 尽力而为
        }
        return n[0];
    }

    public static void pruneEmptyDirs(Path root, String rel) {
        int slash = rel.lastIndexOf('/');
        String d = slash < 0 ? "" : rel.substring(0, slash);
        while (!d.isEmpty()) {
            try {
                Files.delete(root.resolve(d));
            } catch (IOException e) {
                return; // 非空或出错即停止
            }
            int s = d.lastIndexOf('/');
            d = s < 0 ? "" : d.substring(0, s);
        }
    }

    public static void rmTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                requireDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static boolean pidAlive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static long currentPid() {
        return ProcessHandle.current().pid();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
