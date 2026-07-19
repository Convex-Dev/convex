package convex.etch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.AStore;
import convex.core.util.Utils;

public class EtchUtils {

	/**
	 * Recovery logging uses the JDK platform logger: convex-core main sources
	 * are deliberately free of external logging dependencies. Applications
	 * using SLF4J pick these up via the standard platform-logging bridge.
	 * Routine idempotent recovery, including safely deferred Windows cleanup,
	 * is DEBUG; warnings indicate unusual but recoverable metadata problems,
	 * while errors identify possible data loss or required operator attention.
	 */
	private static final System.Logger recoveryLog = System.getLogger("convex.etch.recovery");

	private static void debug(String msg, Object... args) {
		recoveryLog.log(System.Logger.Level.DEBUG, fmt(msg, args));
	}

	private static void warn(String msg, Object... args) {
		recoveryLog.log(System.Logger.Level.WARNING, fmt(msg, args));
	}

	private static void error(String msg, Object... args) {
		recoveryLog.log(System.Logger.Level.ERROR, fmt(msg, args));
	}

	/** Minimal SLF4J-style {} substitution, so messages read naturally */
	private static String fmt(String msg, Object... args) {
		StringBuilder sb = new StringBuilder();
		int a = 0, i = 0;
		while (i < msg.length()) {
			int j = msg.indexOf("{}", i);
			if ((j < 0) || (a >= args.length)) {
				sb.append(msg, i, msg.length());
				break;
			}
			sb.append(msg, i, j).append(args[a++]);
			i = j + 2;
		}
		return sb.toString();
	}

	/**
	 * Automatic GC recovery for an Etch store file, run before opening (called
	 * by EtchStore.create; see convex-core/docs/ETCH_GC.md "File lifecycle").
	 *
	 * Reconciles on-disk state left by GC cycles that ended in a previous
	 * process:
	 * <ul>
	 * <li><b>Completed cutovers</b>: the single {@code .gc-complete} marker —
	 * rewritten by every completeGC, so multiple successive GCs never chain —
	 * names the CURRENT store file. It is ADOPTED: installed under the base
	 * file name, with the superseded original deleted. That deletion is the
	 * disk reclamation (each cutover was hard-gated on a verifiably complete
	 * sweep, and everything else is garbage by the retention contract).</li>
	 * <li><b>Defunct files</b> ({@code .gc-defunct} tombstone: superseded
	 * cutover originals not yet deleted, or cancelled targets pinned by
	 * mappings) are deleted, never rolled back — their retained content is
	 * verifiably elsewhere.</li>
	 * <li><b>Abandoned cycles</b> (target files with neither marker reference
	 * nor tombstone) are ROLLED BACK: their contents — which exist nowhere
	 * else — are migrated into the live store file, tolerating a torn tail
	 * from the crash; the root advances only if its tree then verifies
	 * complete; the target is deleted.</li>
	 * </ul>
	 *
	 * Every step is idempotent: a crash mid-recovery leaves a state this
	 * method recognises and resumes on the next run.
	 *
	 * If files cannot be renamed/deleted (e.g. pinned by memory mappings from
	 * this same process on Windows), adoption is DEFERRED: the returned file
	 * is the marker-named current file, which holds the correct data, and the
	 * renames are retried on the next start. Recovery never silently opens
	 * stale data.
	 *
	 * @param file The Etch store file the caller wishes to open
	 * @return The file that should actually be opened (equals {@code file}
	 *         unless adoption was deferred)
	 * @throws IOException in case of IO error, or unresolvable on-disk state
	 */
	public static File recover(File file) throws IOException {
		file = file.getCanonicalFile();
		File marker = markerFile(file);
		List<File> targets = gcTargets(file);
		if (!marker.exists() && targets.isEmpty()) return file; // fast path: no GC residue

		debug("Etch GC recovery started for {} (marker {}, target file(s) found: {})",
				file, marker.exists() ? "present" : "absent", targets);

		// ---- 1: identify the CURRENT store file ----
		// The single marker (rewritten by every completeGC) names it; no marker
		// means the base file itself is current
		File current = null;
		if (marker.exists()) {
			String name = readMarkerName(marker, file);
			if (name != null) {
				File named = new File(file.getParentFile(), name).getCanonicalFile();
				if (named.equals(file)) {
					debug("Etch GC recovery: marker {} names the base file itself - already adopted;"
							+ " removing the leftover marker.", marker.getName());
					deleteOrThrow(marker);
				} else if (!named.exists()) {
					if (file.exists()) {
						debug("Etch GC recovery: marker {} names {} which no longer exists, while {} is"
								+ " present - treating the marker as stale (cutover already adopted by a"
								+ " previous run) and removing it.", marker.getName(), name, file.getName());
						deleteOrThrow(marker);
					} else {
						throw new IOException("Etch GC recovery cannot proceed for " + file + ": marker "
								+ marker.getName() + " names " + name + " as the current store file, but"
								+ " neither that file nor " + file.getName() + " exists. Files have been"
								+ " lost outside Convex's control (disk failure? manual deletion?)."
								+ " Restore " + name + " or " + file.getName() + " from a backup, or delete"
								+ " the marker to start a fresh empty store.");
					}
				} else {
					current = named;
					debug("Etch GC recovery: completed cutover found - current store file is {}",
							current.getName());
				}
			}
		}
		// Where rolled-back data belongs: the live store file
		File live = (current != null) ? current : file;

		// ---- 2: dispose of defunct targets, roll back abandoned ones ----
		// Defunct files (.gc-defunct tombstone) are superseded cutover originals
		// or cancelled targets: their retained content is verifiably elsewhere,
		// so they are only ever deleted - rolling them back would re-introduce
		// collected garbage. Markerless, non-defunct targets are abandoned
		// cycles whose contents exist nowhere else: rolled back into the live
		// file
		for (File st : targets) {
			if (st.getCanonicalFile().equals(current)) continue; // the live file itself
			File tomb = new File(st.getPath() + ".gc-defunct");
			if (tomb.exists()) {
				if (st.delete()) {
					tomb.delete();
					debug("Etch GC recovery: deleted defunct GC file {} (fully superseded or rolled back)",
							st.getName());
				} else {
					debug("Etch GC recovery: defunct GC file {} still cannot be deleted (pinned by memory"
							+ " mappings?). Harmless - its retained content lives elsewhere - and deletion"
							+ " is retried on later starts.", st.getName());
				}
				continue;
			}
			if (!live.exists()) {
				error("Etch GC recovery: abandoned GC target {} cannot be rolled back because the live"
						+ " store file {} is missing (crash during a previous adoption?). Leaving it in"
						+ " place; recovery will retry on a later start. Inspect manually if data written"
						+ " during that cycle matters.", st, live);
				continue;
			}
			rollback(live, st);
		}

		// ---- 3: adopt the current file under the base name ----
		if (current == null) {
			debug("Etch GC recovery finished for {}: roll-back only, no completed cutover to adopt", file);
			return file;
		}

		// Delete the superseded original first. This deletion IS the disk
		// reclamation: its retained content is verifiably in the current file
		// (the cutover was hard-gated on a complete sweep) and everything else
		// is garbage by the retention contract. Operators wanting an archive
		// copy the file BEFORE invoking completeGC
		if (file.exists()) {
			if (!file.delete()) {
				debug("Etch GC recovery: cannot delete superseded original {} (pinned by memory mappings"
						+ " from this same process, e.g. on Windows?). Adoption DEFERRED: opening the"
						+ " current store file {} directly - it holds the correct data - and the deletion"
						+ " and installation will be retried on the next start.", file, current.getName());
				return current;
			}
			new File(file.getPath() + ".gc-defunct").delete();
		}
		try {
			Files.move(current.toPath(), file.toPath());
		} catch (IOException e) {
			debug("Etch GC recovery: deleted the superseded original but cannot install {} as {} ({})."
					+ " Opening it directly; the marker is intact, so the next start will complete the"
					+ " adoption.", current.getName(), file.getName(), e);
			return current;
		}
		if (!marker.delete()) {
			warn("Etch GC recovery: adoption succeeded but marker {} could not be deleted. Delete it"
					+ " manually; until then, later recoveries will detect it as stale and remove it.",
					marker);
		}
		debug("Etch GC recovery finished: {} now holds the garbage-collected store", file);
		return file;
	}

	static File markerFile(File base) throws IOException {
		return new File(base.getCanonicalPath() + ".gc-complete");
	}

	/**
	 * Reads the file name from a GC completion marker, or null (deleting the
	 * marker with a full explanation) if it is malformed.
	 */
	static String readMarkerName(File marker, File base) throws IOException {
		List<String> lines = Files.readAllLines(marker.toPath());
		String name = lines.isEmpty() ? "" : lines.get(0).trim();
		if (name.isEmpty()) {
			warn("Etch GC recovery: marker {} is empty/malformed - deleting it. Any completed GC"
					+ " target alongside {} will instead be treated as an abandoned cycle and rolled back"
					+ " (safe: same data, just not adopted as the main file).", marker, base.getName());
			deleteOrThrow(marker);
			return null;
		}
		return name;
	}

	private static void deleteOrThrow(File f) throws IOException {
		if (!f.delete()) {
			throw new IOException("Etch GC recovery cannot proceed: unable to delete " + f
					+ ". Check filesystem permissions and remove it manually.");
		}
	}

	static void writeMarker(File base, File target) throws IOException {
		// Line 1 is authoritative (the target file name); any further lines are
		// informational only (completeGC also records a root hash hint)
		Files.writeString(markerFile(base).toPath(), target.getName() + "\n");
	}

	/**
	 * Lists GC target files of a base file: names of the form
	 * {@code <base>~<digits?>}, sorted oldest generation first. All targets —
	 * including those of chained successor stores — are named off the logical
	 * base file (bounded naming); chain parentage is carried by the
	 * .gc-complete markers, not by the names.
	 */
	static List<File> gcTargets(File base) {
		String prefix = base.getName() + "~";
		List<File> out = new ArrayList<>();
		File dir = base.getParentFile();
		File[] fs = (dir == null) ? null : dir.listFiles();
		if (fs != null) {
			for (File f : fs) {
				String n = f.getName();
				if (!n.startsWith(prefix)) continue;
				String suffix = n.substring(prefix.length());
				if (suffix.chars().allMatch(Character::isDigit)) out.add(f);
			}
		}
		out.sort(Comparator.comparingLong(f -> {
			String s = f.getName().substring(prefix.length());
			return s.isEmpty() ? 0L : Long.parseLong(s);
		}));
		return out;
	}

	/**
	 * Rolls an abandoned GC target back into its base file: best-effort entry
	 * migration (tolerating a torn tail from the crash), root adoption only if
	 * the target's root tree verifies complete in the base afterwards, then
	 * target deletion.
	 */
	static void rollback(File base, File staleTarget) throws IOException {
		debug("Etch GC recovery: rolling back abandoned GC target {} into {} - data written during"
				+ " that cycle exists nowhere else", staleTarget.getName(), base.getName());
		long copied;
		long[] skipped = {0};
		EtchStore baseStore = new EtchStore(Etch.create(base)); // direct open: no recursive recovery
		try {
			// The target is opened as a store of its own so decoded refs bind to
			// it: the index scan visits entries in hash order (not post-order),
			// and a parent's persist must be able to resolve children from the
			// SOURCE during its descent. Binding refs to the base store instead
			// would make every parent visited before its children fail
			EtchStore srcStore = new EtchStore(Etch.create(staleTarget));
			try {
				copied = lenientCopy(srcStore.getEtch(), baseStore, skipped);
				Hash root = srcStore.getEtch().getRootHash();
				List<Hash> missing = verify(baseStore.getEtch(), root);
				if (missing.isEmpty()) {
					baseStore.getEtch().setRootHash(root);
					baseStore.getEtch().writeDataLength();
					debug("Etch GC recovery: rolled back {} entries from {} and advanced the root of {}"
							+ " to {}", copied, staleTarget.getName(), base.getName(), root);
				} else {
					error("Etch GC recovery: rolled back {} entries from {}, but its root {} does NOT"
							+ " verify complete in {} afterwards ({} hashes missing - torn tail from the"
							+ " crash?). Keeping the previous root: the recovered entries are preserved"
							+ " but unreachable. If that cycle's root state matters, investigate before"
							+ " running further GC cycles.", copied, staleTarget.getName(), root,
							base.getName(), missing.size());
				}
				baseStore.flush();
			} finally {
				srcStore.close();
			}
		} finally {
			baseStore.close();
		}
		if (skipped[0] > 0) {
			error("Etch GC recovery: {} entries in {} could not be read (torn tail or corruption after"
					+ " a crash) and were skipped during roll-back. If data from that cycle matters, copy"
					+ " the file elsewhere before it is deleted.", skipped[0], staleTarget);
		}
		if (!staleTarget.delete()) {
			staleTarget.deleteOnExit();
			debug("Etch GC recovery: rolled-back target {} could not be deleted (pinned by memory"
					+ " mappings from this process?). Deletion is retried on JVM exit; until then later"
					+ " recoveries will redo this roll-back - idempotent and safe, just wasteful.",
					staleTarget);
		}
	}

	/**
	 * Best-effort copy of every readable entry in a (possibly crash-damaged)
	 * Etch file into a destination store. Unreadable slots or entries are
	 * skipped and counted instead of aborting the recovery.
	 */
	static long lenientCopy(Etch source, AStore dest, long[] skipped) {
		long[] count = {0};
		lenientWalk(source, 0, source.getIndexStart(), dest, count, skipped);
		return count[0];
	}

	private static void lenientWalk(Etch e, int level, long indexPointer, AStore dest,
			long[] count, long[] skipped) {
		int n = e.indexSize(level);
		for (int i = 0; i < n; i++) {
			try {
				long slot = e.readSlot(indexPointer, i);
				if (slot == 0L) continue;
				if (e.extractType(slot) == Etch.PTR_INDEX) {
					lenientWalk(e, level + 1, e.rawPointer(slot), dest, count, skipped);
				} else {
					ACell cell = e.readCell(e.rawPointer(slot));
					Ref<ACell> ref = cell.getRef();
					int status = Math.max(Ref.STORED, Math.min(ref.getStatus(), Ref.MAX_STATUS));
					dest.storeTopRef(ref, status, null);
					count[0]++;
				}
			} catch (VirtualMachineError fatal) {
				throw fatal; // never swallow OOM / stack overflow
			} catch (IOException | RuntimeException | Error ex) {
				// Torn tail or corrupt entry after a crash (EtchCorruptionError and
				// decode failures surface as Errors): skip and count, keep going
				skipped[0]++;
			}
		}
	}

	/**
	 * Ensures everything in the source Etch store is persisted in the
	 * destination store: a full index scan drives a transfer of each entry at
	 * its recorded status (STORED entries copy the top cell only; PERSISTED and
	 * above descend, pruning on subtrees the destination already holds).
	 *
	 * The SOURCE must not be undergoing any writes for the duration of the
	 * migration: the underlying index scan (Etch.visitIndex) is racy under
	 * concurrent index restructuring and may miss entries. The destination may
	 * be non-empty and in live use. The destination root is not modified.
	 * See convex-core/docs/ETCH_GC.md.
	 *
	 * @param source Source Etch store to migrate from
	 * @param dest Destination store (any AStore implementation)
	 * @return Number of source entries processed
	 * @throws IOException in case of IO error during migration
	 */
	public static long migrate(EtchStore source, AStore dest) throws IOException {
		return migrate(source.getEtch(), dest);
	}

	/**
	 * Etch-level migrate variant: used where the source file is not a store's
	 * main file (e.g. a GC target being reverse-migrated by cancelGC). The
	 * source's store binding is used for decoding. Same write-quiescence
	 * requirement as the store-level variant.
	 *
	 * @param source Source Etch file to migrate from (must be write-quiescent)
	 * @param dest Destination store
	 * @return Number of source entries processed
	 * @throws IOException in case of IO error during migration
	 */
	public static long migrate(Etch source, AStore dest) throws IOException {
		long[] count = {0};
		source.visitIndex(new EtchCellVisitor() {
			@Override
			protected void visitCell(ACell cell) {
				Ref<ACell> ref = cell.getRef();
				// Cap at MAX_STATUS: internal/marked levels are store-local concerns
				int status = Math.max(Ref.STORED, Math.min(ref.getStatus(), Ref.MAX_STATUS));
				try {
					dest.storeTopRef(ref, status, null);
				} catch (IOException e) {
					// OK: enclosing method declares IOException
					throw Utils.sneakyThrow(e);
				}
				count[0]++;
			}
		});
		return count[0];
	}
	
	/**
	 * Verifies that the entire tree reachable from the given hash is present in
	 * a single Etch file: entry presence is checked against this file ONLY
	 * (unlike store-level reads, which may fall back to caches or other files).
	 * Iterative and duplicate-safe; no pruning — a full independent walk.
	 *
	 * @param e Etch file to verify against
	 * @param rootHash Hash of the tree root (unset/nil/empty roots are trivially complete)
	 * @return List of missing hashes, empty if the tree is fully present
	 * @throws IOException in case of IO error
	 */
	public static List<Hash> verify(Etch e, Hash rootHash) throws IOException {
		List<Hash> missing = new ArrayList<>();
		HashSet<Hash> seen = new HashSet<>();
		ArrayDeque<Hash> stack = new ArrayDeque<>();
		// unset / nil / empty roots are recognised without a store entry (as in
		// AStore.getRootRef), so there is nothing to verify
		if (!(Hash.UNSET_HASH.equals(rootHash) || Hash.NULL_HASH.equals(rootHash)
				|| Hash.EMPTY_HASH.equals(rootHash))) {
			stack.push(rootHash);
		}
		while (!stack.isEmpty()) {
			Hash h = stack.pop();
			if (!seen.add(h)) continue;
			Ref<ACell> r = e.read(h);
			if (r == null) {
				missing.add(h);
				continue;
			}
			Cells.visitBranchRefs(r.getValue(), br -> stack.push(br.getHash()));
		}
		return missing;
	}

	public static FullValidator getFullValidator() {
		return new FullValidator();
	}

	/**
	 * An Etch validator that checks every index entry
	 */
	public static class FullValidator implements IEtchIndexVisitor {
		public long visited=0;
		public long entries=0;
		public long empty=0;
		public long values=0;
		public long indexPtrs=0;
		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException {
			visited++;
			
			int isize=e.indexSize(level);
			
			String ps="";
			for (int ll=0; ll<level; ll++) {
				int lsize=e.indexSize(ll);
				int hd=Integer.bitCount(lsize-1)/4;
				ps=ps+Utils.toHexString(digits[ll]).substring(8-hd);
			}
			
			entries+=isize;
			
			if (isize<=0) fail("Bad index size:"+isize);
			
			for (int i=0; i<isize; i++) {
				long slot=e.readSlot(indexPointer, i);
				long ptr=e.rawPointer(slot);
				long type=e.extractType(slot);			
				if ((ptr|type)!=slot) fail("Inconsistent slot code?!?");
				
				if (slot==0) {
					empty++;
				} else if (type!=Etch.PTR_INDEX) {
					values++;
					
					Hash h=e.readValueKey(ptr);
					String hp=h.toHexString(ps.length());
					if (!hp.equals(ps)) {
						fail("Index "+ps+" inconsistent with hash "+h);
					}
					
					visitHash(e,h);
				} else {
					indexPtrs++;
				}
				
				if (type==Etch.PTR_START) {
					int ipp=(i+1)%isize; // next slot
					long nextSlot=e.readSlot(indexPointer, ipp);
					if (e.extractType(nextSlot)!=Etch.PTR_CHAIN) {
						fail("Invalid slot after chain start: "+Utils.toHexString(nextSlot));
					}
				}
				
				if (type==Etch.PTR_CHAIN) {
					int imm=(i+isize-1)%isize; // prev slot
					long prevSlot=e.readSlot(indexPointer, imm);
					long pt=e.extractType(prevSlot);
					if (!((pt==Etch.PTR_CHAIN)||(pt==Etch.PTR_START))) {
						fail("Invalid slot before chain entry: "+Utils.toHexString(prevSlot));
					}
				}
			}
		}
		
		public void visitHash(Etch e,Hash h) {
			// Should be overriden if subclass wants to perform additional validation
		}

		public void fail(String msg) {
			throw new Error(msg);
		}
		
	};
	
	public static abstract class EtchCellVisitor implements IEtchIndexVisitor {
		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException {
			int isize=e.indexSize(level);			
			for (int i=0; i<isize; i++) {
				long slot=e.readSlot(indexPointer, i);
				if (slot==0) continue;
				
				long ptr=e.rawPointer(slot);
				long type=e.extractType(slot);			
				if ((ptr|type)!=slot) throw new Error("Inconsistent slot code?!?");
				
				if (type==Etch.PTR_INDEX) continue;
				
				ACell cell=e.readCell(ptr);
				
				visitCell(cell);
			}
		}
		
		protected abstract void visitCell(ACell cell);
	}


}
