package convex.etch;

import static convex.etch.EtchConstants.MAX_LEVEL;
import static convex.etch.EtchConstants.POINTER_CHAIN;
import static convex.etch.EtchConstants.POINTER_INDEX;
import static convex.etch.EtchConstants.POINTER_SIZE;
import static convex.etch.EtchConstants.POINTER_START;
import static convex.etch.EtchConstants.POINTER_TYPE_MASK;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.StoreException;
import convex.core.store.MemoryStore;
import convex.core.store.StoreTransfer;
import convex.core.util.Utils;

/**
 * Explicit verification of Etch data, independent of copying and collection.
 *
 * <p>{@link #findMissing(Etch, Hash)} checks root completeness;
 * {@link #verifyPersisted(Etch)} checks an entirely persisted file;
 * {@link IndexVisitor} checks index structure; {@link #validate(File)} performs
 * strict offline validation including record hashes and canonical encodings.
 * These are explicit maintenance operations, never part of normal reads or
 * checkpoint export.</p>
 */
public final class EtchVerifier {
	private static final int DEFAULT_MAX_DIAGNOSTICS=100;

	private EtchVerifier() {
	}

	/**
	 * Finds missing hashes in the tree reachable from an explicit root, using
	 * only this physical file. Neither caches nor other GC generations can
	 * satisfy a read. Persistence flags are deliberately ignored: GC targets
	 * may also contain unrelated STORED entries.
	 *
	 * <p>Uses the same iterative, duplicate-safe traversal as store-level
	 * verification, with an uncached private decoding context. Nil, unset and
	 * empty roots are trivially complete. The caller keeps the file open and
	 * write-quiescent. Working memory is proportional to the reachable hashes.
	 * This checks completeness, not content hashes or index integrity.</p>
	 *
	 * @return missing hashes, empty if the tree is complete
	 * @throws IOException if the file cannot be read
	 */
	public static List<Hash> findMissing(Etch file, Hash root) throws IOException {
		Objects.requireNonNull(file,"file");
		Objects.requireNonNull(root,"root");
		if (isSpecialRoot(root)) return List.of();
		try (EtchReadView reader=new EtchReadView(new Etch[] {file},null)) {
			return StoreTransfer.verify(reader,root);
		} catch (StoreException e) {
			if (e.getCause() instanceof IOException io) throw io;
			throw e;
		}
	}

	/**
	 * Checks that the stored root, every indexed entry and every branch are
	 * physically present at PERSISTED status or above. Suitable for a checkpoint
	 * or any other entirely persisted file; an ordinary store may legitimately
	 * contain STORED entries which this stronger check rejects.
	 *
	 * <p>The caller keeps the file open and write-quiescent. This shares the index
	 * checks in {@link IndexVisitor} and uses bounded working memory, without a
	 * visited-hash set. It neither mutates nor closes the file. It does not
	 * recompute content hashes or check canonical encodings; use
	 * {@link #validate(File)} for strict validation of potentially corrupt files.</p>
	 *
	 * @throws IOException if an entry is not persisted or the file cannot be read
	 * @throws MissingDataException if the root or a branch is missing
	 */
	public static void verifyPersisted(Etch file) throws IOException {
		Objects.requireNonNull(file,"file");
		Hash root=file.getRootHash();
		try (EtchReadView reader=new EtchReadView(new Etch[] {file},null)) {
			if (!isSpecialRoot(root)) requirePersisted(reader,root);
			file.visitIndex(new IndexVisitor() {
				@Override public void visitHash(Etch e, Hash hash) {
					try {
						Ref<ACell> ref=requirePersisted(reader,hash);
						Cells.visitBranchRefs(ref.getValue(),br-> {
							try { requirePersisted(reader,br.getHash()); }
							catch (IOException ex) { throw Utils.sneakyThrow(ex); }
						});
					} catch (IOException ex) { throw Utils.sneakyThrow(ex); }
				}
				@Override public void fail(String message) {
					throw Utils.sneakyThrow(new IOException(message));
				}
			});
		}
	}

	private static Ref<ACell> requirePersisted(EtchReadView reader, Hash hash) throws IOException {
		Ref<ACell> ref=reader.read(hash);
		if (ref==null) throw new MissingDataException(reader,hash);
		if (ref.getStatus()<Ref.PERSISTED) throw new IOException("Unpersisted Etch entry: "+hash);
		return ref;
	}

	/**
	 * Lightweight index-only visitor for an open, write-quiescent file. Shares
	 * collision-chain and hash-path checks with strict validation, but does not
	 * check record encodings, content hashes or reference completeness. Use
	 * {@link #validate(File)} for safe traversal of potentially corrupt indexes.
	 */
	public static class IndexVisitor implements IEtchIndexVisitor {
		public long visited;
		public long entries;
		public long empty;
		public long values;
		public long indexPtrs;

		@Override public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException {
			if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Etch verification interrupted");
			visited++;
			int count=e.indexSize(level);
			entries+=count;
			long[] slots=new long[count];
			for (int i=0;i<count;i++) slots[i]=e.readSlot(indexPointer,i);
			int[] starts=chainStarts(slots,(slot,message)->fail(message+" at slot "+slot));
			for (int i=0;i<count;i++) {
				long slot=slots[i];
				long type=slot&POINTER_TYPE_MASK;
				if (slot==0L) {
					empty++;
				} else if (type==POINTER_INDEX) {
					indexPtrs++;
				} else {
					values++;
					Hash hash=e.readValueKey(e.rawPointer(slot));
					int expected=(type==POINTER_CHAIN)?starts[i]:i;
					int mismatch=pathMismatch(hash,level,digits,expected);
					if (mismatch>=0) fail("Record hash does not match index path at level "+mismatch+": "+hash);
					visitHash(e,hash);
				}
			}
		}

		/** Override to perform additional checks for each indexed hash. */
		public void visitHash(Etch e, Hash hash) { }

		/** Override to collect diagnostics instead of failing immediately. */
		public void fail(String message) { throw new Error(message); }
	}

	private static int pathMismatch(Hash hash, int level, int[] path, int expectedDigit) {
		for (int i=0;i<level;i++) {
			if (EtchConstants.indexDigit(hash,i)!=path[i]) return i;
		}
		return (expectedDigit<0 || EtchConstants.indexDigit(hash,level)!=expectedDigit)?level:-1;
	}

	/** Maps continuation slots to their start, reporting orphan/broken chains. */
	private static int[] chainStarts(long[] slots, BiConsumer<Integer,String> problem) {
		int count=slots.length;
		int[] starts=new int[count];
		java.util.Arrays.fill(starts,-1);
		for (int i=0;i<count;i++) {
			if ((slots[i]&POINTER_TYPE_MASK)!=POINTER_START) continue;
			int next=(i+1)%count;
			if ((slots[next]&POINTER_TYPE_MASK)!=POINTER_CHAIN) {
				problem.accept(i,"collision start is not followed by a continuation");
				continue;
			}
			int j=next;
			while ((slots[j]&POINTER_TYPE_MASK)==POINTER_CHAIN && starts[j]<0) {
				starts[j]=i;
				j=(j+1)%count;
			}
		}
		for (int i=0;i<count;i++) {
			if ((slots[i]&POINTER_TYPE_MASK)==POINTER_CHAIN && starts[i]<0) {
				problem.accept(i,"collision continuation has no start");
			}
		}
		return starts;
	}

	/** Bounded detail options for a maintenance validation. */
	public record Options(int maxDiagnostics) {
		public static final Options DEFAULT=new Options(DEFAULT_MAX_DIAGNOSTICS);

		public Options {
			if (maxDiagnostics<0) throw new IllegalArgumentException("Negative diagnostic limit");
		}
	}

	/** Broad failure category suitable for tooling and repair output. */
	public enum ProblemKind {
		INDEX, CHAIN, POINTER, RECORD, HASH, ENCODING, CANONICAL, MISSING_ROOT, IO
	}

	/** One bounded diagnostic. Aggregate report counters are never truncated. */
	public record Problem(ProblemKind kind, long position, String message) {
		public Problem {
			Objects.requireNonNull(kind,"kind");
			Objects.requireNonNull(message,"message");
		}
	}

	/** Immutable validation summary. */
	public record Report(File file, short version, Hash rootHash, long logicalBytes,
			long indexBlocks, long indexSlots, long emptySlots, long indexPointers,
			long records, long encodingBytes, long malformedEntries,
			long hashMismatches, long canonicalFailures, long missingRootHashes,
			long ioFailures, boolean exhaustive, List<Problem> problems) {
		public Report {
			file=Objects.requireNonNull(file,"file");
			rootHash=Objects.requireNonNull(rootHash,"rootHash");
			problems=List.copyOf(problems);
		}

		/** True only when the complete selected store passed all strict checks. */
		public boolean isValid() {
			return exhaustive&&(malformedEntries==0)&&(hashMismatches==0)
					&&(canonicalFailures==0)&&(missingRootHashes==0)&&(ioFailures==0);
		}

		public long failureCount() {
			return malformedEntries+hashMismatches+canonicalFailures
					+missingRootHashes+ioFailures;
		}
	}

	/** Validates an existing plaintext or legacy Etch file under an exclusive lock. */
	public static Report validate(File file) throws IOException {
		return validate(file,null,Options.DEFAULT);
	}

	/** Validates an existing Etch file under an exclusive lock. */
	public static Report validate(File file, EtchConfig config) throws IOException {
		return validate(file,config,Options.DEFAULT);
	}

	/**
	 * Validates an existing Etch file under an exclusive lock. The key function
	 * in {@code config} is required for encrypted stores.
	 */
	public static Report validate(File file, EtchConfig config, Options options)
			throws IOException {
		Objects.requireNonNull(options,"options");
		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openExclusive(file,config)) {
			return validate(reader,options);
		}
	}

	static Report validate(EtchMaintenanceReader reader, Options options) {
		MemoryStore decoder=new MemoryStore();
		try {
			State state=new State(reader,decoder,options);
			state.walkIndex(0,reader.getIndexStart(),new int[MAX_LEVEL]);
			state.checkRootTree();
			return state.report();
		} finally {
			decoder.close();
		}
	}

	private static final class State {
		private final EtchMaintenanceReader source;
		private final Options options;
		private final EtchRecordVerifier verifier;
		private final HashSet<Long> visitedIndexes=new HashSet<>();
		private final HashSet<Long> visitedRecords=new HashSet<>();
		private final TreeMap<Long,Long> occupiedExtents=new TreeMap<>();
		private final Map<Hash,List<Hash>> branches=new HashMap<>();
		private final ArrayList<Problem> problems=new ArrayList<>();
		private long indexBlocks;
		private long indexSlots;
		private long emptySlots;
		private long indexPointers;
		private long records;
		private long encodingBytes;
		private long malformedEntries;
		private long hashMismatches;
		private long canonicalFailures;
		private long missingRootHashes;
		private long ioFailures;
		private boolean exhaustive=true;

		private State(EtchMaintenanceReader source, MemoryStore decoder, Options options) {
			this.source=source;
			this.options=options;
			this.verifier=new EtchRecordVerifier(source,decoder);
		}

		private void walkIndex(int level, long position, int[] path) {
			if (level>=MAX_LEVEL) {
				malformed(ProblemKind.INDEX,position,"index depth exceeds "+MAX_LEVEL);
				return;
			}
			int count=EtchConstants.indexSize(level);
			int length=count*POINTER_SIZE;
			long end;
			try {
				end=Math.addExact(position,length);
			} catch (ArithmeticException e) {
				malformed(ProblemKind.INDEX,position,"index extent overflows");
				return;
			}
			if ((position<source.getIndexStart())||(end>source.getLogicalFileEnd())
					||((level==0)&&(position!=source.getIndexStart()))
					||((level>0)&&((position&(POINTER_SIZE-1L))!=0L))) {
				malformed(ProblemKind.INDEX,position,"index extent is outside the selected store");
				return;
			}
			if (!visitedIndexes.add(position)) {
				malformed(ProblemKind.INDEX,position,"repeated or cyclic child index");
				return;
			}
			if (!occupy(position,end)) {
				malformed(ProblemKind.INDEX,position,"index overlaps another indexed extent");
				return;
			}

			byte[] block=new byte[length];
			try {
				source.readIndex(position,block,0,length);
			} catch (IOException | RuntimeException | Error e) {
				if (e instanceof VirtualMachineError fatal) throw fatal;
				io(position,"cannot read index: "+e.getMessage());
				return;
			}
			indexBlocks++;
			indexSlots+=count;

			long[] slots=new long[count];
			for (int i=0;i<count;i++) {
				long slot=Utils.readLong(block,i*POINTER_SIZE,POINTER_SIZE);
				slots[i]=slot;
				if (slot==0L) emptySlots++;
			}
			int[] chainStarts=chainStarts(slots,(slot,message)->
					malformed(ProblemKind.CHAIN,position+(long)slot*POINTER_SIZE,message));

			for (int i=0;i<count;i++) {
				long slot=slots[i];
				if (slot==0L) continue;
				long type=slot&POINTER_TYPE_MASK;
				long pointer=slot&~POINTER_TYPE_MASK;
				if (type==POINTER_INDEX) {
					indexPointers++;
					if (!validChildExtent(level,pointer)) continue;
					path[level]=i;
					walkIndex(level+1,pointer,path);
				} else {
					int expected=(type==POINTER_CHAIN)?chainStarts[i]:i;
					validateRecord(pointer,level,path,expected);
				}
			}
		}

		private boolean validChildExtent(int level, long pointer) {
			if ((level+1>=MAX_LEVEL)||(pointer<source.getBodyStart())
					||((pointer&(POINTER_SIZE-1L))!=0L)) {
				malformed(ProblemKind.POINTER,pointer,"invalid child-index pointer");
				return false;
			}
			long end;
			try {
				end=Math.addExact(pointer,(long)EtchConstants.indexSize(level+1)*POINTER_SIZE);
			} catch (ArithmeticException e) {
				malformed(ProblemKind.POINTER,pointer,"child-index extent overflows");
				return false;
			}
			if (end>source.getLogicalFileEnd()) {
				malformed(ProblemKind.POINTER,pointer,"child index exceeds the selected store");
				return false;
			}
			return true;
		}

		private void validateRecord(long position, int level, int[] path, int expectedDigit) {
			if (!visitedRecords.add(position)) {
				malformed(ProblemKind.POINTER,position,"duplicate data pointer");
				return;
			}
			EtchRecordVerifier.Result result=verifier.verify(position,source.getLogicalFileEnd());
			if (!result.isValid()) {
				recordFailure(position,result.failure());
				return;
			}
			EtchRecordVerifier.Verified verified=result.verified();
			if (!occupy(position,position+verified.recordLength())) {
				malformed(ProblemKind.POINTER,position,"data record overlaps another indexed extent");
				return;
			}
			Hash hash=verified.hash();
			int mismatch=pathMismatch(hash,level,path,expectedDigit);
			if (mismatch>=0) {
				String location=(mismatch==level)?"its index slot":"index path";
				malformed(ProblemKind.POINTER,position,"record hash does not match "+location+" at level "+mismatch);
				return;
			}
			if (branches.containsKey(hash)) {
				malformed(ProblemKind.POINTER,position,"duplicate stored hash "+hash);
				return;
			}

			records++;
			encodingBytes+=verified.encoding().count();
			ArrayList<Hash> childHashes=new ArrayList<>();
			ACell cell=verified.cell();
			Cells.visitBranchRefs(cell,ref -> childHashes.add(
					Hash.wrap(ref.getHash().getBytes())));
			branches.put(hash,List.copyOf(childHashes));
		}

		private boolean occupy(long start, long end) {
			Map.Entry<Long,Long> previous=occupiedExtents.floorEntry(start);
			if ((previous!=null)&&(previous.getValue()>start)) return false;
			Map.Entry<Long,Long> next=occupiedExtents.ceilingEntry(start);
			if ((next!=null)&&(next.getKey()<end)) return false;
			occupiedExtents.put(start,end);
			return true;
		}

		private void recordFailure(long position, EtchRecordVerifier.Failure failure) {
			switch (failure.kind()) {
				case HASH -> {
					hashMismatches++;
					add(ProblemKind.HASH,position,failure.message());
				}
				case CANONICAL -> {
					canonicalFailures++;
					add(ProblemKind.CANONICAL,position,failure.message());
				}
				case IO -> io(position,failure.message());
				case ENCODING -> {
					canonicalFailures++;
					add(ProblemKind.ENCODING,position,failure.message());
				}
				case EXTENT, LENGTH -> malformed(ProblemKind.RECORD,position,failure.message());
			}
		}

		private void checkRootTree() {
			Hash root=source.getRootHash();
			if (isSpecialRoot(root)) return;
			HashSet<Hash> seen=new HashSet<>();
			ArrayDeque<Hash> pending=new ArrayDeque<>();
			pending.add(root);
			while (!pending.isEmpty()) {
				Hash hash=pending.removeFirst();
				if (!seen.add(hash)||isSpecialRoot(hash)) continue;
				List<Hash> children=branches.get(hash);
				if (children==null) {
					missingRootHashes++;
					add(ProblemKind.MISSING_ROOT,-1L,"root tree is missing "+hash);
					continue;
				}
				pending.addAll(children);
			}
		}

		private Report report() {
			return new Report(source.getFile(),source.getVersion(),source.getRootHash(),
					source.getLogicalFileEnd(),indexBlocks,indexSlots,emptySlots,indexPointers,
					records,encodingBytes,malformedEntries,hashMismatches,
					canonicalFailures,missingRootHashes,ioFailures,exhaustive,problems);
		}

		private void malformed(ProblemKind kind, long position, String message) {
			malformedEntries++;
			add(kind,position,message);
		}

		private void io(long position, String message) {
			ioFailures++;
			exhaustive=false;
			add(ProblemKind.IO,position,message);
		}

		private void add(ProblemKind kind, long position, String message) {
			if (problems.size()<options.maxDiagnostics()) {
				problems.add(new Problem(kind,position,message));
			}
		}
	}

	private static boolean isSpecialRoot(Hash hash) {
		return Hash.UNSET_HASH.equals(hash)||Hash.NULL_HASH.equals(hash)
				||Hash.EMPTY_HASH.equals(hash);
	}
}
