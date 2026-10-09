package convex.etch;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;

/**
 * GC and file lifetime for one logical Etch store, shared by all its generations.
 * Handles own their caches and reference bindings; this coordinator owns routing,
 * root serialisation and collection. No routing object is allocated by a persist.
 */
final class EtchGC {

	/** Published only at lifecycle boundaries. Views are newest first. */
	static final class State {
		final EtchStore owner;
		final EtchStore[] views;
		final Etch target;
		final boolean cancelling;

		State(EtchStore owner, EtchStore[] views, Etch target, boolean cancelling) {
			this.owner=owner;
			this.views=views;
			this.target=target;
			this.cancelling=cancelling;
		}

		Etch writeEtch() {
			return (target==null || cancelling)?owner.getEtch():target;
		}
	}

	private volatile State state;
	private File baseFile;
	// Lifecycle operations take operations, then writes (if needed), then roots.
	// Cancellation holds operations/roots but allows ordinary persists to proceed.
	private final ReentrantLock operations=new ReentrantLock();
	private final ReentrantReadWriteLock writes=new ReentrantReadWriteLock();
	private final Object roots=new Object();
	private final AtomicInteger targetWriters=new AtomicInteger();
	private final Object drainSignal=new Object();
	private final AtomicBoolean sweepRunning=new AtomicBoolean();
	private volatile boolean sweepComplete;
	// Accessed only under operations. No lease bookkeeping on read/write hot paths.
	private final IdentityHashMap<Etch,Pin> pins=new IdentityHashMap<>();
	private static final class Pin {
		int readers;
		boolean retired;
		boolean delete;
	}

	EtchGC(EtchStore initial) {
		state=new State(initial,new EtchStore[] {initial},null,false);
		baseFile=initial.getFile();
	}

	File getBaseFile() { return baseFile; }
	void setBaseFile(File file) { baseFile=file; }

	boolean isInProgress(EtchStore store) {
		State s=state;
		return s.owner==store && !store.closed && s.target!=null;
	}

	boolean isComplete(EtchStore store) {
		State s=state;
		return s.owner==store && !store.closed && s.target!=null && !s.cancelling && sweepComplete;
	}

	Etch getTarget(EtchStore store) {
		State s=state;
		return (s.owner==store)?s.target:s.writeEtch();
	}

	private void requireCurrent(EtchStore store) {
		if (store.closed || state.owner!=store) {
			throw new IllegalStateException("GC requires the open current store: "+store);
		}
	}

	/** Callbacks run inside a persist; blocking on a lifecycle/root lock can deadlock. */
	private void rejectPersistenceCallback() {
		if (writes.getReadHoldCount()!=0) {
			throw new IllegalStateException("Cannot change GC lifecycle or root from a persistence callback");
		}
	}

	void start(EtchStore store) throws IOException {
		rejectPersistenceCallback();
		operations.lock();
		writes.writeLock().lock();
		try {
			synchronized (roots) {
				requireCurrent(store);
				State s=state;
				if (s.target!=null) throw new IllegalStateException("GC already in progress: "+store);
				String base=baseFile.getCanonicalPath()+"~";
				File file=new File(base);
				for (int i=1; ; i++) {
					File tomb=new File(file.getPath()+".gc-defunct");
					if (!file.exists()) {
						tomb.delete();
						break;
					}
					// A closed intermediate handle can still be pinned by an older view.
					if (!retains(s,file) && tomb.exists() && file.delete()) {
						tomb.delete();
						break;
					}
					if (i>=100) throw new IllegalStateException("Too many stale GC target files: "+base);
					file=new File(base+i);
				}
				Etch target=Etch.create(file,store.getConfig());
				try {
					target.setStore(store);
					target.setRootHash(store.getEtch().getRootHash());
				} catch (IOException | RuntimeException | Error e) {
					target.close();
					throw e;
				}
				sweepComplete=false;
				state=new State(store,s.views,target,false);
			}
		} finally {
			writes.writeLock().unlock();
			operations.unlock();
		}
	}

	private boolean retains(State s, File file) throws IOException {
		File canonical=file.getCanonicalFile();
		for (Etch pinned:pins.keySet()) {
			if (pinned.getFile().getCanonicalFile().equals(canonical)) return true;
		}
		for (EtchStore view:s.views) {
			if (view.getFile().getCanonicalFile().equals(canonical)) return true;
		}
		return false;
	}

	/** Pins the files visible now, independently of the caller's handle lifetime. */
	EtchReadView lease(EtchStore store) throws IOException {
		rejectPersistenceCallback();
		try { operations.lockInterruptibly(); }
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while acquiring Etch read lease");
		}
		try {
			store.checkOpen();
			State s=state;
			ArrayList<Etch> files=new ArrayList<>();
			if (s.target!=null) files.add(s.target);
			for (EtchStore view:s.views) {
				files.add(view.getEtch());
				if (view==store) break;
			}
			Etch[] retained=files.toArray(Etch[]::new);
			for (Etch file:retained) pins.computeIfAbsent(file,k->new Pin()).readers++;
			return new EtchReadView(retained,()->release(retained));
		} finally { operations.unlock(); }
	}

	private void release(Etch[] files) {
		operations.lock();
		try {
			for (Etch file:files) {
				Pin pin=pins.get(file);
				if (--pin.readers==0) {
					pins.remove(file);
					if (pin.retired) retire(file,pin.delete);
				}
			}
		} finally { operations.unlock(); }
	}

	private void retire(Etch etch, boolean delete) {
		Pin pin=pins.get(etch);
		if (pin!=null) {
			pin.retired=true;
			pin.delete|=delete;
			return;
		}
		etch.close();
		if (delete) {
			File file=etch.getFile();
			if (file.delete()) new File(file.getPath()+".gc-defunct").delete();
			else file.deleteOnExit();
		}
	}

	<T extends ACell> Ref<T> persist(EtchStore store, Ref<T> ref, int status,
			Consumer<Ref<ACell>> novelty, boolean topLevel) throws IOException {
		return persist(store,ref,status,novelty,topLevel,null);
	}

	private <T extends ACell> Ref<T> persist(EtchStore store, Ref<T> ref, int status,
			Consumer<Ref<ACell>> novelty, boolean topLevel, Etch sweepTarget) throws IOException {
		writes.readLock().lock();
		boolean registered=false;
		try {
			store.checkOpen();
			State s=state;
			s.owner.checkOpen();
			if (s.target!=null && !s.cancelling) {
				targetWriters.incrementAndGet();
				registered=true;
				s=state; // Pair registration with cancellation's redirect-before-drain.
			}
			if (sweepTarget!=null && (s.target!=sweepTarget || s.cancelling)) {
				throw new IllegalStateException("GC cycle ended during transfer");
			}
			return store.storeRef(ref,status,novelty,topLevel,s);
		} finally {
			if (registered && targetWriters.decrementAndGet()==0 && state.cancelling) {
				synchronized (drainSignal) { drainSignal.notifyAll(); }
			}
			writes.readLock().unlock();
		}
	}

	<T extends ACell> Ref<T> read(EtchStore store, Hash hash) throws IOException {
		store.checkOpen();
		State s=state;
		if (s.target!=null) {
			try {
				Ref<T> ref=s.target.read(hash,store);
				if (ref!=null) return ref;
			} catch (ClosedChannelException e) {
				if (state.target==s.target) throw e;
				// A cancelled target has been copied back into its source.
			}
		}
		for (EtchStore view:s.views) {
			Ref<T> ref=view.getEtch().read(hash,store);
			if (ref!=null) return ref;
			if (view==store) break; // Successors must never resurrect predecessor garbage.
		}
		return null;
	}

	/** Recorded status from this handle's retained files, never incoming ref flags. */
	int retainedStatus(EtchStore store, State s, Etch destination, Hash hash) throws IOException {
		int status=Ref.UNKNOWN;
		for (EtchStore view:s.views) {
			Etch file=view.getEtch();
			if (file!=destination) {
				Ref<?> old=file.read(hash,store);
				if (old!=null) status=Math.max(status,Math.min(old.getStatus(),Ref.MAX_STATUS));
			}
			if (view==store) break;
		}
		return status;
	}

	Hash getRootHash(EtchStore store) throws IOException {
		store.checkOpen();
		State s=state;
		if (s.target!=null) {
			try { return s.target.getRootHash(); }
			catch (ClosedChannelException e) { if (state.target==s.target) throw e; }
		}
		return s.owner.getEtch().getRootHash();
	}

	<T extends ACell> Ref<T> setRootData(EtchStore store, T data) throws IOException {
		rejectPersistenceCallback();
		synchronized (roots) {
			store.checkOpen();
			State s=state;
			s.owner.checkOpen();
			// A failed cancel leaves the target root authoritative for retry/recovery.
			// Updating only the source here would lose the new root on copy-back.
			if (s.cancelling) throw new IOException("GC cancellation incomplete; retry cancelGC before updating the root");
			Ref<T> ref=store.storeRef(Ref.get(data),Ref.PERSISTED,null,true,s);
			Etch file=s.writeEtch();
			file.setRootHash(Hash.get(data));
			file.writeDataLength();
			return ref;
		}
	}

	void transfer(EtchStore store) throws IOException {
		rejectPersistenceCallback();
		State s=state;
		requireCurrent(store);
		Etch target=s.target;
		if (target==null || s.cancelling) throw new IllegalStateException("No active GC cycle: "+store);
		if (!sweepRunning.compareAndSet(false,true)) throw new IllegalStateException("GC transfer already running");
		try {
			Hash hash=store.getRootHash();
			Ref<ACell> root=store.getRootRef();
			if (root==null) throw new MissingDataException(store,hash);
			if (root.getValue()!=null) sweep(store,target,root);
			synchronized (roots) {
				if (state.target!=target || state.cancelling) throw new IllegalStateException("GC cycle ended during transfer");
				sweepComplete=true;
			}
		} finally { sweepRunning.set(false); }
	}

	private void sweep(EtchStore store, Etch target, Ref<ACell> root) throws IOException {
		EtchTreeTransfer.transfer(root.getHash(),new EtchTreeTransfer.Access() {
			@Override public void check() throws IOException {
				EtchTreeTransfer.checkInterrupted();
				if (state.target!=target || state.cancelling) throw new IllegalStateException("GC cycle ended during transfer");
			}
			@Override public Ref<ACell> read(Hash hash) throws IOException {
				Ref<ACell> ref=EtchGC.this.read(store,hash);
				if (ref==null) throw new MissingDataException(store,hash);
				return ref;
			}
			@Override public boolean contains(Hash hash) throws IOException {
				Ref<?> existing=target.read(hash,store);
				return existing!=null && existing.getStatus()>=Ref.PERSISTED;
			}
			@Override public void write(Ref<ACell> ref) throws IOException {
				persist(store,ref,Ref.PERSISTED,null,true,target);
			}
		});
	}

	java.util.List<Hash> verify(EtchStore store) throws IOException {
		rejectPersistenceCallback();
		operations.lock();
		try {
			requireCurrent(store);
			if (state.target==null) throw new IllegalStateException("No GC cycle: "+store);
			return EtchVerifier.findMissing(state.target,getRootHash(store));
		} finally { operations.unlock(); }
	}

	void cancel(EtchStore store) throws IOException {
		rejectPersistenceCallback();
		operations.lock();
		try {
			synchronized (roots) {
				requireCurrent(store);
				State s=state;
				Etch target=s.target;
				if (target==null) throw new IllegalStateException("No GC cycle: "+store);
				state=new State(store,s.views,target,true);
				synchronized (drainSignal) {
					try { while (targetWriters.get()!=0) drainSignal.wait(); }
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while draining GC writers",e);
					}
				}
				EtchUtils.migrate(target,store);
				Etch source=store.getEtch();
				source.setRootHash(target.getRootHash());
				source.writeDataLength();
				source.flush();
				// An export may retain the cancelled file beyond further collections.
				// Mark it before retiring it so a crash never resurrects its garbage.
				File file=target.getFile();
				EtchUtils.writeMetadata(new File(file.getPath()+".gc-defunct").toPath(),"rolled back by cancelGC\n");
				state=new State(store,s.views,null,false);
				sweepComplete=false;
				retire(target,true);
			}
		} finally { operations.unlock(); }
	}

	EtchStore complete(EtchStore store, File backupFile) throws IOException {
		rejectPersistenceCallback();
		operations.lock();
		writes.writeLock().lock();
		try {
			synchronized (roots) {
				requireCurrent(store);
				State s=state;
				Etch target=s.target;
				if (target==null || s.cancelling || !sweepComplete) throw new IllegalStateException("GC transfer not complete: "+store);
				java.util.List<Hash> missing=EtchVerifier.findMissing(target,target.getRootHash());
				if (!missing.isEmpty()) throw new MissingDataException(store,missing.get(0));
				target.flush();
				Path backup=null;
				if (backupFile!=null) {
					backup=store.validateGCBackup(backupFile).toPath();
					store.getEtch().flush();
					Files.createLink(backup,store.getFile().toPath());
				}
				Path tomb=new File(store.getFile().getCanonicalPath()+".gc-defunct").toPath();
				try {
					EtchUtils.writeMetadata(tomb,"superseded by "+target.getFile().getName()+"\n");
					EtchUtils.writeMarker(baseFile,target.getFile());
				} catch (IOException e) {
					try { Files.deleteIfExists(tomb); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
					if (backup!=null) {
						try { Files.deleteIfExists(backup); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
					}
					throw e;
				}
				EtchStore successor=new EtchStore(target,store.getConfig(),this);
				EtchStore[] views=new EtchStore[s.views.length+1];
				views[0]=successor;
				System.arraycopy(s.views,0,views,1,s.views.length);
				state=new State(successor,views,null,false);
				sweepComplete=false;
				return successor;
			}
		} finally {
			writes.writeLock().unlock();
			operations.unlock();
		}
	}

	void flush(EtchStore store) throws IOException {
		writes.readLock().lock();
		try {
			store.checkOpen();
			State s=state;
			for (EtchStore view:s.views) {
				view.getEtch().flush();
				if (view==store) break;
			}
			if (s.target!=null) {
				try { s.target.flush(); }
				catch (ClosedChannelException e) { if (state.target==s.target) throw e; }
			}
		} finally { writes.readLock().unlock(); }
	}

	void close(EtchStore store) {
		rejectPersistenceCallback();
		operations.lock();
		writes.writeLock().lock();
		try {
			synchronized (roots) {
				if (store.closed) return;
				store.closed=true;
				State s=state;
				int retained=s.views.length;
				while (retained>0 && s.views[retained-1].closed) {
					EtchStore view=s.views[--retained];
					retire(view.getEtch(),view!=s.owner);
				}
				if (retained==0 && s.target!=null) retire(s.target,false); // abandoned cycle: recover on reopen
				state=new State(s.owner,Arrays.copyOf(s.views,retained),s.target,s.cancelling);
			}
		} finally {
			writes.writeLock().unlock();
			operations.unlock();
		}
	}
}
