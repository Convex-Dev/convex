package convex.p2p;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import convex.core.Constants;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.AccessList;
import convex.lattice.cursor.AForkableCursor;
import convex.net.Transports;

/**
 * The nodes and addresses this node refuses, kept as an {@link AccessList} at
 * {@code :blocked} in its {@code :local} P2P record beside its contact records
 * ({@link ContactRecords}), or in memory for a node without a key. An entry is one of:
 *
 * <ul>
 * <li>a node key, which refuses that node: its record, its connections and the
 * messages it sends or would be sent;</li>
 * <li>a transport address such as {@code tcp://host:port}, which refuses dialling
 * that address;</li>
 * <li>a bare host, which refuses dialling any address at that host and, for an IP
 * address, connections from it.</li>
 * </ul>
 *
 * <p>An address is stored in one form, so a check matches however it was written:
 * the scheme and host in lower case, the port given explicitly ({@value
 * Constants#DEFAULT_PEER_PORT} when absent), and an IP address in standard text, an
 * IPv6 address without brackets or scope as a bare host and an IPv4 address written
 * in IPv6 as IPv4. A string containing {@code ://} is a transport address; any other
 * is a host. Host names are matched as written, without DNS lookups, so a host-name
 * entry does not refuse connections from that host.</p>
 */
final class Blocklist {

	/** The field of this node's {@code :local} P2P record holding the list. */
	static final Keyword KEY=Keyword.intern("blocked");

	private static final String OCTET="(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
	private static final Pattern IPV4=Pattern.compile(OCTET+"(\\."+OCTET+"){3}");
	private static final Pattern IPV6=Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");
	private static final Pattern HOST_NAME=Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");

	private final AccessList<ABlobLike<?>> list;

	Blocklist(AForkableCursor<Index<ABlobLike<?>,ACell>> cursor,Supplier<CVMLong> clock) {
		this.list=new AccessList<ABlobLike<?>>(cursor,clock);
	}

	/** Blocks a node. Returns whether it was not blocked before. */
	boolean block(AccountKey node) {
		return list.add(node);
	}

	/**
	 * Blocks a transport address or a host. Returns whether it was not blocked before.
	 *
	 * @throws IllegalArgumentException if the address is neither
	 */
	boolean block(AString address) {
		return list.add(address(address));
	}

	/** Unblocks a node. Returns whether it was blocked. */
	boolean unblock(AccountKey node) {
		return list.remove(node);
	}

	/** Unblocks a transport address or a host. Returns whether it was blocked. */
	boolean unblock(AString address) {
		return list.remove(address(address));
	}

	/** Whether a node is blocked. */
	boolean isBlocked(AccountKey node) {
		return node!=null && list.contains(node);
	}

	/** Whether this transport address or host is itself on the list. */
	boolean isBlocked(AString address) {
		return list.contains(address(address));
	}

	/**
	 * Whether this node refuses to dial a transport: its address or its host is
	 * blocked. A transport that cannot be parsed is left to the dialler, which skips it.
	 */
	boolean refuses(AString transport) {
		URI endpoint;
		try {
			endpoint=Transports.endpoint(transport.toString());
		} catch (IllegalArgumentException e) {
			return false;
		}
		String host=host(endpoint.getHost());
		if (host==null) return false;
		return list.contains(Strings.create(host)) || list.contains(Strings.create(address(endpoint,host)));
	}

	/** Whether this node refuses a connection from a remote address: its IP address is blocked. */
	boolean refuses(InetSocketAddress remote) {
		if (remote==null || remote.getAddress()==null) return false;
		return list.contains(Strings.create(ip(remote.getAddress())));
	}

	/** The transports a node advertises without those this node refuses to dial; the same vector if none is refused. */
	AVector<AString> dialable(AVector<AString> transports) {
		if (transports==null) return null;
		AVector<AString> kept=Vectors.empty();
		long n=transports.count();
		for (long i=0; i<n; i++) {
			AString transport=transports.get(i);
			if (transport==null || !refuses(transport)) kept=kept.conj(transport);
		}
		return (kept.count()==n)?transports:kept;
	}

	/**
	 * Puts an address in stored form.
	 *
	 * @throws IllegalArgumentException if it is neither a transport address nor a host
	 */
	static AString address(AString address) {
		if (address==null) throw new IllegalArgumentException("Address must not be null");
		String text=address.toString();
		if (!text.contains("://")) {
			String host=host(text);
			if (host==null) throw new IllegalArgumentException("Not a host: "+text);
			return Strings.create(host);
		}
		URI endpoint;
		try {
			endpoint=new URI(text);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Not a transport address: "+text,e);
		}
		String host=host(endpoint.getHost());
		if (host==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null
				|| endpoint.getRawFragment()!=null || (endpoint.getRawPath()!=null && !endpoint.getRawPath().isEmpty())) {
			throw new IllegalArgumentException("Not a transport address: "+text);
		}
		return Strings.create(address(endpoint,host));
	}

	private static String address(URI endpoint,String host) {
		int port=endpoint.getPort();
		return endpoint.getScheme().toLowerCase(Locale.ROOT)+"://"+(host.indexOf(':')>=0?"["+host+"]":host)
			+":"+(port<0?Constants.DEFAULT_PEER_PORT:port);
	}

	/** A host in stored form, or null if it is neither an IP address nor a host name. Never looks up DNS. */
	private static String host(String raw) {
		if (raw==null) return null;
		String host=(raw.startsWith("[") && raw.endsWith("]"))?raw.substring(1,raw.length()-1):raw;
		try {
			if (IPV4.matcher(host).matches()) return ip(InetAddress.getByName(host));
			// Brackets make the JDK parse an IPv6 literal or fail, never look it up
			if (IPV6.matcher(host).matches()) return ip(InetAddress.getByName("["+host+"]"));
		} catch (UnknownHostException e) {
			return null;
		}
		host=host.toLowerCase(Locale.ROOT);
		return HOST_NAME.matcher(host).matches()?host:null;
	}

	private static String ip(InetAddress address) {
		String text=address.getHostAddress();
		int scope=text.indexOf('%');
		return (scope<0)?text:text.substring(0,scope);
	}
}
