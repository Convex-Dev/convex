package convex.restapi.handler;

import io.javalin.http.Handler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.naming.ServiceUnavailableException;

import convex.restapi.api.ABaseAPI;

public class ConcurrentLimit {
	
	private Semaphore semaphore;
	private long timeout = ABaseAPI.BUSY_TIMEOUT;

	public ConcurrentLimit(int maxRequests) {
		this.semaphore = new Semaphore(maxRequests);
	}
	
	public ConcurrentLimit(int maxRequests, long timeoutMillis) {
		this.semaphore = new Semaphore(maxRequests);
		this.timeout= timeoutMillis;
	}
	
	public Handler handler(Handler delegate) {
        return ctx -> {
            if (!semaphore.tryAcquire(timeout , TimeUnit.MILLISECONDS)) {
                throw new ServiceUnavailableException("Server busy: too many requests");
            }
            try {
                delegate.handle(ctx);
            } finally {
                semaphore.release();
            }
        };
    }
}
