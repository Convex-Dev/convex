package convex.api;

/**
 * Server Event Interface. The server will post events to this the callback.
 *
 */
public class AcquireStatus {

    protected long requestCount;
    protected long queueRequestCount;
    protected long resetCount;
    protected long missingCount;
    protected long maxMissingCount;
    protected double percentLeft;

    public AcquireStatus(long requestCount, long queueRequestCount, long resetCount, long missingCount, long maxMissingCount) {
        this.requestCount = requestCount;
        this.queueRequestCount = queueRequestCount;
        this.resetCount = resetCount;
        this.missingCount = missingCount;
        this.maxMissingCount = maxMissingCount;
        this.percentLeft = ((double) missingCount / (double) maxMissingCount)  * 100;
    }

    public long getRequestCount() {
        return this.requestCount;
    }

    public long getQueueRequestCount() {
        return this.queueRequestCount;
    }

    public long getResetCount() {
        return this.resetCount;
    }

    public long getMissingCount() {
        return this.missingCount;
    }

    public long getMaxMissingCount() {
        return this.maxMissingCount;
    }

    public double getPercentLeft() {
        return this.percentLeft;
    }


}
