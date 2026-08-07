package convex.benchmarks;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.etch.Etch;
import convex.etch.EtchConfig;
import convex.etch.EtchConstants;
import convex.etch.EtchStore;

/**
 * Fixed-work concurrent Etch benchmark and robustness smoke test.
 *
 * <p>This is intentionally not a JMH microbenchmark. Each configured store
 * receives exactly the same deterministic workload, including concurrent
 * readers and writers, a controlled miss rate, cache-path hits, direct store
 * hits and reads of recently published writes. A run finishes by flushing,
 * closing, reopening and sampling persisted values.</p>
 */
public final class EtchConcurrentBenchmark {
	private static final long VALUE_MARKER=0x455443482d42454eL;
	private static final int RECENT_RING_SIZE=1<<16;
	private static final int FAILURE_CHECK_MASK=(1<<10)-1;
	private static final byte[] BENCHMARK_SECRET=createBenchmarkSecret();

	private EtchConcurrentBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		for (String arg:args) {
			if ("--help".equals(arg)||"-h".equals(arg)) {
				printUsage(System.out);
				return;
			}
		}
		run(Options.parse(args),System.out);
	}

	static List<RunResult> run(Options options, PrintStream output) throws Exception {
		Path directory=options.directory();
		boolean temporaryDirectory=directory==null;
		if (temporaryDirectory) {
			directory=Files.createTempDirectory("etch-concurrent-benchmark-");
			if (!options.keepFiles()) directory.toFile().deleteOnExit();
		} else {
			Files.createDirectories(directory);
		}

		output.printf(Locale.ROOT,
				"Etch fixed workload: %,d preload, %,d reads, %,d writes, %d readers, %d writers, %d periodic syncs%n",
				options.preload(),options.reads(),options.writes(),options.readers(),options.writers(),
				periodicSyncCount(options));
		output.printf(Locale.ROOT,
				"Read mix: %d%% misses, %d%% recent-write targets, %d%% direct-store hits; seed=0x%016x%n",
				options.missPercent(),options.recentPercent(),options.directHitPercent(),options.seed());

		List<RunResult> results=new ArrayList<>();
		try {
			for (String configName:options.configNames()) {
				StoreConfig config=StoreConfig.parse(configName);
				Path file=Files.createTempFile(directory,"etch-"+safeName(configName)+"-", ".etch");
				RunResult result=runOne(config,file,options,output);
				results.add(result);
				if (!options.keepFiles()) deleteOrDefer(file);
			}
		} finally {
			if (temporaryDirectory&&!options.keepFiles()) {
				try {
					Files.deleteIfExists(directory);
				} catch (IOException e) {
					// The directory was registered before its files for exit cleanup.
				}
			}
		}
		return List.copyOf(results);
	}

	private static RunResult runOne(StoreConfig storeConfig, Path path,
			Options options, PrintStream output) throws Exception {
		long totalStart=System.nanoTime();
		Etch etch=Etch.create(path.toFile(),storeConfig.config());
		EtchStore store=new EtchStore(etch);
		String mapper=etch.getMappingImplementation();
		Hash[] initialHashes=new Hash[options.preload()];
		AtomicReferenceArray<PublishedValue> recent=new AtomicReferenceArray<>(RECENT_RING_SIZE);
		boolean closed=false;
		try {
			long preloadStart=System.nanoTime();
			for (int i=0;i<initialHashes.length;i++) {
				AVector<CVMLong> value=createValue(i,options.payloadLongs());
				Ref<AVector<CVMLong>> source=value.getRef();
				Hash hash=source.getHash();
				Ref<AVector<CVMLong>> stored=store.storeTopRef(source,Ref.STORED,null);
				if (!hash.equals(stored.getHash())) {
					throw new IllegalStateException("Etch preload returned the wrong hash at value "+i);
				}
				initialHashes[i]=hash;
			}
			long preloadNanos=System.nanoTime()-preloadStart;

			ConcurrentResult concurrent=runConcurrent(store,initialHashes,recent,options);

			long flushStart=System.nanoTime();
			store.flush();
			long flushNanos=System.nanoTime()-flushStart;
			long logicalBytes=store.getEtch().getDataLength();
			store.close();
			closed=true;

			long verifyStart=System.nanoTime();
			verifyReopen(path.toFile(),storeConfig.config(),initialHashes,options);
			long verifyNanos=System.nanoTime()-verifyStart;
			long totalNanos=System.nanoTime()-totalStart;
			long physicalBytes=Files.size(path);

			RunResult result=new RunResult(storeConfig.name(),mapper,options.preload(),
					concurrent.cachedHits(),concurrent.directHits(),concurrent.recentHits(),
					concurrent.misses(),concurrent.writes(),concurrent.syncs(),
					concurrent.syncNanos(),concurrent.checksum(),
					preloadNanos,concurrent.elapsedNanos(),flushNanos,verifyNanos,totalNanos,
					logicalBytes,physicalBytes,path);
			printResult(result,output,options.keepFiles());
			return result;
		} finally {
			if (!closed) store.close();
		}
	}

	private static ConcurrentResult runConcurrent(EtchStore store, Hash[] initialHashes,
			AtomicReferenceArray<PublishedValue> recent, Options options) throws Exception {
		int threadCount=options.readers()+options.writers();
		CountDownLatch ready=new CountDownLatch(threadCount);
		CountDownLatch start=new CountDownLatch(1);
		AtomicBoolean failed=new AtomicBoolean();
		ExecutorService executor=Executors.newFixedThreadPool(threadCount,new WorkerThreadFactory());
		List<Future<WorkerResult>> futures=new ArrayList<>(threadCount);
		try {
			for (int i=0;i<options.readers();i++) {
				long count=partitionCount(options.reads(),i,options.readers());
				long seed=mix64(options.seed()^0x5245414445520000L^i);
				int syncs=((i==0)&&(options.reads()>0L))?periodicSyncCount(options):0;
				futures.add(executor.submit(started(ready,start,failed,
						()->readWork(store,initialHashes,recent,count,seed,syncs,options,failed))));
			}
			for (int i=0;i<options.writers();i++) {
				long count=partitionCount(options.writes(),i,options.writers());
				long first=partitionStart(options.writes(),i,options.writers());
				int syncs=((i==0)&&(options.reads()==0L))?periodicSyncCount(options):0;
				futures.add(executor.submit(started(ready,start,failed,
						()->writeWork(store,recent,first,count,syncs,options,failed))));
			}

			ready.await();
			long started=System.nanoTime();
			start.countDown();
			long cachedHits=0L;
			long directHits=0L;
			long recentHits=0L;
			long misses=0L;
			long writes=0L;
			long syncs=0L;
			long syncNanos=0L;
			long checksum=0L;
			for (Future<WorkerResult> future:futures) {
				WorkerResult worker=get(future);
				cachedHits+=worker.cachedHits();
				directHits+=worker.directHits();
				recentHits+=worker.recentHits();
				misses+=worker.misses();
				writes+=worker.writes();
				syncs+=worker.syncs();
				syncNanos+=worker.syncNanos();
				checksum^=worker.checksum();
			}
			long elapsed=System.nanoTime()-started;
			long reads=Math.addExact(Math.addExact(cachedHits,directHits),misses);
			if (reads!=options.reads()) {
				throw new IllegalStateException("Reader work mismatch: expected "
						+options.reads()+", completed "+reads);
			}
			if (writes!=options.writes()) {
				throw new IllegalStateException("Writer work mismatch: expected "
						+options.writes()+", completed "+writes);
			}
			long expectedSyncs=periodicSyncCount(options);
			if (syncs!=expectedSyncs) {
				throw new IllegalStateException("Sync work mismatch: expected "
						+expectedSyncs+", completed "+syncs);
			}
			return new ConcurrentResult(cachedHits,directHits,recentHits,misses,writes,
					syncs,syncNanos,checksum,elapsed);
		} catch (Throwable t) {
			failed.set(true);
			executor.shutdownNow();
			throw t;
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private static Callable<WorkerResult> started(CountDownLatch ready, CountDownLatch start,
			AtomicBoolean failed, Callable<WorkerResult> task) {
		return () -> {
			ready.countDown();
			start.await();
			try {
				return task.call();
			} catch (Throwable t) {
				failed.set(true);
				throw t;
			}
		};
	}

	private static WorkerResult readWork(EtchStore store, Hash[] initialHashes,
			AtomicReferenceArray<PublishedValue> recent, long count, long seed,
			int syncCount, Options options, AtomicBoolean failed) throws IOException {
		SplittableRandom random=new SplittableRandom(seed);
		SyncSchedule syncSchedule=new SyncSchedule(count,syncCount);
		long cachedHits=0L;
		long directHits=0L;
		long recentHits=0L;
		long misses=0L;
		long checksum=0L;
		for (long i=0;i<count;i++) {
			if (((i&FAILURE_CHECK_MASK)==0L)&&failed.get()) break;
			if (random.nextInt(100)<options.missPercent()) {
				Hash absent=randomHash(random);
				if (store.refForHash(absent)!=null) {
					throw new IllegalStateException("Random absent hash unexpectedly resolved: "+absent);
				}
				misses++;
				checksum^=absent.longValue();
				syncSchedule.afterOperation(store,i+1L);
				continue;
			}

			PublishedValue target=null;
			if (random.nextInt(100)<options.recentPercent()) {
				target=recent.get(random.nextInt(RECENT_RING_SIZE));
			}
			if (target==null) {
				int index=random.nextInt(initialHashes.length);
				target=new PublishedValue(index,initialHashes[index]);
			} else {
				recentHits++;
			}

			Ref<?> ref;
			if (random.nextInt(100)<options.directHitPercent()) {
				ref=store.readStoreRef(target.hash());
				directHits++;
			} else {
				ref=store.refForHash(target.hash());
				cachedHits++;
			}
			checksum^=validateValue(ref,target.id(),options.payloadLongs());
			syncSchedule.afterOperation(store,i+1L);
		}
		return new WorkerResult(cachedHits,directHits,recentHits,misses,0L,
				syncSchedule.completed(),syncSchedule.elapsedNanos(),checksum);
	}

	private static WorkerResult writeWork(EtchStore store,
			AtomicReferenceArray<PublishedValue> recent, long first, long count, int syncCount,
			Options options, AtomicBoolean failed) throws IOException {
		long checksum=0L;
		long completed=0L;
		SyncSchedule syncSchedule=new SyncSchedule(count,syncCount);
		for (long i=0;i<count;i++) {
			if (((i&FAILURE_CHECK_MASK)==0L)&&failed.get()) break;
			long writeIndex=first+i;
			long id=Math.addExact(options.preload(),writeIndex);
			AVector<CVMLong> value=createValue(id,options.payloadLongs());
			Ref<AVector<CVMLong>> source=value.getRef();
			Hash hash=source.getHash();
			Ref<AVector<CVMLong>> stored=store.storeTopRef(source,Ref.STORED,null);
			if (!hash.equals(stored.getHash())) {
				throw new IllegalStateException("Etch write returned the wrong hash at value "+id);
			}
			recent.set((int)(writeIndex&(RECENT_RING_SIZE-1L)),new PublishedValue(id,hash));
			checksum^=hash.longValue()^id;
			completed++;
			syncSchedule.afterOperation(store,completed);
		}
		return new WorkerResult(0L,0L,0L,0L,completed,
				syncSchedule.completed(),syncSchedule.elapsedNanos(),checksum);
	}

	private static void verifyReopen(File file, EtchConfig config, Hash[] initialHashes,
			Options options) throws IOException {
		EtchStore reopened=new EtchStore(Etch.create(file,config));
		try {
			int samples=options.verifySamples();
			SplittableRandom random=new SplittableRandom(mix64(options.seed()^0x564552494659L));
			if (initialHashes.length>0) {
				verifyOne(reopened,initialHashes[0],0L,options.payloadLongs());
				verifyOne(reopened,initialHashes[initialHashes.length-1],
						initialHashes.length-1L,options.payloadLongs());
			}
			if (options.writes()>0L) {
				verifyWritten(reopened,0L,options);
				verifyWritten(reopened,options.writes()-1L,options);
			}
			for (int i=0;i<samples;i++) {
				boolean verifyInitial=(options.writes()==0L)
						||((initialHashes.length>0)&&random.nextBoolean());
				if (verifyInitial) {
					int index=random.nextInt(initialHashes.length);
					verifyOne(reopened,initialHashes[index],index,options.payloadLongs());
				} else {
					verifyWritten(reopened,random.nextLong(options.writes()),options);
				}
			}
		} finally {
			reopened.close();
		}
	}

	private static void verifyWritten(EtchStore store, long writeIndex, Options options)
			throws IOException {
		long id=Math.addExact(options.preload(),writeIndex);
		AVector<CVMLong> expected=createValue(id,options.payloadLongs());
		verifyOne(store,expected.getHash(),id,options.payloadLongs());
	}

	private static void verifyOne(EtchStore store, Hash hash, long id, int payloadLongs)
			throws IOException {
		validateValue(store.readStoreRef(hash),id,payloadLongs);
	}

	private static long validateValue(Ref<?> ref, long expectedId, int payloadLongs) {
		if (ref==null) throw new IllegalStateException("Missing Etch value "+expectedId);
		ACell value=ref.getValue();
		if (!(value instanceof AVector<?> vector)||(vector.count()!=payloadLongs)) {
			throw new IllegalStateException("Invalid Etch value shape for "+expectedId);
		}
		if (!(vector.get(0) instanceof CVMLong marker)||(marker.longValue()!=VALUE_MARKER)) {
			throw new IllegalStateException("Invalid Etch value marker for "+expectedId);
		}
		if (!(vector.get(1) instanceof CVMLong id)||(id.longValue()!=expectedId)) {
			throw new IllegalStateException("Invalid Etch value ID: expected "+expectedId);
		}
		CVMLong tail=(CVMLong)vector.get(payloadLongs-1);
		return expectedId^tail.longValue();
	}

	private static AVector<CVMLong> createValue(long id, int payloadLongs) {
		long[] values=new long[payloadLongs];
		values[0]=VALUE_MARKER;
		values[1]=id;
		for (int i=2;i<values.length;i++) values[i]=mix64(id+i);
		return Vectors.createLongs(values);
	}

	private static Hash randomHash(SplittableRandom random) {
		byte[] bytes=new byte[Hash.LENGTH];
		for (int offset=0;offset<bytes.length;offset+=Long.BYTES) {
			long value=random.nextLong();
			for (int i=0;i<Long.BYTES;i++) {
				bytes[offset+i]=(byte)(value>>>(56-i*Byte.SIZE));
			}
		}
		Hash hash=Hash.wrap(bytes);
		return Hash.NULL_HASH.equals(hash)?randomHash(random):hash;
	}

	private static byte[] createBenchmarkSecret() {
		byte[] secret=new byte[32];
		for (int i=0;i<secret.length;i++) secret[i]=(byte)(0x40+i);
		return secret;
	}

	private static WorkerResult get(Future<WorkerResult> future) throws Exception {
		try {
			return future.get();
		} catch (ExecutionException e) {
			Throwable cause=e.getCause();
			if (cause instanceof Exception exception) throw exception;
			if (cause instanceof Error error) throw error;
			throw new IllegalStateException("Etch benchmark worker failed",cause);
		}
	}

	private static long partitionCount(long total, int index, int partitions) {
		long base=total/partitions;
		return base+((index<(total%partitions))?1L:0L);
	}

	private static long partitionStart(long total, int index, int partitions) {
		long base=total/partitions;
		long remainder=total%partitions;
		return Math.addExact(Math.multiplyExact(base,index),Math.min(index,remainder));
	}

	private static int periodicSyncCount(Options options) {
		long coordinatorWork=(options.reads()>0L)
				?partitionCount(options.reads(),0,options.readers())
				:partitionCount(options.writes(),0,options.writers());
		return (int)Math.min(options.syncs(),coordinatorWork);
	}

	private static long proportionalCeiling(long total, int numerator, int denominator) {
		if ((total<0L)||(numerator<=0)||(numerator>denominator)||(denominator<=0)) {
			throw new IllegalArgumentException("Invalid proportional position");
		}
		long quotient=total/denominator;
		long remainder=total%denominator;
		long whole=Math.multiplyExact(quotient,numerator);
		long partial=(remainder*numerator+denominator-1L)/denominator;
		return Math.addExact(whole,partial);
	}

	private static long mix64(long value) {
		value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
		value=(value^(value>>>27))*0x94d049bb133111ebL;
		return value^(value>>>31);
	}

	private static void printResult(RunResult result, PrintStream output, boolean keepFile) {
		long reads=result.cachedHits()+result.directHits()+result.misses();
		long operations=reads+result.writes();
		double seconds=seconds(result.workloadNanos());
		output.printf(Locale.ROOT,"%n[%s] mapper=%s%n",result.configName(),result.mapper());
		output.printf(Locale.ROOT,
				"  work: %,d ops in %.3f s = %,.0f ops/s (%,d cached hits, %,d direct hits, %,d recent, %,d misses, %,d writes)%n",
				operations,seconds,operations/seconds,result.cachedHits(),result.directHits(),
				result.recentHits(),result.misses(),result.writes());
		output.printf(Locale.ROOT,"  syncs: %,d periodic in %.3f s total%n",
				result.syncs(),seconds(result.syncNanos()));
		output.printf(Locale.ROOT,
				"  phases: preload %.3f s, final flush %.3f s, reopen/verify %.3f s, E2E %.3f s%n",
				seconds(result.preloadNanos()),seconds(result.flushNanos()),
				seconds(result.verifyNanos()),seconds(result.totalNanos()));
		output.printf(Locale.ROOT,
				"  size: logical %.2f MiB, physical %.2f MiB; checksum=0x%016x%s%n",
				mib(result.logicalBytes()),mib(result.physicalBytes()),result.checksum(),
				keepFile?"; file="+result.path():"");
	}

	private static double seconds(long nanos) {
		return nanos/1_000_000_000.0;
	}

	private static double mib(long bytes) {
		return bytes/(1024.0*1024.0);
	}

	private static String safeName(String value) {
		return value.replaceAll("[^A-Za-z0-9._-]","_");
	}

	private static void deleteOrDefer(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			path.toFile().deleteOnExit();
		}
	}

	private static void printUsage(PrintStream output) {
		output.println("Usage: EtchConcurrentBenchmark [options]");
		output.println("  --config NAME              Repeat for each config (default: v2-auto)");
		output.println("                             v1-mapped, v2-auto, v2-mapped, v2-ffm");
		output.println("                             v3-auto, v3-aes-auto, v3-aes-index-auto");
		output.println("                             v3-chacha-auto, v3-chacha-index-auto");
		output.println("                             append -no-chains to disable short chains");
		output.println("  --readers N                Reader threads (default: up to 8)");
		output.println("  --writers N                Writer threads (default: 2)");
		output.println("  --syncs N                  Periodic syncs across concurrent work (default: 50)");
		output.println("  --reads N                  Total reads (default: 150,000,000)");
		output.println("  --writes N                 Total writes (default: 1,000,000)");
		output.println("  --preload N                Initial values (default: 250,000)");
		output.println("  --payload-longs N          Longs per value (default: 8; minimum: 2)");
		output.println("  --miss-percent N           Absent-hash reads (default: 20)");
		output.println("  --recent-percent N         Valid reads targeting recent writes (default: 20)");
		output.println("  --direct-hit-percent N     Valid reads bypassing the L2 cache (default: 50)");
		output.println("  --verify-samples N         Random reopen checks (default: 10,000)");
		output.println("  --seed N                   Reproducible decimal or 0x seed");
		output.println("  --directory PATH           Directory for temporary Etch files");
		output.println("  --keep-files               Retain generated Etch files");
	}

	static record Options(List<String> configNames, int readers, int writers, int syncs,
			long reads, long writes, int preload, int payloadLongs, int missPercent,
			int recentPercent, int directHitPercent, int verifySamples, long seed,
			Path directory, boolean keepFiles) {
		private static final int DEFAULT_READERS=Math.max(2,
				Math.min(8,Runtime.getRuntime().availableProcessors()-2));

		static Options parse(String[] args) {
			List<String> configs=new ArrayList<>();
			int readers=DEFAULT_READERS;
			int writers=2;
			int syncs=50;
			long reads=150_000_000L;
			long writes=1_000_000L;
			int preload=250_000;
			int payloadLongs=8;
			int missPercent=20;
			int recentPercent=20;
			int directHitPercent=50;
			int verifySamples=10_000;
			long seed=0x5eed5eedc0ffeeL;
			Path directory=null;
			boolean keepFiles=false;

			for (int i=0;i<args.length;i++) {
				String argument=args[i];
				String key=argument;
				String value=null;
				int equals=argument.indexOf('=');
				if (equals>=0) {
					key=argument.substring(0,equals);
					value=argument.substring(equals+1);
				}
				switch (key) {
					case "--keep-files" -> keepFiles=true;
					case "--config" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						configs.add(value);
					}
					case "--readers" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						readers=parseInt(value,key);
					}
					case "--writers" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						writers=parseInt(value,key);
					}
					case "--syncs" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						syncs=parseInt(value,key);
					}
					case "--reads" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						reads=parseLong(value,key);
					}
					case "--writes" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						writes=parseLong(value,key);
					}
					case "--preload" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						preload=parseInt(value,key);
					}
					case "--payload-longs" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						payloadLongs=parseInt(value,key);
					}
					case "--miss-percent" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						missPercent=parseInt(value,key);
					}
					case "--recent-percent" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						recentPercent=parseInt(value,key);
					}
					case "--direct-hit-percent" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						directHitPercent=parseInt(value,key);
					}
					case "--verify-samples" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						verifySamples=parseInt(value,key);
					}
					case "--seed" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						seed=Long.decode(value.replace("_",""));
					}
					case "--directory" -> {
						value=value(value,args,++i,key,equals>=0);
						if (equals>=0) i--;
						directory=Path.of(value);
					}
					default -> throw new IllegalArgumentException("Unknown option: "+key);
				}
			}

			if (configs.isEmpty()) configs.add("v2-auto");
			if (readers<=0) throw new IllegalArgumentException("--readers must be positive");
			if (writers<=0) throw new IllegalArgumentException("--writers must be positive");
			if (syncs<0) throw new IllegalArgumentException("--syncs must not be negative");
			if (reads<0L) throw new IllegalArgumentException("--reads must not be negative");
			if (writes<0L) throw new IllegalArgumentException("--writes must not be negative");
			if (preload<=0) throw new IllegalArgumentException("--preload must be positive");
			if (payloadLongs<2) throw new IllegalArgumentException("--payload-longs must be at least 2");
			checkPercent(missPercent,"--miss-percent");
			checkPercent(recentPercent,"--recent-percent");
			checkPercent(directHitPercent,"--direct-hit-percent");
			if (verifySamples<0) throw new IllegalArgumentException("--verify-samples must not be negative");
			return new Options(List.copyOf(configs),readers,writers,syncs,reads,writes,preload,
					payloadLongs,missPercent,recentPercent,directHitPercent,verifySamples,
					seed,directory,keepFiles);
		}

		private static String value(String inline, String[] args, int index, String key,
				boolean wasInline) {
			if (wasInline) {
				if (inline.isEmpty()) throw new IllegalArgumentException("Missing value for "+key);
				return inline;
			}
			if (index>=args.length) throw new IllegalArgumentException("Missing value for "+key);
			return args[index];
		}

		private static int parseInt(String value, String key) {
			try {
				return Integer.parseInt(value.replace("_",""));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid integer for "+key+": "+value,e);
			}
		}

		private static long parseLong(String value, String key) {
			try {
				return Long.parseLong(value.replace("_",""));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid integer for "+key+": "+value,e);
			}
		}

		private static void checkPercent(int value, String key) {
			if ((value<0)||(value>100)) {
				throw new IllegalArgumentException(key+" must be from 0 to 100");
			}
		}
	}

	static record RunResult(String configName, String mapper, int preload,
			long cachedHits, long directHits, long recentHits, long misses, long writes,
			long syncs, long syncNanos, long checksum, long preloadNanos, long workloadNanos, long flushNanos,
			long verifyNanos, long totalNanos, long logicalBytes, long physicalBytes,
			Path path) {
	}

	private record StoreConfig(String name, EtchConfig config) {
		static StoreConfig parse(String name) {
			boolean buildChains=!name.endsWith("-no-chains");
			String base=buildChains?name:name.substring(0,name.length()-"-no-chains".length());
			if ((base.length()<4)||(base.charAt(0)!='v')||(base.charAt(2)!='-')) {
				throw new IllegalArgumentException("Invalid Etch config name: "+name);
			}
			short version;
			try {
				version=Short.parseShort(base.substring(1,2));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid Etch config version: "+name,e);
			}
			String mapping=base.substring(3);
			EtchConfig.CipherMode cipher=EtchConfig.CipherMode.NONE;
			boolean encryptedIndex=false;
			if (version==EtchConstants.VERSION_3) {
				if (mapping.startsWith("aes-index-")) {
					cipher=EtchConfig.CipherMode.AES_256_CTR;
					encryptedIndex=true;
					mapping=mapping.substring("aes-index-".length());
				} else if (mapping.startsWith("aes-")) {
					cipher=EtchConfig.CipherMode.AES_256_CTR;
					mapping=mapping.substring("aes-".length());
				} else if (mapping.startsWith("chacha-index-")) {
					cipher=EtchConfig.CipherMode.CHACHA20;
					encryptedIndex=true;
					mapping=mapping.substring("chacha-index-".length());
				} else if (mapping.startsWith("chacha-")) {
					cipher=EtchConfig.CipherMode.CHACHA20;
					mapping=mapping.substring("chacha-".length());
				} else if (mapping.startsWith("plain-")) {
					mapping=mapping.substring("plain-".length());
				}
			}
			EtchConfig.MappingMode mappingMode=switch (mapping) {
				case "auto" -> EtchConfig.create(version).getMappingMode();
				case "mapped" -> EtchConfig.MappingMode.MAPPED_BYTE_BUFFER;
				case "ffm" -> EtchConfig.MappingMode.MEMORY_SEGMENT;
				default -> throw new IllegalArgumentException("Invalid Etch mapping config: "+name);
			};
			EtchConfig config=(version==EtchConstants.VERSION_3)
					?EtchConfig.createV3(mappingMode,buildChains,cipher,encryptedIndex,null,
							(cipher==EtchConfig.CipherMode.NONE)?null:BENCHMARK_SECRET)
					:EtchConfig.create(version,mappingMode,buildChains);
			return new StoreConfig(name,config);
		}
	}

	private record PublishedValue(long id, Hash hash) {
	}

	private record WorkerResult(long cachedHits, long directHits, long recentHits,
			long misses, long writes, long syncs, long syncNanos, long checksum) {
	}

	private record ConcurrentResult(long cachedHits, long directHits, long recentHits,
			long misses, long writes, long syncs, long syncNanos, long checksum, long elapsedNanos) {
	}

	private static final class SyncSchedule {
		private final long workCount;
		private final int requested;
		private int completed;
		private long next;
		private long elapsedNanos;

		private SyncSchedule(long workCount, int requested) {
			this.workCount=workCount;
			this.requested=requested;
			this.next=(requested==0)?Long.MAX_VALUE:proportionalCeiling(workCount,1,requested);
		}

		private void afterOperation(EtchStore store, long operation) throws IOException {
			if (operation!=next) return;
			long started=System.nanoTime();
			store.flush();
			elapsedNanos+=System.nanoTime()-started;
			completed++;
			next=(completed==requested)?Long.MAX_VALUE
					:proportionalCeiling(workCount,completed+1,requested);
		}

		private int completed() {
			return completed;
		}

		private long elapsedNanos() {
			return elapsedNanos;
		}
	}

	private static final class WorkerThreadFactory implements ThreadFactory {
		private final AtomicInteger next=new AtomicInteger();

		@Override
		public Thread newThread(Runnable task) {
			Thread thread=new Thread(task,"etch-workload-"+next.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}
}
