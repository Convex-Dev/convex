package convex.node;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import convex.core.data.ACell;

/**
 * Waits on a group's announce signals for a matching value, without polling.
 * Use it instead of a bare {@link LatticePropagator#nextAnnounce()} whenever an
 * earlier publication may still be in flight: that one would complete the future.
 */
final class Announcements {
	/** Announcements to pass over before giving up. */
	private static final int LIMIT=16;

	private Announcements() {
	}

	/**
	 * Completes with the first value the group announces after this call that
	 * matches. Each wait is registered on the completing thread, before the group
	 * can announce again, so no announcement is missed.
	 */
	static CompletableFuture<ACell> nextMatching(LatticePropagator group,Predicate<ACell> predicate) {
		CompletableFuture<ACell> result=new CompletableFuture<>();
		await(group,predicate,result,LIMIT);
		return result;
	}

	private static void await(LatticePropagator group,Predicate<ACell> predicate,
			CompletableFuture<ACell> result,int remaining) {
		group.nextAnnounce().whenComplete((value,error) -> {
			if (error!=null) {
				result.completeExceptionally(error);
				return;
			}
			boolean matches;
			try {
				matches=predicate.test(value);
			} catch (Throwable predicateFailure) {
				result.completeExceptionally(predicateFailure);
				return;
			}
			if (matches) {
				result.complete(value);
			} else if (remaining<=1) {
				result.completeExceptionally(new AssertionError("Expected announcement did not arrive"));
			} else {
				await(group,predicate,result,remaining-1);
			}
		});
	}
}
