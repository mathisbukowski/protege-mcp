package dev.protegemcp;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** Runs work on the Swing thread, because the Protégé model is not thread safe. */
final class Gui {
    static final long DEFAULT_TIMEOUT_MS = 60_000;

    private static final int PENDING = 0;
    private static final int STARTED = 1;
    private static final int ABANDONED = 2;

    private Gui() {
    }

    static <T> T call(Callable<T> work) throws Exception {
        return call(DEFAULT_TIMEOUT_MS, work);
    }

    static <T> T call(long timeoutMs, Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        AtomicInteger state = new AtomicInteger(PENDING);
        FutureTask<T> task = new FutureTask<>(() -> {
            if (!state.compareAndSet(PENDING, STARTED)) {
                return null;
            }
            return work.call();
        });
        SwingUtilities.invokeLater(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (state.compareAndSet(PENDING, ABANDONED)) {
                throw new ToolError("Protégé UI thread is busy. The request was cancelled and changed nothing.");
            }
            throw new ToolError("Timed out, but the operation is still running in Protégé. "
                    + "Check `status` before retrying.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}
