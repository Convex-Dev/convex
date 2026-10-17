package convex.gui.peer;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import convex.core.cvm.Peer;
import convex.peer.Server;

/**
 * Runs a UI update on the EDT whenever a local server's peer state changes.
 * Bursts of changes collapse into one update. Closing stops the updates.
 */
final class PeerStateUpdates implements AutoCloseable {

	private final Server server;
	private final Runnable update;
	private final AtomicBoolean pending=new AtomicBoolean();
	private volatile boolean closed=false;

	/** Called on the CVM executor thread, so only schedules the update */
	private final Consumer<Peer> observer=p->{
		if (pending.compareAndSet(false, true)) {
			EventQueue.invokeLater(this::runUpdate);
		}
	};

	/**
	 * Starts updates. The first is scheduled straight away.
	 * @param server Local server to follow
	 * @param update Update to run on the EDT
	 */
	PeerStateUpdates(Server server, Runnable update) {
		this.server=server;
		this.update=update;
		server.addStateUpdateObserver(observer);
	}

	private void runUpdate() {
		pending.set(false);
		if (!closed) update.run();
	}

	@Override
	public void close() {
		closed=true;
		server.removeStateUpdateObserver(observer);
	}
}
