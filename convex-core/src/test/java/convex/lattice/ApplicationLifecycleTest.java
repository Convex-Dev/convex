package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;

/** Application lifecycle properties without a parallel ALattice definition. */
class ApplicationLifecycleTest {
	private static final Keyword NESTED=Keyword.intern("nested");
	private static final Keyword ITEMS=Keyword.intern("items");
	private static final Keyword OTHER=Keyword.intern("other");

	private static class Values extends ALatticeComponent<ASet<CVMLong>> {
		Values(AForkableCursor<ASet<CVMLong>> cursor) { super(cursor); }
		@Override public ASet<CVMLong> merge(ASet<CVMLong> incoming) {
			return cursor().updateAndGet(own -> union(own,incoming));
		}
		@Override protected ASet<CVMLong> mergePublication(ASet<CVMLong> own,ASet<CVMLong> other) {
			return union(own,other);
		}
	}

	private static ASet<CVMLong> union(ASet<CVMLong> own,ASet<CVMLong> other) {
		if (other==null) return own;
		return own==null?other:own.includeAll(other);
	}

	/** Preparation is ordered through nested applications; failure does not begin child publication. */
	@Test void nestedRegionsPrepareBeforePublishingChildren() {
		var host=LatticeHost.create(Index.<Keyword,ACell>of(OTHER,CVMLong.ONE),null);
		List<String> events=new ArrayList<>();
		boolean[] fail={true};
		var root=new Regions(host) {
			@Override protected void beforeSync() {
				events.add("root");
				assertThrows(IllegalStateException.class,() -> add(OTHER,this));
				if (fail[0]) throw new IllegalStateException("preparation failed");
			}
		};
		AForkableCursor<Index<Keyword,ACell>> nestedPath=host.cursor().path(NESTED);
		var nested=new Regions(nestedPath) {
			@Override protected void beforeSync() { events.add("nested"); }
		};
		var values=new Values(nestedPath.withDefault(Index.none()).<ASet<CVMLong>>path(ITEMS).fork()) {
			@Override public ASet<CVMLong> sync() {
				events.add("values");
				return super.sync();
			}
		};
		nested.add(ITEMS,values);
		root.add(NESTED,nested);
		host.addApplication(root);
		host.mergeAt(Sets.of(1),NESTED,ITEMS);
		assertTrue(events.isEmpty(),"Delivery does not run publication lifecycle");
		var before=host.cursor().get();
		assertThrows(IllegalStateException.class,root::sync);
		assertSame(before,host.cursor().get());
		assertEquals(List.of("root"),events);
		fail[0]=false;
		events.clear();
		root.sync();
		assertEquals(List.of("root","nested","values"),events);
		assertEquals(Index.of(OTHER,CVMLong.ONE,NESTED,Index.of(ITEMS,Sets.of(1))),host.cursor().get());
		before=host.cursor().get();
		root.sync();
		assertSame(before,host.cursor().get(),"Repeated lifecycle does not rewrite unchanged state");
	}

	/** Children publish into a staged application before its own boundary becomes visible. */
	@Test void childFailureDefersApplicationPublicationAndCanRetry() {
		var host=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),null);
		var app=new ALatticeApplication<ASet<CVMLong>>(host) {
			final Values child=new Values(cursor().fork());
			boolean fail=true;
			@Override protected void beforeSync() { child.merge(Sets.of(1)); }
			@Override protected void syncChildren() {
				child.sync();
				assertTrue(host.cursor().get().isEmpty());
				if (fail) throw new IllegalStateException("later child failed");
			}
			@Override public ASet<CVMLong> merge(ASet<CVMLong> incoming) { return child.merge(incoming); }
			@Override protected ASet<CVMLong> mergePublication(ASet<CVMLong> own,ASet<CVMLong> other) {
				return union(own,other);
			}
		};
		assertThrows(IllegalStateException.class,app::sync);
		assertTrue(host.cursor().get().isEmpty());
		assertEquals(Sets.of(1),app.cursor().get(),"Completed child publication remains in application working state");
		app.fail=false;
		assertEquals(Sets.of(1),app.sync());
		assertEquals(Sets.of(1),host.cursor().get());
	}

	/** Two independent applications reconcile concurrent publication through their own value policy. */
	@Test void concurrentApplicationsPreserveBothPublications() throws Exception {
		var host=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),null);
		var ready=new CountDownLatch(2);
		var publish=new CountDownLatch(1);
		class App extends ALatticeApplication<ASet<CVMLong>> {
			App() { super(host); }
			@Override public ASet<CVMLong> merge(ASet<CVMLong> incoming) {
				return cursor().updateAndGet(own -> union(own,incoming));
			}
			@Override protected ASet<CVMLong> mergePublication(ASet<CVMLong> own,ASet<CVMLong> other) {
				return union(own,other);
			}
			@Override protected void beforeSync() {
				ready.countDown();
				try { assertTrue(publish.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
			}
		}
		var first=new App();
		var second=new App();
		first.merge(Sets.of(1));
		second.merge(Sets.of(2));
		try (var executor=Executors.newFixedThreadPool(2)) {
			var a=executor.submit(first::sync);
			var b=executor.submit(second::sync);
			try { assertTrue(ready.await(10,TimeUnit.SECONDS)); }
			finally { publish.countDown(); }
			a.get(10,TimeUnit.SECONDS);
			b.get(10,TimeUnit.SECONDS);
		}
		assertEquals(Sets.of(1,2),host.cursor().get());
	}
}
