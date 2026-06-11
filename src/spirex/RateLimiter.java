package spirex;

/**
 * Global request pacer. Combines the -rl (per second), -rlm (per minute), and
 * -rd (fixed delay) limits into a single minimum interval between request starts.
 * Thread-safe: every worker calls {@link #acquire()} immediately before fetching.
 */
public class RateLimiter {

    private final long minIntervalNanos;
    private long nextAllowed;

    public RateLimiter(int perSecond, int perMinute, int delaySeconds) {
        long interval = 0;
        if (perSecond > 0) {
            interval = Math.max(interval, 1_000_000_000L / perSecond);
        }
        if (perMinute > 0) {
            interval = Math.max(interval, 60_000_000_000L / perMinute);
        }
        if (delaySeconds > 0) {
            interval = Math.max(interval, delaySeconds * 1_000_000_000L);
        }
        this.minIntervalNanos = interval;
        this.nextAllowed = System.nanoTime();
    }

    /** Block until the caller is allowed to send the next request. */
    public void acquire() {
        if (minIntervalNanos <= 0) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long start = Math.max(now, nextAllowed);
            nextAllowed = start + minIntervalNanos;
            waitNanos = start - now;
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
