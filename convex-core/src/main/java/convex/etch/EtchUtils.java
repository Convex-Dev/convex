package convex.etch;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import convex.core.data.ACell;
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
	 * file name, with the superseded original name deleted. Without a retained
	 * backup link, that reclaims disk space (each cutover was gated on a complete
	 * sweep, and everything else is garbage by the retention contract).</li>
	 * <li><b>Defunct files</b> ({@code .gc-defunct} tombstone: superseded
	 * cutover originals not yet deleted, or cancelled targets pinned by
	 * mappings) are deleted, never rolled back — their retained content is
	 * verifiably elsewhere.</li>
	 * <li><b>Abandoned cycles</b> (target files with neither marker reference
	 * nor tombstone) are ROLLED BACK: their contents — which exist nowhere
	 * else — are migrated into the live store file, tolerating a torn tail
	 * from the crash; the root advances only if its tree then verifies
	 * complete; the target is deleted. An abandoned v3 target that was never
	 * checkpointed records no committed root, so it is deleted unread and the
	 * live file keeps its last checkpoint.</li>
	 * </ul>
	 *
	 * Every step is idempotent: a crash mid-recovery leaves a state this
	 * method recognises and resumes on the next run.
	 *
	 * Before the first mutation, every participating non-empty file is checked
	 * with a direct, non-mapping header read. Encrypted v3 headers are
	 * authenticated with the caller's compiled configuration. Normal recovery
	 * accepts only cleanly closed v3 files, apart from never-checkpointed
	 * abandoned targets; other dirty v3 files require an explicitly selected
	 * maintenance or repair operation.
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
		return recoverConfigured(file,null);
	}

	/**
	 * Performs automatic GC recovery using compiled Etch configuration. File
	 * headers remain authoritative; configuration supplies key resolution and may
	 * explicitly permit recovery across lifecycle files of different versions.
	 * All participating non-empty files are authenticated before recovery mutates
	 * any marker, store or sibling file.
	 *
	 * @param file Etch store file the caller wishes to open
	 * @param config compiled Etch configuration, including a key function when
	 *        opening an encrypted v3 store
	 * @return file that should actually be opened
	 * @throws IOException in case of invalid configuration, authentication failure,
	 *         dirty v3 state or an unresolvable on-disk state
	 */
	public static File recover(File file, EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		return recoverConfigured(file,config);
	}

	private static File recoverConfigured(File file, EtchConfig config) throws IOException {
		return recover(file,config,EtchGCFileOps.SYSTEM);
	}

	static File recover(File file, EtchConfig config, EtchGCFileOps files) throws IOException {
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
		String markerDiscardReason = null;
		if (marker.exists()) {
			String name = readMarkerName(marker);
			if (name == null) {
				throw new IOException("Malformed GC completion marker; preserve the layout for inspection: "+marker);
			} else {
				File named = new File(file.getParentFile(), name).getCanonicalFile();
				if (named.equals(file)) {
					if (hasMarkerIdentity(marker) && !markerIdentifies(marker,file,config)) {
						throw new IOException("GC marker identity disagrees with the base file: "+file);
					}
					markerDiscardReason = "names the already-adopted base file";
				} else if (!named.exists()) {
					if (file.exists()) {
						if (!markerIdentifies(marker,file,config)) {
							throw new IOException("Missing GC target cannot be identified as the adopted base: "+marker);
						}
						markerDiscardReason = "identifies the already-adopted base file";
					} else {
						throw new IOException("Etch GC recovery cannot proceed for " + file + ": marker "
								+ marker.getName() + " names " + name + " as the current store file, but"
								+ " neither that file nor " + file.getName() + " exists. Files have been"
								+ " lost outside Convex's control (disk failure? manual deletion?)."
								+ " Restore " + name + " or " + file.getName() + " from a backup, or delete"
								+ " the marker to start a fresh empty store.");
					}
				} else if (!containsCanonical(targets,named)) {
					throw new IOException("Etch GC recovery marker "+marker
							+" names a file outside the GC target set for "+file+": "+name);
				} else {
					if (hasMarkerIdentity(marker) && !markerIdentifies(marker,named,config)) {
						throw new IOException("GC marker identity disagrees with target (possibly a reused name): "+named);
					}
					current = named;
					debug("Etch GC recovery: completed cutover found - current store file is {}",
							current.getName());
				}
			}
		}
		// Where rolled-back data belongs: the live store file
		File live = (current != null) ? current : file;
		// No safe rollback destination exists. In particular, an interrupted
		// marker rewrite must never turn retained targets into a new empty store.
		if (!live.isFile() || live.length()==0L) {
			throw new IOException("Etch GC recovery has no live store for " + file
					+ "; preserve the GC files and restore the completion marker or a backup");
		}

		// Recovery metadata is unauthenticated. Authenticate every non-empty store
		// file which recovery may read, delete or replace before the first mutation.
		// Normal opening deliberately refuses dirty v3 files, apart from abandoned
		// targets that were never checkpointed; callers must select the explicit
		// maintenance/rebuild path for the others.
		Set<File> uncommitted=validateRecoveryFiles(file,current,targets,config);

		// Logged only once recovery acts on the marker: a refusal above reports
		// the problem through its exception instead
		if (markerDiscardReason!=null) {
			debug("Etch GC recovery: marker {} {} - removing the stale marker.",
					marker.getName(),markerDiscardReason);
			deleteOrThrow(marker,files);
		}

		// ---- 2: dispose of defunct targets, roll back abandoned ones ----
		// Defunct files (.gc-defunct tombstone) are superseded cutover originals
		// or cancelled targets: their retained content is verifiably elsewhere,
		// so they are only ever deleted - rolling them back would re-introduce
		// collected garbage. Markerless, non-defunct targets are abandoned
		// cycles whose contents exist nowhere else: rolled back into the live
		// file, unless never checkpointed and so holding no committed root
		for (File st : targets) {
			if (st.getCanonicalFile().equals(current)) continue; // the live file itself
			File tomb = new File(st.getPath() + ".gc-defunct");
			if (tomb.exists()) {
				if (files.delete(st)) {
					files.delete(tomb);
					debug("Etch GC recovery: deleted defunct GC file {} (fully superseded or rolled back)",
							st.getName());
				} else {
					debug("Etch GC recovery: defunct GC file {} still cannot be deleted (pinned by memory"
							+ " mappings?). Harmless - its retained content lives elsewhere - and deletion"
							+ " is retried on later starts.", st.getName());
				}
				continue;
			}
			if (uncommitted.contains(st.getCanonicalFile())) {
				// Its header records no committed root, so no recovery or repair
				// could restore any state from it: the cycle crashed before the
				// target's first sync. The live file passed validation and was not
				// written during the cycle, so it holds the last committed root.
				if (files.delete(st)) {
					debug("Etch GC recovery: deleted abandoned GC target {}, which was never checkpointed"
							+ " and so holds no committed root. {} keeps its last checkpoint.",
							st.getName(), live.getName());
				} else {
					debug("Etch GC recovery: abandoned GC target {} was never checkpointed but cannot be"
							+ " deleted yet (pinned by memory mappings?). It is ignored, and deletion is"
							+ " retried on later starts.", st.getName());
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
			if (st.length()==0L) {
				deleteOrThrow(st,files);
				debug("Etch GC recovery: deleted empty abandoned GC target {}",st.getName());
				continue;
			}
			rollback(live,st,config);
		}

		// ---- 3: adopt the current file under the base name ----
		if (current == null) {
			debug("Etch GC recovery finished for {}: roll-back only, no completed cutover to adopt", file);
			return file;
		}

		// Delete the superseded original first. This deletion IS the disk
		// reclamation unless a backup link retains it: its live content is in the current file
		// (the cutover was hard-gated on a complete sweep) and everything else
		// is garbage by the retention contract. An explicitly retained snapshot
		// has a separate name outside this recovery layout and remains untouched.
		if (file.exists()) {
			if (!files.delete(file)) {
				debug("Etch GC recovery: cannot delete superseded original {} (pinned by memory mappings"
						+ " from this same process, e.g. on Windows?). Adoption DEFERRED: opening the"
						+ " current store file {} directly - it holds the correct data - and the deletion"
						+ " and installation will be retried on the next start.", file, current.getName());
				return current;
			}
			files.delete(new File(file.getPath() + ".gc-defunct"));
		}
		try {
			files.adopt(current.toPath(), file.toPath());
		} catch (IOException e) {
			if (!current.exists()) {
				if (file.isFile() && markerIdentifies(marker,file,config)) return file;
				throw new IOException("GC adoption outcome is ambiguous; preserve recovery evidence",e);
			}
			debug("Etch GC recovery: deleted the superseded original but cannot install {} as {} ({})."
					+ " Opening it directly; the marker is intact, so the next start will complete the"
					+ " adoption.", current.getName(), file.getName(), e);
			return current;
		}
		if (!files.delete(marker)) {
			warn("Etch GC recovery: adoption succeeded but marker {} could not be deleted. Delete it"
					+ " manually; until then, later recoveries will detect it as stale and remove it.",
					marker);
		}
		debug("Etch GC recovery finished: {} now holds the garbage-collected store", file);
		return file;
	}

	private static boolean containsCanonical(List<File> files, File candidate) throws IOException {
		for (File file:files) {
			if (file.getCanonicalFile().equals(candidate)) return true;
		}
		return false;
	}

	/**
	 * Authenticates every participating non-empty file. Dirty v3 files are refused,
	 * except abandoned targets (neither current nor tombstoned) that were never
	 * checkpointed: these are returned for deletion.
	 */
	private static Set<File> validateRecoveryFiles(File base, File current, List<File> targets,
			EtchConfig config) throws IOException {
		LinkedHashSet<File> files=new LinkedHashSet<>();
		LinkedHashSet<File> uncommitted=new LinkedHashSet<>();
		LinkedHashSet<Short> versions=new LinkedHashSet<>();
		if (base.isFile()) files.add(base.getCanonicalFile());
		for (File target:targets) {
			if (target.isFile()) files.add(target.getCanonicalFile());
		}
		for (File candidate:files) {
			if (candidate.length()==0L) continue;
			try (RandomAccessFile data=new RandomAccessFile(candidate,"r");
					AFileMapper mapper=EtchFileMapperFactory.createExisting(data.getChannel(),config,
							candidate.getName(),true)) {
				byte[] masterKey=AEtchHeader.resolveKey(mapper,candidate.getName(),config);
				AEtchHeader header=null;
				try {
					header=AEtchHeader.open(mapper,candidate.getName(),masterKey);
					masterKey=null; // borrowed caller-owned array is not retained
					Etch.resolveExistingConfig(header,config);
					versions.add(header.version());
					long physicalEnd=data.length();
					long storedEnd=header.storedLength();
					if ((storedEnd<0L)||(storedEnd>physicalEnd)) {
						throw new IOException("Etch stored length is outside the physical file: stored="
								+storedEnd+" physical="+physicalEnd+" file="+candidate);
					}
					long rootIndexEnd;
					try {
						rootIndexEnd=Math.addExact(header.indexStart(),
								(long)EtchConstants.ROOT_INDEX_SIZE*EtchConstants.POINTER_SIZE);
					} catch (ArithmeticException e) {
						throw new IOException("Etch index range overflows: "+candidate,e);
					}
					if (rootIndexEnd>storedEnd) {
						throw new IOException("Etch root index extends beyond stored length: "+candidate);
					}
					if ((header instanceof EtchV3Header v3)&&!v3.isCleanClosed()) {
						boolean abandoned=!candidate.equals(base) && !candidate.equals(current)
								&& !new File(candidate.getPath()+".gc-defunct").exists();
						if (!abandoned || !v3.isNeverCheckpointed()) {
							throw new IOException("Etch v3 GC recovery requires a cleanly closed file; "
									+"explicit recovery or repair is required: "+candidate);
						}
						uncommitted.add(candidate);
					}
				} finally {
					if (header!=null) header.destroy();
				}
			}
		}
		if (versions.size()>1) {
			if ((config==null)||!versions.contains(config.getVersion())) {
				throw new IOException("Etch GC recovery files use mixed versions "+versions
						+"; supply an explicit matching Etch version to force cross-version recovery");
			}
			debug("Etch GC recovery explicitly allowing mixed file versions {} under configured v{}",
					versions,config.getVersion());
		}
		return uncommitted;
	}

	static File markerFile(File base) throws IOException {
		return new File(base.getCanonicalPath() + ".gc-complete");
	}

	/**
	 * Reads the file name from a validated completion record; malformed records fail.
	 * This inspection is deliberately side-effect free: recovery authenticates
	 * participating Etch files before changing metadata or the store layout.
	 */
	static String readMarkerName(File marker) throws IOException {
		return readMarker(marker).name();
	}

	private record GCMarker(String name,String identity) {}

	private static GCMarker readMarker(File marker) throws IOException {
		List<String> lines=Files.readAllLines(marker.toPath());
		String name=lines.isEmpty()?"":lines.getFirst().trim();
		if (name.isEmpty()) throw new IOException("Malformed GC completion marker: "+marker);
		// Historical writers emitted exactly one filename line. Accepting arbitrary
		// extra lines would silently downgrade a damaged new identity to legacy.
		if (lines.size()==1) return new GCMarker(name,null);
		if (lines.size()!=4 || !lines.get(1).equals("etch-gc-v1")
				|| !(lines.get(2).equals("legacy") || lines.get(2).matches("v3-salt [0-9a-f]{64}"))
				|| !lines.get(3).matches("attempt [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IOException("Malformed or unsupported GC completion marker; preserve it for inspection: "+marker);
		}
		return new GCMarker(name,lines.get(2).equals("legacy")?null:lines.get(2));
	}

	private static void deleteOrThrow(File f,EtchGCFileOps files) throws IOException {
		if (!files.delete(f)) {
			throw new IOException("Etch GC recovery cannot proceed: unable to delete " + f
					+ ". Check filesystem permissions and remove it manually.");
		}
	}

	static void writeMarker(File base, File target) throws IOException {
		// Legacy single-line publication retained for package compatibility.
		// Lifecycle cutover uses the versioned record from markerContents instead.
		writeMetadata(markerFile(base).toPath(), target.getName() + "\n");
	}

	/** Publishes a complete metadata file without truncating the previous one. */
	static void writeMetadata(Path path, String contents) throws IOException {
		EtchGCFileOps.SYSTEM.metadata(path,contents);
	}

	static String markerContents(Etch target) {
		String identity=target.gcFileIdentity();
		return target.getFile().getName()+"\netch-gc-v1\n"+(identity==null?"legacy":identity)+"\n"
				+"attempt "+java.util.UUID.randomUUID()+"\n";
	}

	private static String fileIdentity(File file,EtchConfig config) throws IOException {
		try (RandomAccessFile data=new RandomAccessFile(file,"r");
				AFileMapper mapper=EtchFileMapperFactory.createExisting(data.getChannel(),config,file.getName(),true)) {
			byte[] key=AEtchHeader.resolveKey(mapper,file.getName(),config);
			AEtchHeader header=AEtchHeader.open(mapper,file.getName(),key);
			try {
				return header instanceof EtchV3Header v3?"v3-salt "+convex.core.util.Utils.toHexString(v3.fileSalt()):null;
			} finally { header.destroy(); }
		}
	}

	private static boolean hasMarkerIdentity(File marker) throws IOException {
		return readMarker(marker).identity()!=null;
	}

	private static boolean markerIdentifies(File marker,File file,EtchConfig config) throws IOException {
		String identity=readMarker(marker).identity();
		return identity!=null && identity.equals(fileIdentity(file,config));
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
		rollback(base,staleTarget,null);
	}

	static void rollback(File base, File staleTarget, EtchConfig config) throws IOException {
		debug("Etch GC recovery: rolling back abandoned GC target {} into {} - data written during"
				+ " that cycle exists nowhere else", staleTarget.getName(), base.getName());
		long copied;
		long[] skipped = {0};
		EtchStore baseStore = new EtchStore(openEtch(base,config)); // direct open: no recursive recovery
		try {
			// The target is opened as a store of its own so decoded refs bind to
			// it: the index scan visits entries in hash order (not post-order),
			// and a parent's persist must be able to resolve children from the
			// SOURCE during its descent. Binding refs to the base store instead
			// would make every parent visited before its children fail
			EtchStore srcStore = new EtchStore(openEtch(staleTarget,config));
			try {
				copied = lenientCopy(srcStore.getEtch(), baseStore, skipped);
				Hash root = srcStore.getEtch().getRootHash();
				List<Hash> missing = EtchVerifier.findMissing(baseStore.getEtch(), root);
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

	private static Etch openEtch(File file, EtchConfig config) throws IOException {
		return (config==null)?Etch.create(file):Etch.create(file,config);
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
				if (e.extractType(slot) == EtchConstants.POINTER_INDEX) {
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
	
	/** @deprecated Use {@link EtchVerifier#findMissing(Etch, Hash)}. */
	@Deprecated
	public static List<Hash> verify(Etch e, Hash rootHash) throws IOException {
		return EtchVerifier.findMissing(e,rootHash);
	}

	/** @deprecated Use {@link EtchVerifier.IndexVisitor}. */
	@Deprecated
	public static FullValidator getFullValidator() {
		return new FullValidator();
	}

	/** @deprecated Use {@link EtchVerifier.IndexVisitor}; this checks the index only. */
	@Deprecated
	public static class FullValidator extends EtchVerifier.IndexVisitor { }

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
				
				if (type==EtchConstants.POINTER_INDEX) continue;
				
				ACell cell=e.readCell(ptr);
				
				visitCell(cell);
			}
		}
		
		protected abstract void visitCell(ACell cell);
	}


}
