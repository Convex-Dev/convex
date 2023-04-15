package convex.net.message;

import java.util.function.Consumer;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.AStore;
import convex.net.Connection;
import convex.net.MessageType;
import convex.peer.Server;

/**
 * Class representing a message to a local Server instance
 */
public class MessageLocal extends Message {

	protected Server server;
	protected AStore store;
	protected Consumer<Result> resultHandler;
	
	protected MessageLocal(MessageType type, ACell payload, Server server, Consumer<Result> handler) {
		super(type, payload,null);
		this.server=server;
		this.resultHandler=handler;
	}
	
	/**
	 * Create an instance with the given message data
	 * @param type Message type
	 * @param payload Message payload
	 * @param server Local server instance
	 * @param handler Handler for Results
	 * @return New MessageLocal instance
	 */
	public static MessageLocal create(MessageType type, ACell payload, Server server, Consumer<Result> handler) {
		return new MessageLocal(type,payload,server,handler);
	}

	@Override
	public boolean reportResult(Result res) {
		ACell id=getID();
		if (id!=null) res=res.withID(id);
		
		resultHandler.accept(res);
		return true;
	}

	@Override
	public String getOriginString() {
		return "Local Peer";
	}

	@Override
	public boolean sendData(ACell data) {
		if (data!=null) {
			store.storeRef(data.getRef(), Ref.STORED, null);
		}
		return true;
	}

	@Override
	public boolean sendMissingData(Hash hash) {
		Ref<ACell> ref=server.getStore().refForHash(hash);
		if (ref!=null) {
			store.storeRef(ref, Ref.STORED, null);
		} 
		return true;
	}

	@Override
	public Connection getConnection() {
		return null;
	}

}
