package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.prim.CVMLong;
import convex.restapi.api.AGenericAPI;
import io.javalin.config.RoutesConfig;

public class PublicQueryServiceTest {

	@Test
	public void testImmediateRejectionAndPermitRelease() throws Exception {
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		AtomicInteger executions=new AtomicInteger();
		AtomicLong observedJuice=new AtomicLong(-1);

		PublicQueryService service=new PublicQueryService((form,address,maxJuice) -> {
			executions.incrementAndGet();
			observedJuice.set(maxJuice);
			entered.countDown();
			if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test query was not released");
			return Result.value(CVMLong.ONE);
		},1,123_456L);

		CompletableFuture<Result> first=CompletableFuture.supplyAsync(() -> service.execute(CVMLong.ZERO,null));
		assertTrue(entered.await(5,TimeUnit.SECONDS),"First query did not acquire the permit");

		Result busy=service.execute(CVMLong.ZERO,null);
		assertEquals(ErrorCodes.LOAD,busy.getErrorCode());
		assertEquals(1,executions.get(),"Rejected query must not reach the executor");

		release.countDown();
		assertFalse(first.get(5,TimeUnit.SECONDS).isError());
		assertEquals(123_456L,observedJuice.get());

		Result afterRelease=service.execute(CVMLong.ZERO,null);
		assertFalse(afterRelease.isError());
		assertEquals(2,executions.get(),"Permit should be reusable after completion");
	}

	@Test
	public void testPermitReleasedAfterExecutorFailure() {
		AtomicInteger executions=new AtomicInteger();
		PublicQueryService service=new PublicQueryService((form,address,maxJuice) -> {
			if (executions.getAndIncrement()==0) throw new IllegalStateException("Expected test failure");
			return Result.value(CVMLong.ONE);
		},1,PublicQueryService.MAX_QUERY_JUICE);

		assertTrue(service.execute(CVMLong.ZERO,null).isError());
		assertFalse(service.execute(CVMLong.ZERO,null).isError(),
				"Executor failure must not retain the public query permit");
	}

	@Test
	public void testLoadResultMapsToServiceUnavailable() {
		AGenericAPI api=new AGenericAPI() {
			@Override
			public void addRoutes(RoutesConfig routes) {
			}
		};
		Result busy=Result.error(ErrorCodes.LOAD,"busy");
		assertEquals(503,api.statusForResult(busy));
	}
}
