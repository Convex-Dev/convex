package convex.observer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.SignedData;
import convex.peer.Server;

public class LogObserver {
	static final Logger log = LoggerFactory.getLogger(LogObserver.class.getName());

	protected Server server;

	public LogObserver(Server server) {
		this.server=server;
	}

	public Consumer<SignedData<ATransaction>> getTransactionRequestObserver() {
		return stx->{
			log.info("TX Request: {}",stx);
		};
	}
	
	public BiConsumer<SignedData<ATransaction>,Result> getTransactionResponseObserver() {
		return (tx,r)->{
			log.info("TX Response: {} for tx {}",r,tx);
		};
	}
}
