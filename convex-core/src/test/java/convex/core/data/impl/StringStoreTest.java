package convex.core.data.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Symbol;

/**
 * The intern store is process-wide and hit from every thread that creates a
 * string, keyword or symbol, so it must stay consistent under concurrent
 * interning. A plain HashMap corrupts when two threads intern at once and
 * every later lookup then recurses until StackOverflowError.
 */
public class StringStoreTest {

	@Test
	public void internIsIdempotentAcrossBothIndexes() {
		String s = "string-store-" + System.nanoTime();
		StringShort a = StringStore.intern(s);
		assertSame(a, StringStore.intern(s), "same String → same interned instance");
		assertSame(a, StringStore.intern(Strings.create(s)), "same AString → same interned instance");
		StringStore.Entry e = StringStore.get(s);
		assertNotNull(e);
		assertSame(e, StringStore.get(a.toBlob()), "both indexes resolve to one Entry");
		assertSame(e.getKeyword(), Keyword.create(s));
		assertSame(e.getSymbol(), Symbol.create(s));
	}

	@Test
	public void concurrentInterningStaysConsistent() throws Exception {
		final int threads = 8;
		final int distinct = 2000;
		final String prefix = "ss-conc-" + System.nanoTime() + "-";
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> tasks = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				final int offset = t;
				tasks.add(pool.submit(() -> {
					// Every thread interns the whole set in a different order,
					// alternating the String and AString entry points, so the
					// same strings race through both indexes at once.
					for (int i = 0; i < distinct; i++) {
						String s = prefix + ((i * 7 + offset) % distinct);
						StringShort a = ((i + offset) % 2 == 0)
							? StringStore.intern(s)
							: StringStore.intern(Strings.create(s));
						assertEquals(s, a.toString());
						assertNotNull(StringShort.create(s)); // the lock-free lookup path
					}
					return null;
				}));
			}
			for (Future<?> f : tasks) f.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		for (int i = 0; i < distinct; i++) {
			String s = prefix + i;
			StringStore.Entry e = StringStore.get(s);
			assertNotNull(e, "every string interned exactly once: " + s);
			StringShort a = e.getStringShort();
			assertSame(a, StringStore.intern(s));
			assertSame(e, StringStore.get(a.toBlob()), "blob index agrees with string index");
		}
	}
}
