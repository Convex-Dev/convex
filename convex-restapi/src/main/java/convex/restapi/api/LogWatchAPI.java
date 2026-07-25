package convex.restapi.api;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.util.StateWatcher;
import convex.restapi.RESTServer;
import convex.restapi.api.ConsensusLogScanner.LogEvent;
import convex.restapi.api.LogWatch.Filter;
import convex.restapi.mcp.SseConnection;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

/** SSE API for filtered finalised consensus log events. */
public final class LogWatchAPI extends ABaseAPI {

	private static final Logger log=LoggerFactory.getLogger(LogWatchAPI.class);

	public static final int MAX_CONNECTIONS=100;
	public static final int MAX_FILTER_VALUES=16;
	public static final int EVENT_QUEUE_CAPACITY=16;
	public static final int MAX_EVENT_CHARS=64*1024;
	public static final int MAX_FILTER_SOURCE_CHARS=1024;
	private static final long KEEPALIVE_MS=30_000;

	private record Subscription(SseConnection connection, Filter filter, WatchFormat format,
		long generation, long nextBlock) {}

	private final ConcurrentHashMap<SseConnection,Subscription> subscriptions=new ConcurrentHashMap<>();
	private final AtomicInteger activeConnections=new AtomicInteger();
	private final Object lifecycleLock=new Object();

	private volatile long generation;
	private StateWatcher<Peer> updates;
	private Consumer<Peer> updateObserver;

	public LogWatchAPI(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(RoutesConfig routes) {
		routes.get("/api/v1/watch/logs",this::watchLogs);
	}

	private void watchLogs(Context ctx) {
		String accept=ctx.header("Accept");
		if ((accept==null)||!accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
			ctx.status(406);
			return;
		}

		Filter filter=parseFilter(ctx);
		WatchFormat format=parseFormat(ctx);
		if (activeConnections.incrementAndGet()>MAX_CONNECTIONS) {
			activeConnections.decrementAndGet();
			ctx.status(429);
			return;
		}

		SseConnection connection=null;
		try {
			HttpServletResponse response=ctx.res();
			response.setContentType("text/event-stream");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control","no-cache");
			response.setHeader("X-Accel-Buffering","no");

			PrintWriter writer=response.getWriter();
			connection=new SseConnection(writer,EVENT_QUEUE_CAPACITY);
			connection.sendComment("connected");
			subscribe(connection,filter,format);
			while (!connection.awaitClosed(KEEPALIVE_MS,TimeUnit.MILLISECONDS)) {
				connection.sendComment("keepalive");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			log.debug("Log watch connection setup failed",e);
		} finally {
			if (connection!=null) {
				connection.close();
				unsubscribe(connection);
			}
			activeConnections.decrementAndGet();
		}
	}

	private Filter parseFilter(Context ctx) {
		List<String> addressValues=boundedValues(ctx,"address",true);
		Set<Address> addresses=new HashSet<>();
		for (String source:addressValues) {
			Address address=Address.parse(source);
			if (address==null) throw new BadRequestResponse("Invalid address filter: "+source);
			addresses.add(address);
		}
		return new Filter(addresses,parseCells(ctx,"event"),parseCells(ctx,"scope"));
	}

	private Set<ACell> parseCells(Context ctx, String name) {
		List<String> values=boundedValues(ctx,name,false);
		Set<ACell> result=new HashSet<>();
		for (String source:values) {
			try {
				result.add(Reader.read(source));
			} catch (Exception e) {
				throw new BadRequestResponse("Invalid "+name+" filter: "+source);
			}
		}
		return result;
	}

	private static List<String> boundedValues(Context ctx, String name, boolean required) {
		List<String> values=ctx.queryParams(name);
		if (required&&values.isEmpty()) throw new BadRequestResponse("At least one address filter is required");
		if (values.size()>MAX_FILTER_VALUES) {
			throw new BadRequestResponse("Too many "+name+" filters (max "+MAX_FILTER_VALUES+")");
		}
		for (String value:values) {
			if ((value==null)||value.isBlank()||(value.length()>MAX_FILTER_SOURCE_CHARS)) {
				throw new BadRequestResponse("Invalid "+name+" filter");
			}
		}
		return values;
	}

	private static WatchFormat parseFormat(Context ctx) {
		List<String> values=ctx.queryParams("format");
		if (values.isEmpty()) return WatchFormat.JSON;
		if (values.size()!=1) throw new BadRequestResponse("Specify format only once");
		return switch (values.get(0).toLowerCase(Locale.ROOT)) {
			case "json" -> WatchFormat.JSON;
			case "cvx" -> WatchFormat.CVX;
			default -> throw new BadRequestResponse("Unsupported log format: "+values.get(0));
		};
	}

	private void subscribe(SseConnection connection, Filter filter, WatchFormat format) {
		synchronized (lifecycleLock) {
			boolean start=(updates==null);
			Peer peer=server.getPeer();
			long nextBlock=(peer==null)?0:peer.getStatePosition();
			if (start) {
				long currentGeneration=++generation;
				ConsensusLogScanner scanner=new ConsensusLogScanner(nextBlock,
					event->distribute(currentGeneration,event));
				updates=new StateWatcher<>(scanner);
				updateObserver=updates::update;
			}
			subscriptions.put(connection,
				new Subscription(connection,filter,format,generation,nextBlock));
			if (start) server.addStateUpdateObserver(updateObserver);
		}
	}

	private void unsubscribe(SseConnection connection) {
		synchronized (lifecycleLock) {
			subscriptions.remove(connection);
			if (!subscriptions.isEmpty()||(updates==null)) return;
			generation++;
			server.removeStateUpdateObserver(updateObserver);
			updates.close();
			updates=null;
			updateObserver=null;
		}
	}

	private void distribute(long sourceGeneration, LogEvent event) {
		if (sourceGeneration!=generation) return;
		String json=null;
		String cvx=null;
		for (Subscription subscription:subscriptions.values()) {
			if ((sourceGeneration!=generation)||(subscription.generation()!=sourceGeneration)
				||(event.blockIndex()<subscription.nextBlock())) continue;
			SseConnection connection=subscription.connection();
			if (connection.isClosed()||!subscription.filter().matches(event)) continue;
			try {
				String data;
				if (subscription.format()==WatchFormat.JSON) {
					if (json==null) json=LogWatch.encode(event,WatchFormat.JSON);
					data=json;
				} else {
					if (cvx==null) cvx=LogWatch.encode(event,WatchFormat.CVX);
					data=cvx;
				}
				if (data.length()>MAX_EVENT_CHARS) {
					connection.close();
				} else {
					connection.sendEvent(LogWatch.eventID(event),"log",data);
				}
			} catch (Exception e) {
				connection.close();
				log.debug("Closing log watch after event encoding failed",e);
			}
		}
	}

	/** Stops observation and closes all active log streams. */
	public void shutdown() {
		for (SseConnection connection:subscriptions.keySet()) connection.close();
		synchronized (lifecycleLock) {
			generation++;
			if (updateObserver!=null) server.removeStateUpdateObserver(updateObserver);
			if (updates!=null) updates.close();
			updates=null;
			updateObserver=null;
			subscriptions.clear();
		}
	}
}
