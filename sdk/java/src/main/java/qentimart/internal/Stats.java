package qentimart.internal;

import java.util.concurrent.atomic.AtomicLong;

/** 同步过程中的线程安全字节计数。 */
public final class Stats {
    public final AtomicLong written = new AtomicLong();
    public final AtomicLong reusedLocal = new AtomicLong();
    public final AtomicLong downloadedRaw = new AtomicLong();
    public final AtomicLong downloadedWire = new AtomicLong();
}
