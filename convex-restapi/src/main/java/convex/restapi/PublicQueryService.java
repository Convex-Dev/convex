package convex.restapi;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.peer.Server;

/**
 * Bounded execution service for public REST and MCP queries.
 *
 * <p>Queries execute on the request virtual thread against the latest immutable
 * Peer snapshot. They deliberately bypass the Peer {@code QueryHandler}, whose
 * queue must remain available for network queries and CAD data requests.</p>
 *
 * <p>The concurrency limit bounds how many CPU cores public queries may take
 * from consensus, not throughput — a single core evaluates queries fast enough
 * that a small slice of cores serves a very high query rate. A request waits up
 * to {@code maxWaitMillis} for a slot and only returns a {@code LOAD} result if
 * the lane is still saturated, so under normal load nobody waits and nobody is
 * rejected.</p>
 */
public final class PublicQueryService {

	/** Default public query execution ceiling, matching the query-watch ceiling. */
	public static final long MAX_QUERY_JUICE=100_000L;

	@FunctionalInterface
	interface QueryExecutor {
		Result execute(ACell form, Address address, long maxJuice) throws Exception;
	}

	private final QueryExecutor executor;
	private final Semaphore permits;
	private final long maxWaitMillis;
	private final long maxJuice;
	private final AtomicLong nextID=new AtomicLong();

	public PublicQueryService(Server server, RESTConfig config) {
		this((form,address,maxJuice) -> Result.fromContext(null,
				server.getPeer().executeQuery(form,address,maxJuice)),
				config.getMaxConcurrentQueries(),config.getQueryMaxWaitMillis(),config.getMaxQueryJuice());
	}

	PublicQueryService(QueryExecutor executor, int maxConcurrent, long maxWaitMillis, long maxJuice) {
		this.executor=Objects.requireNonNull(executor,"Query executor cannot be null");
		if (maxConcurrent<=0) throw new IllegalArgumentException("Maximum concurrent queries must be positive");
		if (maxWaitMillis<0) throw new IllegalArgumentException("Maximum query wait cannot be negative");
		if (maxJuice<0) throw new IllegalArgumentException("Maximum query Juice cannot be negative");
		this.permits=new Semaphore(maxConcurrent);
		this.maxWaitMillis=maxWaitMillis;
		this.maxJuice=maxJuice;
	}

	/**
	 * Executes a public query, waiting briefly for a free slot when the lane is
	 * momentarily full.
	 *
	 * @param form Query form
	 * @param address Address from which to execute, or {@code null}
	 * @return Query result, or a load result when the public lane stays saturated
	 */
	public Result execute(ACell form, Address address) {
		CVMLong id=CVMLong.create(nextID.getAndIncrement());
		boolean acquired;
		try {
			acquired=permits.tryAcquire(maxWaitMillis,TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.error(ErrorCodes.LOAD,"Interrupted while awaiting public query capacity")
					.withSource(SourceCodes.PEER)
					.withID(id);
		}
		if (!acquired) {
			return Result.error(ErrorCodes.LOAD,"Server busy: public query limit reached")
					.withSource(SourceCodes.PEER)
					.withID(id);
		}

		try {
			Result result=Objects.requireNonNull(executor.execute(form,address,maxJuice),
					"Query executor returned null");
			return result.withSource(SourceCodes.PEER).withID(id);
		} catch (Exception e) {
			return Result.fromException(e).withSource(SourceCodes.PEER).withID(id);
		} finally {
			permits.release();
		}
	}
}
