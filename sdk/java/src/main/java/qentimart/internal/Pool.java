package qentimart.internal;

import qentimart.QentimArtException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 用固定线程池对集合并行执行；重新抛出第一个异常（取消其余任务）。等价于 Python 的 run_pool。 */
public final class Pool {
    private Pool() {
    }

    public interface Task<T> {
        void run(T item) throws Exception;
    }

    public static <T> void run(Collection<T> items, int workers, Task<T> fn) {
        List<T> list = new ArrayList<>(items);
        if (list.isEmpty()) return;
        if (workers <= 1 || list.size() == 1) {
            for (T it : list) call(fn, it);
            return;
        }
        int n = Math.min(workers, list.size());
        ExecutorService ex = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "qentimart-pool");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(list.size());
        try {
            for (T it : list) futures.add(ex.submit(() -> call(fn, it)));
            Throwable first = null;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (first == null) {
                        first = e.getCause();
                        for (Future<?> g : futures) g.cancel(true);
                    }
                } catch (CancellationException ignored) {
                    // 被上面的 cancel 取消，忽略
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (first == null) first = e;
                }
            }
            if (first != null) throwUnchecked(first);
        } finally {
            ex.shutdownNow();
        }
    }

    private static <T> void call(Task<T> fn, T it) {
        try {
            fn.run(it);
        } catch (Exception e) {
            throwUnchecked(e);
        }
    }

    static void throwUnchecked(Throwable t) {
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        throw new QentimArtException(t.getMessage() == null ? t.toString() : t.getMessage(), t);
    }
}
