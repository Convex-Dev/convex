package convex.etch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;

/**
 * Online export of an explicit root, independent of the source's live root/GC.
 *
 * <p>Recorded status is sampled per entry from the files leased at entry; later
 * announcements, including ones in later GC generations, need not be included.
 * Children are persisted before a parent, without announcing its descendants.
 * The source's caches, refs, root and configuration are never changed.</p>
 *
 * <p>The child-first copy establishes completeness. A separate full scan is
 * available through {@link EtchVerifier#verifyPersisted(Etch)} when the caller wants verification.</p>
 *
 * <p>A private staging file beside the destination is flushed and
 * checked closed before no-replace hard-link publication. The link is to the
 * newly built file, never the source. This works across source/destination
 * filesystems and with retained Windows MBB mappings. The destination filesystem
 * must support hard links. A process crash cannot expose a partially copied
 * checkpoint at the requested name. File contents are forced, but durability of
 * the new directory entry across power loss remains filesystem/platform dependent.
 * Failure may leave private staging files, never a published partial checkpoint.
 * Once published, cleanup is best effort and cannot revoke success.</p>
 */
final class EtchCheckpoint {
	private EtchCheckpoint() { }

	static File export(EtchStore source, Hash root, File destination, EtchConfig config) throws IOException {
		return export(source,root,destination,config,new IO());
	}

	// Per-operation I/O boundary also permits deterministic fault/concurrency tests.
	static class IO {
		EtchStore create(File file, EtchConfig config) throws IOException {
			// Export's private working cache is bounded; runtime policy is not on disk.
			return new EtchStore(Etch.create(file,config),false);
		}
		void copied(Hash hash) throws IOException { }
		void flush(Etch file) throws IOException { file.flush(); }
		void close(Etch file) throws IOException { file.closeChecked(); }
		void publish(Path destination, Path staging) throws IOException { Files.createLink(destination,staging); }
		void cleanup(Path staging) throws IOException { Files.deleteIfExists(staging); }
	}

	static File export(EtchStore source, Hash root, File destination, EtchConfig config, IO io) throws IOException {
		Objects.requireNonNull(root,"root");
		Objects.requireNonNull(config,"config");
		Path output=source.validateGCBackup(Objects.requireNonNull(destination,"destination")).toPath();
		validateLayout(output);
		EtchTreeTransfer.checkInterrupted();
		EtchReadView lease=source.lease();
		try {
			// Validate availability before creating anything in the destination directory.
			if (!EtchTreeTransfer.emptyRoot(root) && lease.read(root)==null) throw new MissingDataException(source,root);
			Path stage=Files.createTempFile(output.getParent(),".etch-checkpoint-",".partial");
			EtchStore destinationStore=null;
			try {
				// Fail early if this filesystem cannot support the publication contract.
				Path probe=Path.of(stage+".link");
				Files.createLink(probe,stage);
				try { Files.delete(probe); }
				catch (IOException e) { probe.toFile().deleteOnExit(); throw e; }
				destinationStore=io.create(stage.toFile(),config);
				copy(lease,destinationStore,root,io);
				Etch file=destinationStore.getEtch();
				file.setRootHash(root); // Preserve even the exact nil/unset sentinel.
				EtchTreeTransfer.checkInterrupted();
				io.flush(file);
				io.close(file);
				EtchTreeTransfer.checkInterrupted();
				validateLayout(output);
				io.publish(output,stage); // Commit point: never delete output during cleanup.
				return output.toFile();
			} finally {
				// FileChannel.force on an interrupted thread closes the channel. Finish
				// resource cleanup without poisoning files, then restore cancellation.
				boolean interrupted=Thread.interrupted();
				try {
					if (destinationStore!=null) destinationStore.close();
					try { io.cleanup(stage); }
					catch (IOException e) { stage.toFile().deleteOnExit(); }
				} finally { if (interrupted) Thread.currentThread().interrupt(); }
			}
		} finally {
			boolean interrupted=Thread.interrupted();
			try { lease.close(); }
			finally { if (interrupted) Thread.currentThread().interrupt(); }
		}
	}

	private static void validateLayout(Path output) throws IOException {
		if (Files.exists(output,LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(Path.of(output+".gc-complete"),LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(Path.of(output+".gc-defunct"),LinkOption.NOFOLLOW_LINKS)
				|| !EtchUtils.gcTargets(output.toFile()).isEmpty()) {
			throw new IOException("Checkpoint destination or its GC files already exist: "+output);
		}
	}

	private static void copy(EtchReadView source, EtchStore dest, Hash root, IO io) throws IOException {
		Etch target=dest.getEtch();
		EtchTreeTransfer.transfer(root,new EtchTreeTransfer.Access() {
			@Override public Ref<ACell> read(Hash hash) throws IOException {
				Ref<ACell> ref=source.read(hash);
				if (ref==null) throw new MissingDataException(source,hash);
				return ref;
			}
			@Override public boolean contains(Hash hash) throws IOException {
				Ref<?> ref=target.read(hash);
				return ref!=null && ref.getStatus()>=Ref.PERSISTED;
			}
			@Override public void write(Ref<ACell> ref) throws IOException {
				int status=Math.max(Ref.PERSISTED,Math.min(Ref.MAX_STATUS,ref.getStatus()));
				Ref<ACell> persisted=dest.storeTopRef(ref,Ref.PERSISTED,null);
				// This entry alone inherits its recorded announcement, after persistence.
				if (status>Ref.PERSISTED) target.write(ref.getHash(),persisted.withStatus(status));
				io.copied(ref.getHash());
			}
		});
	}

}
