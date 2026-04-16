package convex.store;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.ACachedStore;
import convex.core.store.CacheStats;
import convex.etch.Etch;
import convex.etch.EtchStore;

/**
 * Interactive demo for the {@link ACachedStore} two-tier cache.
 *
 * <p>Backs onto a real on-disk Etch file at a persistent location so re-runs can
 * skip the (expensive) population step. The store path resolves to
 * {@code ${user.home}/.convex-cache-demo/store-${profile}.etch}, or you can
 * override via env var {@code STORE_DEMO_FILE}. Deliberately not under
 * {@code java.io.tmpdir} — Windows cleanup periodically purges that, and the
 * huge profile takes 30+ minutes to populate.</p>
 *
 * <p>Population sentinel: total cell count is written as the store's root data
 * (a {@link CVMLong}). On re-run, if the sentinel matches the expected count we
 * assume the store is populated and skip straight to measurement. Delete the
 * file to force re-population.</p>
 *
 * <p>Workload: tiered access pattern with three frequency tiers (hot/common/rare).
 * Reads dispatch to tiers via a fixed mix (default 70/25/5). Per-tier hit rates
 * are reported via focused passes after the mixed-workload measurement.</p>
 *
 * <p>Reads use {@link EtchStore#refForHash(Hash)}+getValue() — the production
 * path. Counters in {@link ACachedStore} cover both checkCache (L1/L2 fast path)
 * and decode (post-disk-read), so every read increments exactly one counter.</p>
 *
 * <p>Profiles:</p>
 * <pre>
 *   small  (default): 1k / 10k / 100k     ≈ 16 MB Etch, populates in ~10s
 *   big              : 1k / 50k / 500k    ≈ 80 MB Etch, populates in ~1min
 *   huge             : 1k / 100k / 10M    ≈ 1.5 GB Etch, populates in ~30min
 * </pre>
 *
 * Usage:
 * <pre>
 *   mvn -pl convex-core exec:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=convex.store.StoreCacheDemo \
 *     -Dexec.args="small"
 * </pre>
 */
public class StoreCacheDemo {

	private static final int L1_SIZE = 10000;

	/** Mix: 70% hot, 25% common, 5% rare. */
	private static final double P_HOT = 0.70;
	private static final double P_COMMON = 0.25;

	/** Reads in the mixed-workload measurement. */
	private static final int MIX_READS = 500_000;

	/** Sample size for focused passes on the rare tier. */
	private static final int RARE_SAMPLE = 5_000;

	enum Profile {
		SMALL(1_000, 10_000, 100_000, 1024),
		BIG  (1_000, 50_000, 500_000, 1024),
		// HUGE uses 256B payload to keep Etch file ≈ 5 GB instead of ≈ 20 GB.
		// 256B is still well above MAX_EMBEDDED_LENGTH and SHA-256 cost dominates decode.
		HUGE (1_000, 100_000, 10_000_000, 256);

		final int hot, common, rare;
		final int payload;
		Profile(int hot, int common, int rare, int payload) {
			this.hot = hot; this.common = common; this.rare = rare; this.payload = payload;
		}
		long total() { return (long) hot + common + rare; }
	}

	// ------------------------------------------------------------------
	// Store factory
	// ------------------------------------------------------------------

	/**
	 * Override to plug in a custom {@link ACachedStore} subclass. The default
	 * uses {@link EtchStore} backed by a persistent file.
	 */
	protected EtchStore openStore(File file, boolean enableL2) throws IOException {
		return new EtchStore(Etch.create(file), enableL2);
	}

	private static File storeFile(Profile p) {
		String override = System.getenv("STORE_DEMO_FILE");
		if (override != null && !override.isEmpty()) return new File(override);
		// Deliberately NOT java.io.tmpdir — we don't want OS cleanup nuking populated stores
		// (huge profile takes 30+ min to populate).
		File dir = new File(System.getProperty("user.home"), ".convex-cache-demo");
		if (!dir.exists()) dir.mkdirs();
		return new File(dir, "store-" + p.name().toLowerCase() + ".etch");
	}

	// ------------------------------------------------------------------
	// Population
	// ------------------------------------------------------------------

	/**
	 * Fills three Hash[] arrays by deterministically generating non-embedded blobs
	 * from per-tier seeds. If {@code persist} is true, also writes each cell to
	 * the store. Either way the hashes are computed in-process — they always
	 * match what's in (or going into) the store, so we can refForHash later.
	 */
	private static void buildTier(EtchStore store, Hash[] out, long seed, int payload, boolean persist) throws IOException {
		Random r = new Random(seed);
		for (int i = 0; i < out.length; i++) {
			Blob b = Blob.createRandom(r, payload);
			out[i] = b.getHash();
			if (persist) b.getRef().persist(store);
		}
	}

	private static boolean isPopulated(EtchStore store, long expectedCount) throws IOException {
		ACell root = store.getRootData();
		if (!(root instanceof CVMLong)) return false;
		return ((CVMLong) root).longValue() == expectedCount;
	}

	private void populateIfNeeded(EtchStore store, Profile p,
			Hash[] hot, Hash[] common, Hash[] rare) throws IOException {
		long expected = p.total();
		boolean populated = isPopulated(store, expected);

		long t0 = System.nanoTime();
		buildTier(store, hot,    0x100L, p.payload, !populated);
		buildTier(store, common, 0x200L, p.payload, !populated);
		buildTier(store, rare,   0x300L, p.payload, !populated);
		long ms = (System.nanoTime() - t0) / 1_000_000;

		if (!populated) {
			store.setRootData(CVMLong.create(expected));
			System.out.printf("Populated %d cells in %d ms (%.1f cells/ms)%n",
					expected, ms, (double) expected / Math.max(1, ms));
		} else {
			System.out.printf("Reusing populated store (%d cells), hashes derived in %d ms%n",
					expected, ms);
		}
	}

	// ------------------------------------------------------------------
	// Measurement
	// ------------------------------------------------------------------

	/** Aggregated result from one (profile, L2-on/off) run, used to print summary. */
	private static final class RunResult {
		final boolean l2;
		final CacheStats mix;
		final long mixNs;
		final TierResult hot, common, rare;

		RunResult(boolean l2, CacheStats mix, long mixNs,
				TierResult hot, TierResult common, TierResult rare) {
			this.l2 = l2; this.mix = mix; this.mixNs = mixNs;
			this.hot = hot; this.common = common; this.rare = rare;
		}
	}

	private static final class TierResult {
		final int reads;
		final long ns;
		final CacheStats stats;
		TierResult(int reads, long ns, CacheStats stats) {
			this.reads = reads; this.ns = ns; this.stats = stats;
		}
		double usPerRead() { return (ns / 1e3) / Math.max(1, reads); }
		double readsPerMs() { return reads * 1e6 / Math.max(1, ns); }
	}

	private RunResult runProfile(Profile p, boolean enableL2) throws IOException, BadFormatException {
		File file = storeFile(p);
		System.out.println();
		System.out.println("############################################################");
		System.out.printf("# Profile: %s (hot=%d, common=%d, rare=%d), payload=%d B%n",
				p.name(), p.hot, p.common, p.rare, p.payload);
		System.out.printf("# L2: %s   File: %s%n", enableL2 ? "on" : "off", file);
		System.out.println("############################################################");

		try (EtchStore store = openStore(file, enableL2)) {
			Hash[] hot = new Hash[p.hot];
			Hash[] common = new Hash[p.common];
			Hash[] rare = new Hash[p.rare];

			populateIfNeeded(store, p, hot, common, rare);

			// Pin hot tier — production code typically holds these (consensus state etc.).
			List<ACell> pinned = new ArrayList<>(p.hot);
			for (Hash h : hot) pinned.add(store.refForHash(h).getValue());

			// Mixed-workload pass
			Random r = new Random(0xACCE55L);
			int[] tierReads = new int[3];
			store.resetCacheStats();
			long t0 = System.nanoTime();
			for (int i = 0; i < MIX_READS; i++) {
				double u = r.nextDouble();
				Hash h;
				if (u < P_HOT) {
					h = hot[r.nextInt(p.hot)]; tierReads[0]++;
				} else if (u < P_HOT + P_COMMON) {
					h = common[r.nextInt(p.common)]; tierReads[1]++;
				} else {
					h = rare[r.nextInt(p.rare)]; tierReads[2]++;
				}
				Ref<?> ref = store.refForHash(h);
				if (ref == null) throw new IllegalStateException("missing hash " + h);
				ref.getValue();
			}
			long mixNs = System.nanoTime() - t0;
			CacheStats mix = store.getCacheStats();

			// Per-tier focused passes (reset between)
			TierResult hotR    = focusedPass(store, hot,    hot.length);
			TierResult commonR = focusedPass(store, common, Math.min(common.length, 10_000));
			TierResult rareR   = focusedPass(store, rare,   Math.min(rare.length, RARE_SAMPLE));

			report(p, mix, mixNs, tierReads, hotR, commonR, rareR);

			pinned.size(); // retain
			return new RunResult(enableL2, mix, mixNs, hotR, commonR, rareR);
		}
	}

	private static TierResult focusedPass(EtchStore store, Hash[] hashes, int n) throws BadFormatException {
		Random r = new Random(0xF0C05L);
		store.resetCacheStats();
		long t0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			Hash h = hashes[r.nextInt(hashes.length)];
			Ref<?> ref = store.refForHash(h);
			if (ref == null) throw new IllegalStateException("missing hash " + h);
			ref.getValue();
		}
		long ns = System.nanoTime() - t0;
		return new TierResult(n, ns, store.getCacheStats());
	}

	private static void report(Profile p, CacheStats mix, long mixNs, int[] tierReads,
			TierResult hot, TierResult common, TierResult rare) {
		System.out.println();
		System.out.printf("Mixed workload: %d reads (hot=%d common=%d rare=%d)%n",
				MIX_READS, tierReads[0], tierReads[1], tierReads[2]);
		System.out.printf("  %s%n", mix);
		System.out.printf("  time=%.1f ms   throughput=%.1f reads/ms   %.2f us/read%n",
				mixNs / 1e6,
				MIX_READS * 1e6 / mixNs,
				(mixNs / 1e3) / MIX_READS);
		System.out.println();
		System.out.println("Per-tier focused (reset stats per tier):");
		System.out.printf("  hot    (%6d reads): %.1f ms (%.2f us/read, %.1f reads/ms)  %s%n",
				hot.reads, hot.ns / 1e6, hot.usPerRead(), hot.readsPerMs(), hot.stats);
		System.out.printf("  common (%6d reads): %.1f ms (%.2f us/read, %.1f reads/ms)  %s%n",
				common.reads, common.ns / 1e6, common.usPerRead(), common.readsPerMs(), common.stats);
		System.out.printf("  rare   (%6d reads): %.1f ms (%.2f us/read, %.1f reads/ms)  %s%n",
				rare.reads, rare.ns / 1e6, rare.usPerRead(), rare.readsPerMs(), rare.stats);
	}

	private static void printComparison(Profile p, RunResult off, RunResult on) {
		System.out.println();
		System.out.println("============================================================");
		System.out.printf("SUMMARY: %s profile (payload=%d B, %d reads mixed)%n",
				p.name(), p.payload, MIX_READS);
		System.out.println("============================================================");
		System.out.printf("%-10s %12s %12s %12s %10s%n",
				"workload", "L2-off (ms)", "L2-on (ms)", "speedup", "decode↓");
		System.out.println("------------------------------------------------------------");
		printRow("mixed",  off.mixNs,        on.mixNs,        off.mix,         on.mix);
		printRow("hot",    off.hot.ns,       on.hot.ns,       off.hot.stats,   on.hot.stats);
		printRow("common", off.common.ns,    on.common.ns,    off.common.stats, on.common.stats);
		printRow("rare",   off.rare.ns,      on.rare.ns,      off.rare.stats,  on.rare.stats);
		System.out.println("------------------------------------------------------------");
		System.out.printf("hit-rate (mixed):  off=%.3f   on=%.3f%n",
				off.mix.hitRate(), on.mix.hitRate());
		System.out.printf("decodes  (mixed):  off=%d   on=%d   (-%.1f%%)%n",
				off.mix.decodes, on.mix.decodes,
				100.0 * (off.mix.decodes - on.mix.decodes) / Math.max(1, off.mix.decodes));
	}

	private static void printRow(String label, long offNs, long onNs, CacheStats off, CacheStats on) {
		double speedup = (double) offNs / Math.max(1, onNs);
		long dDelta = off.decodes - on.decodes;
		double dPct = off.decodes == 0 ? 0 : 100.0 * dDelta / off.decodes;
		System.out.printf("%-10s %12.1f %12.1f %11.2fx %9.1f%%%n",
				label, offNs / 1e6, onNs / 1e6, speedup, dPct);
	}

	// ------------------------------------------------------------------
	// Entry point
	// ------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		// Args: profile name(s). Each runs both L2-on and L2-off back-to-back.
		List<String> profiles = (args.length == 0)
				? Arrays.asList("small")
				: Arrays.asList(args);

		// Defensive: encoder caches some state — touch it once so first measured run is warm.
		new CVMEncoder(null);

		StoreCacheDemo demo = new StoreCacheDemo();
		for (String s : profiles) {
			Profile p;
			try {
				p = Profile.valueOf(s.toUpperCase());
			} catch (IllegalArgumentException e) {
				System.err.println("unknown profile: " + s + " (use small | big | huge)");
				continue;
			}
			RunResult off = demo.runProfile(p, false);
			RunResult on  = demo.runProfile(p, true);
			printComparison(p, off, on);
		}
	}
}
