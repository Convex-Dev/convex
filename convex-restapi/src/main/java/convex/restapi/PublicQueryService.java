package convex.restapi;

import java.util.Objects;
import java.util.concurrent.Semaphore;
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
 */
public final class PublicQueryService {

	/** Public queries are cheap and serial by default to bound CPU competition. */
	public static final int MAX_CONCURRENT_QUERIES=1;

	/** Public query execution ceiling, matching the query-watch ceiling. */
	public static final long MAX_QUERY_JUICE=100_000L;

	@FunctionalInterface
	interface QueryExecutor {
		Result execute(ACell form, Address address, long maxJuice) throws Exception;
	}

	private final QueryExecutor executor;
	private final Semaphore permits;
	private final long maxJuice;
	private final AtomicLong nextID=new AtomicLong();

	public PublicQueryService(Server server) {
		this((form,address,maxJuice) -> Result.fromContext(null,
				server.getPeer().executeQuery(form,address,maxJuice)),
				MAX_CONCURRENT_QUERIES,MAX_QUERY_JUICE);
	}

	PublicQueryService(QueryExecutor executor, int maxConcurrent, long maxJuice) {
		this.executor=Objects.requireNonNull(executor,"Query executor cannot be null");
		if (maxConcurrent<=0) throw new IllegalArgumentException("Maximum concurrent queries must be positive");
		if (maxJuice<0) throw new IllegalArgumentException("Maximum query Juice cannot be negative");
		this.permits=new Semaphore(maxConcurrent);
		this.maxJuice=maxJuice;
	}

	/**
	 * Executes a public query if capacity is immediately available.
	 *
	 * @param form Query form
	 * @param address Address from which to execute, or {@code null}
	 * @return Query result, or a load result when the public lane is occupied
	 */
	public Result execute(ACell form, Address address) {
		CVMLong id=CVMLong.create(nextID.getAndIncrement());
		if (!permits.tryAcquire()) {
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
