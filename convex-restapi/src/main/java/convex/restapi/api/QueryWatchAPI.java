package convex.restapi.api;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
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
import convex.restapi.api.QueryWatch.Event;
import convex.restapi.mcp.SseConnection;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

/** SSE API for queries re-evaluated against finalised consensus state. */
public final class QueryWatchAPI extends ABaseAPI {

	private static final Logger log=LoggerFactory.getLogger(QueryWatchAPI.class);

	public static final int MAX_CONNECTIONS=100;
	public static final int EVENT_QUEUE_CAPACITY=16;
	public static final int MAX_EVENT_CHARS=64*1024;
	public static final int MAX_SOURCE_CHARS=4096;
	private static final long KEEPALIVE_MS=30_000;

	private record Subscription(SseConnection connection, QueryWatch query,
		WatchFormat format, long generation) {}

	private final ConcurrentHashMap<SseConnection,Subscription> subscriptions=new ConcurrentHashMap<>();
	private final AtomicInteger activeConnections=new AtomicInteger();
	private final Object lifecycleLock=new Object();

	private volatile long generation;
	private StateWatcher<Peer> updates;
	private Consumer<Peer> updateObserver;

	public QueryWatchAPI(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(RoutesConfig routes) {
		routes.get("/api/v1/watch",this::watchQuery);
	}

	private void watchQuery(Context ctx) {
		String accept=ctx.header("Accept");
		if ((accept==null)||!accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
			ctx.status(406);
			return;
		}

		QueryWatch query=parseQuery(ctx);
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
			subscribe(connection,query,format);
			synchronized (writer) {
				writer.write(": connected\n\n");
				writer.flush();
			}
			response.flushBuffer();
			if (writer.checkError()) return;

			while (!connection.awaitClosed(KEEPALIVE_MS,TimeUnit.MILLISECONDS)) {
				synchronized (writer) {
					writer.write(": keepalive\n\n");
					writer.flush();
					if (writer.checkError()) connection.close();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			log.debug("Query watch connection setup failed",e);
		} finally {
			if (connection!=null) {
				connection.close();
				unsubscribe(connection);
			}
			activeConnections.decrementAndGet();
		}
	}

	private static QueryWatch parseQuery(Context ctx) {
		List<String> sources=ctx.queryParams("source");
		if (sources.size()!=1) throw new BadRequestResponse("Exactly one query source is required");
		String source=sources.get(0);
		if (source.isBlank()||(source.length()>MAX_SOURCE_CHARS)) {
			throw new BadRequestResponse("Invalid query source");
		}

		ACell form;
		try {
			form=Reader.read(source);
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid query source: "+e.getMessage());
		}

		List<String> addresses=ctx.queryParams("address");
		if (addresses.size()>1) throw new BadRequestResponse("Specify query address only once");
		Address address=null;
		if (!addresses.isEmpty()) {
			address=Address.parse(addresses.get(0));
			if (address==null) throw new BadRequestResponse("Invalid query address: "+addresses.get(0));
		}
		return new QueryWatch(form,address);
	}

	private static WatchFormat parseFormat(Context ctx) {
		List<String> values=ctx.queryParams("format");
		if (values.isEmpty()) return WatchFormat.JSON;
		if (values.size()!=1) throw new BadRequestResponse("Specify format only once");
		return switch (values.get(0).toLowerCase(Locale.ROOT)) {
			case "json" -> WatchFormat.JSON;
			case "cvx" -> WatchFormat.CVX;
			default -> throw new BadRequestResponse("Unsupported query-watch format: "+values.get(0));
		};
	}

	private void subscribe(SseConnection connection, QueryWatch query, WatchFormat format) {
		synchronized (lifecycleLock) {
			boolean start=(updates==null);
			if (start) {
				long currentGeneration=++generation;
				updates=new StateWatcher<>(peer->evaluateAll(currentGeneration,peer));
				updateObserver=updates::update;
			}
			subscriptions.put(connection,new Subscription(connection,query,format,generation));
			if (start) {
				server.addStateUpdateObserver(updateObserver);
			} else {
				updates.update(server.getPeer());
			}
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

	private void evaluateAll(long sourceGeneration, Peer peer) {
		if ((peer==null)||(sourceGeneration!=generation)) return;
		for (Subscription subscription:subscriptions.values()) {
			if ((sourceGeneration!=generation)||(subscription.generation()!=sourceGeneration)) continue;
			SseConnection connection=subscription.connection();
			if (connection.isClosed()) continue;
			try {
				Event event=subscription.query().evaluate(peer);
				if (event==null) continue;
				String data=QueryWatch.encode(event,subscription.format(),MAX_EVENT_CHARS);
				if (data==null) {
					connection.close();
				} else {
					connection.sendEvent(Long.toString(event.position()),"result",data);
				}
			} catch (Exception e) {
				connection.close();
				log.debug("Closing query watch after evaluation failed",e);
			}
		}
	}

	/** Stops observation and closes all active query streams. */
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
