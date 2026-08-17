package convex.dlfs;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.etch.EtchConfig;
import convex.node.NodeConfig;
import convex.peer.Config;

/**
 * Typed configuration for a standalone DLFS lattice application.
 *
 * <p>The source of truth is an immutable JSON5-compatible
 * {@code AMap<AString,ACell>}. Sections separate generic lattice-node hosting
 * from DLFS application policy and HTTP exposure:
 *
 * <pre>{@code
 * {
 *   node: {
 *     port: 19888,
 *     store: "dlfs.etch",
 *     keypair: "<32-byte seed hex>",
 *     bootstrap: [{key: "<AccountKey>", url: "tcp://127.0.0.1:19889"}],
 *   },
 *   dlfs: {path: ["fs"], drive: "home"},
 *   http: {bind: "127.0.0.1", port: 8080},
 *   security: {latticeInbound: "deny", writeAuthentication: "none"},
 * }
 * }</pre>
 *
 * <p>Inbound lattice access is denied and HTTP binds to loopback by default.
 * Serving the complete lattice view or accepting unauthenticated writes on a
 * non-loopback HTTP interface requires an explicit operator choice.
 */
public final class DLFSConfig {

	// Top-level sections
	public static final AString NODE=Strings.intern("node");
	public static final AString DLFS=Strings.intern("dlfs");
	public static final AString HTTP=Strings.intern("http");
	public static final AString SECURITY=Strings.intern("security");

	// Node/application host keys
	public static final AString STORE=Strings.intern("store");
	public static final AString ETCH=Strings.intern("etch");
	public static final AString KEYPAIR=Strings.intern("keypair");
	public static final AString BOOTSTRAP=Strings.intern("bootstrap");
	public static final AString KEY=Strings.intern("key");

	// DLFS keys
	public static final AString PATH=Strings.intern("path");
	public static final AString DRIVE=Strings.intern("drive");
	public static final AString MOUNTS=Strings.intern("mounts");
	public static final AString IDENTITY=Strings.intern("identity");
	public static final AString OWNER=Strings.intern("owner");
	public static final String LOCAL_IDENTITY="node";
	public static final String LOCAL_OWNER="node";

	// HTTP keys
	public static final AString BIND=Strings.intern("bind");
	public static final AString MAX_REQUEST_BYTES=Strings.intern("maxRequestBytes");

	// Security keys and values
	public static final AString LATTICE_INBOUND=Strings.intern("latticeInbound");
	public static final AString WRITE_AUTHENTICATION=Strings.intern("writeAuthentication");
	public static final AString ALLOW_UNAUTHENTICATED_HTTP=
		Strings.intern("allowUnauthenticatedHTTP");
	public static final String INBOUND_DENY="deny";
	public static final String INBOUND_PUBLIC="public";
	public static final String WRITE_AUTH_NONE="none";
	public static final String WRITE_AUTH_NODE_KEY="node-key";
	public static final String WRITE_AUTH_DENY="deny";

	public static final int DEFAULT_NODE_PORT=19888;
	public static final int DEFAULT_HTTP_PORT=8080;
	public static final String DEFAULT_DRIVE="home";
	public static final String DEFAULT_STORE="temp";

	private final AMap<AString,ACell> config;

	private DLFSConfig(AMap<AString,ACell> config) {
		this.config=(config==null)?Maps.empty():config;
	}

	/** Creates a configuration over an immutable Convex map. */
	public static DLFSConfig create(AMap<AString,ACell> config) {
		return new DLFSConfig(config);
	}

	/** Creates a configuration containing only secure defaults. */
	public static DLFSConfig create() {
		return new DLFSConfig(null);
	}

	/** Parses a JSON5 configuration document. */
	public static DLFSConfig parse(String json5) {
		ACell parsed=JSON.parseJSON5(json5);
		AMap<AString,ACell> map=RT.castMap(parsed);
		if (map==null) throw new IllegalArgumentException("DLFS configuration must be an object");
		return new DLFSConfig(map);
	}

	/** Loads a UTF-8 JSON5 configuration file. */
	public static DLFSConfig load(Path path) throws IOException {
		return parse(Files.readString(path));
	}

	/** Loads a JSON5 configuration file. */
	public static DLFSConfig load(String path) throws IOException {
		return load(Path.of(path));
	}

	/** Returns the complete immutable source map. */
	public AMap<AString,ACell> getMap() {
		return config;
	}

	/** Returns a section, rejecting a present non-object value. */
	public AMap<AString,ACell> getSection(AString key) {
		ACell value=config.get(key);
		if (value==null) return Maps.empty();
		AMap<AString,ACell> section=RT.castMap(value);
		if (section==null) throw new IllegalArgumentException(key+" must be an object");
		return section;
	}

	/** Compiles the generic NodeServer settings from the {@code node} section. */
	public NodeConfig getNodeConfig() {
		AMap<AString,ACell> node=getSection(NODE);
		if (!node.containsKey(NodeConfig.PORT)) {
			node=node.assoc(NodeConfig.PORT,CVMLong.create(DEFAULT_NODE_PORT));
		}
		return NodeConfig.create(node);
	}

	/** Gets the backing store path, or {@code temp}/{@code memory}. */
	public String getStorePath() {
		return getString(getSection(NODE),STORE,DEFAULT_STORE,"node.store");
	}

	/** Compiles optional Etch creation policy. */
	public EtchConfig getEtchConfig() {
		return getEtchConfig((Function<AccountKey,byte[]>)null);
	}

	/** Compiles optional Etch creation policy with runtime key resolution. */
	public EtchConfig getEtchConfig(Function<AccountKey,byte[]> keyFunction) {
		ACell value=getSection(NODE).get(ETCH);
		if (value==null) return null;
		AMap<AString,ACell> etch=RT.castMap(value);
		if (etch==null) throw new IllegalArgumentException("node.etch must be an object");
		return EtchConfig.fromMap(etch,keyFunction);
	}

	/** Compiles optional Etch policy using the standard node-identity resolver. */
	public EtchConfig getEtchConfigFor(AKeyPair keyPair) {
		return getEtchConfig((keyPair==null)?null:Config.etchKeyResolver(keyPair));
	}

	/** Gets the configured node key pair, or null to generate an ephemeral identity. */
	public AKeyPair getKeyPair() {
		ACell value=getSection(NODE).get(KEYPAIR);
		if (value==null) return null;
		AString seedString=RT.ensureString(value);
		if (seedString==null) throw new IllegalArgumentException("node.keypair must be a hex string");
		Blob seed=Blob.fromHex(seedString.toString());
		if ((seed==null)||(seed.count()!=AKeyPair.SEED_LENGTH)) {
			throw new IllegalArgumentException("node.keypair must encode a 32-byte seed");
		}
		return AKeyPair.create(seed);
	}

	/** Gets configured TCP bootstrap peers and their expected identities. */
	public List<BootstrapPeer> getBootstrapPeers() {
		ACell value=getSection(NODE).get(BOOTSTRAP);
		if (value==null) return List.of();
		AVector<ACell> entries=RT.ensureVector(value);
		if (entries==null) throw new IllegalArgumentException("node.bootstrap must be an array");
		ArrayList<BootstrapPeer> result=new ArrayList<>((int)entries.count());
		for (long i=0;i<entries.count();i++) {
			AMap<AString,ACell> entry=RT.castMap(entries.get(i));
			if (entry==null) throw new IllegalArgumentException("node.bootstrap["+i+"] must be an object");
			result.add(parseBootstrapPeer(entry,i));
		}
		return List.copyOf(result);
	}

	private static BootstrapPeer parseBootstrapPeer(AMap<AString,ACell> entry,long index) {
		AString keyValue=RT.ensureString(entry.get(KEY));
		AString urlValue=RT.ensureString(entry.get(NodeConfig.URL));
		if (keyValue==null) throw new IllegalArgumentException("node.bootstrap["+index+"].key is required");
		if (urlValue==null) throw new IllegalArgumentException("node.bootstrap["+index+"].url is required");
		AccountKey key=AccountKey.parse(keyValue.toString());
		if (key==null) throw new IllegalArgumentException("Invalid AccountKey at node.bootstrap["+index+"].key");
		try {
			URI uri=new URI(urlValue.toString());
			if (!"tcp".equalsIgnoreCase(uri.getScheme())) {
				throw new IllegalArgumentException("node.bootstrap["+index+"].url must use tcp://");
			}
			if ((uri.getHost()==null)||(uri.getPort()<1)||(uri.getPort()>65535)
					||(uri.getUserInfo()!=null)||(uri.getQuery()!=null)||(uri.getFragment()!=null)
					||((uri.getPath()!=null)&&!uri.getPath().isEmpty())) {
				throw new IllegalArgumentException("Invalid TCP URL at node.bootstrap["+index+"].url");
			}
			return new BootstrapPeer(key,
				InetSocketAddress.createUnresolved(uri.getHost(),uri.getPort()));
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid URL at node.bootstrap["+index+"].url",e);
		}
	}

	/** Gets the configured lattice path containing the DLFS region. */
	public Keyword[] getRegionPath() {
		ACell value=getSection(DLFS).get(PATH);
		if (value==null) return new Keyword[] {Keywords.FS};
		AString single=RT.ensureString(value);
		if (single!=null) return new Keyword[] {parsePathElement(single,"dlfs.path")};
		AVector<ACell> values=RT.ensureVector(value);
		if ((values==null)||values.isEmpty()) {
			throw new IllegalArgumentException("dlfs.path must be a string or non-empty string array");
		}
		Keyword[] path=new Keyword[(int)values.count()];
		for (int i=0;i<path.length;i++) {
			AString element=RT.ensureString(values.get(i));
			if (element==null) throw new IllegalArgumentException("dlfs.path["+i+"] must be a string");
			path[i]=parsePathElement(element,"dlfs.path["+i+"]");
		}
		return path;
	}

	private static Keyword parsePathElement(AString value,String location) {
		String name=value.toString();
		if (name.isBlank()) throw new IllegalArgumentException(location+" must not be blank");
		if (name.startsWith(":")) name=name.substring(1);
		Keyword keyword=Keyword.create(name);
		if (keyword==null) throw new IllegalArgumentException("Invalid lattice keyword at "+location);
		return keyword;
	}

	/** Gets the initial local drive name. */
	public String getDriveName() {
		String result=getString(getSection(DLFS),DRIVE,DEFAULT_DRIVE,"dlfs.drive");
		if (!DLFSDriveManager.isValidDriveName(result)) {
			throw new IllegalArgumentException("Invalid dlfs.drive: "+result);
		}
		return result;
	}

	/**
	 * Gets local HTTP identity-to-lattice-owner routing policy.
	 *
	 * <p>A null identity is the anonymous route. The symbolic string
	 * {@code node} selects this process's DID, and a null owner selects this
	 * process's lattice owner key. If omitted, anonymous requests route to the
	 * local owner for zero-setup loopback use.
	 */
	public List<DriveMount> getDriveMounts() {
		ACell value=getSection(DLFS).get(MOUNTS);
		if (value==null) return List.of(new DriveMount(null,null));
		AVector<ACell> entries=RT.ensureVector(value);
		if (entries==null) throw new IllegalArgumentException("dlfs.mounts must be an array");
		ArrayList<DriveMount> result=new ArrayList<>((int)entries.count());
		for (long i=0;i<entries.count();i++) {
			AMap<AString,ACell> entry=RT.castMap(entries.get(i));
			if (entry==null) throw new IllegalArgumentException("dlfs.mounts["+i+"] must be an object");
			result.add(parseDriveMount(entry,i));
		}
		return List.copyOf(result);
	}

	private static DriveMount parseDriveMount(AMap<AString,ACell> entry,long index) {
		ACell identityValue=entry.get(IDENTITY);
		String identity=null;
		if (identityValue!=null) {
			AString string=RT.ensureString(identityValue);
			if ((string==null)||string.toString().isBlank()) {
				throw new IllegalArgumentException("dlfs.mounts["+index+"].identity must be null or a string");
			}
			identity=string.toString();
		}

		ACell ownerValue=entry.get(OWNER);
		AccountKey owner=null;
		if (ownerValue!=null) {
			AString string=RT.ensureString(ownerValue);
			if (string==null) {
				throw new IllegalArgumentException("dlfs.mounts["+index+"].owner must be 'node' or an AccountKey");
			}
			if (!LOCAL_OWNER.equals(string.toString())) {
				owner=AccountKey.parse(string.toString());
				if (owner==null) {
					throw new IllegalArgumentException("Invalid AccountKey at dlfs.mounts["+index+"].owner");
				}
			}
		}
		return new DriveMount(identity,owner);
	}

	/** Gets the HTTP bind address. */
	public String getHTTPBindHost() {
		return getString(getSection(HTTP),BIND,DLFSServer.DEFAULT_BIND_HOST,"http.bind");
	}

	/** Gets the HTTP port. */
	public int getHTTPPort() {
		return getPort(getSection(HTTP),NodeConfig.PORT,DEFAULT_HTTP_PORT,"http.port");
	}

	/** Gets the maximum HTTP request body size. */
	public long getMaxRequestBytes() {
		ACell value=getSection(HTTP).get(MAX_REQUEST_BYTES);
		if (value==null) return DLFSServer.DEFAULT_MAX_REQUEST_SIZE;
		CVMLong size=RT.ensureLong(value);
		if ((size==null)||(size.longValue()<=0)) {
			throw new IllegalArgumentException("http.maxRequestBytes must be a positive integer");
		}
		return size.longValue();
	}

	/** Whether every inbound connection receives the complete primary lattice view. */
	public boolean isPublicLatticeInbound() {
		String policy=getString(getSection(SECURITY),LATTICE_INBOUND,INBOUND_DENY,
			"security.latticeInbound");
		return switch (policy) {
			case INBOUND_DENY -> false;
			case INBOUND_PUBLIC -> true;
			default -> throw new IllegalArgumentException(
				"security.latticeInbound must be 'deny' or 'public'");
		};
	}

	/** Gets HTTP mutation authentication policy. */
	public String getWriteAuthentication() {
		String policy=getString(getSection(SECURITY),WRITE_AUTHENTICATION,WRITE_AUTH_NONE,
			"security.writeAuthentication");
		return switch (policy) {
			case WRITE_AUTH_NONE,WRITE_AUTH_NODE_KEY,WRITE_AUTH_DENY -> policy;
			default -> throw new IllegalArgumentException(
				"security.writeAuthentication must be 'none', 'node-key' or 'deny'");
		};
	}

	/** Whether a public HTTP listener may accept unauthenticated mutations. */
	public boolean isUnauthenticatedPublicHTTPAllowed() {
		ACell value=getSection(SECURITY).get(ALLOW_UNAUTHENTICATED_HTTP);
		if (value==null) return false;
		if (value instanceof CVMBool bool) return bool.booleanValue();
		throw new IllegalArgumentException("security.allowUnauthenticatedHTTP must be a boolean");
	}

	/**
	 * Validates all typed fields and cross-section security invariants.
	 *
	 * <p>A non-loopback HTTP listener with authentication disabled is rejected
	 * unless the operator explicitly acknowledges it with
	 * {@code security.allowUnauthenticatedHTTP: true}.
	 */
	public void validate() {
		validateNodePort();
		String storePath=getStorePath();
		AKeyPair keyPair=getKeyPair();
		EtchConfig etch=getEtchConfigFor(keyPair);
		if ("memory".equalsIgnoreCase(storePath)&&(etch!=null)) {
			throw new IllegalArgumentException("node.etch cannot be used with a memory store");
		}
		if ((etch!=null)&&(etch.getCipherMode()!=EtchConfig.CipherMode.NONE)
				&&(keyPair==null)) {
			throw new IllegalArgumentException(
				"Encrypted node.etch storage requires a persistent node.keypair");
		}
		getBootstrapPeers();
		getRegionPath();
		getDriveName();
		getDriveMounts();
		String bind=getHTTPBindHost();
		getHTTPPort();
		getMaxRequestBytes();
		isPublicLatticeInbound();
		String writeAuth=getWriteAuthentication();
		boolean allowUnauthenticated=isUnauthenticatedPublicHTTPAllowed();
		if (!isLoopbackName(bind)&&WRITE_AUTH_NONE.equals(writeAuth)
				&&!allowUnauthenticated) {
			throw new IllegalArgumentException(
				"Non-loopback HTTP with unauthenticated writes requires "
				+"security.allowUnauthenticatedHTTP: true");
		}
	}

	private static boolean isLoopbackName(String host) {
		String value=host.trim().toLowerCase();
		return value.equals("localhost")||value.equals("::1")||value.equals("[::1]")
			||value.startsWith("127.");
	}

	/** Returns a copy with an explicit node-port override. */
	public DLFSConfig withNodePort(int port) {
		if ((port<-1)||(port>65535)) {
			throw new IllegalArgumentException("node.port must be -1 or between 0 and 65535");
		}
		return withSectionValue(NODE,NodeConfig.PORT,CVMLong.create(port));
	}

	/** Returns a copy with an explicit HTTP-port override. */
	public DLFSConfig withHTTPPort(int port) {
		validatePort(port,"http.port");
		return withSectionValue(HTTP,NodeConfig.PORT,CVMLong.create(port));
	}

	/** Returns a copy with an explicit initial-drive override. */
	public DLFSConfig withDriveName(String drive) {
		return withSectionValue(DLFS,DRIVE,Strings.create(drive));
	}

	/** Returns a copy explicitly granting public inbound lattice access. */
	public DLFSConfig withPublicLatticeInbound() {
		return withSectionValue(SECURITY,LATTICE_INBOUND,Strings.create(INBOUND_PUBLIC));
	}

	private DLFSConfig withSectionValue(AString sectionKey,AString key,ACell value) {
		AMap<AString,ACell> section=getSection(sectionKey).assoc(key,value);
		return new DLFSConfig(config.assoc(sectionKey,section));
	}

	private static String getString(AMap<AString,ACell> section,AString key,
			String defaultValue,String location) {
		ACell value=section.get(key);
		if (value==null) return defaultValue;
		AString string=RT.ensureString(value);
		if (string==null) throw new IllegalArgumentException(location+" must be a string");
		String result=string.toString();
		if (result.isBlank()) throw new IllegalArgumentException(location+" must not be blank");
		return result;
	}

	private static int getPort(AMap<AString,ACell> section,AString key,
			int defaultValue,String location) {
		ACell value=section.get(key);
		if (value==null) return defaultValue;
		CVMLong port=RT.ensureLong(value);
		if (port==null) throw new IllegalArgumentException(location+" must be an integer");
		long raw=port.longValue();
		if ((raw<0)||(raw>65535)) {
			throw new IllegalArgumentException(location+" must be between 0 and 65535");
		}
		int result=(int)raw;
		validatePort(result,location);
		return result;
	}

	private void validateNodePort() {
		ACell value=getSection(NODE).get(NodeConfig.PORT);
		if (value==null) return;
		CVMLong port=RT.ensureLong(value);
		if (port==null) throw new IllegalArgumentException("node.port must be an integer");
		long raw=port.longValue();
		if ((raw<-1)||(raw>65535)) {
			throw new IllegalArgumentException("node.port must be -1 or between 0 and 65535");
		}
	}

	private static void validatePort(int port,String location) {
		if ((port<0)||(port>65535)) {
			throw new IllegalArgumentException(location+" must be between 0 and 65535");
		}
	}

	/** Bootstrap transport paired with its expected remote identity. */
	public record BootstrapPeer(AccountKey key,InetSocketAddress address) {
		public BootstrapPeer {
			if (key==null) throw new IllegalArgumentException("Bootstrap key is required");
			if (address==null) throw new IllegalArgumentException("Bootstrap address is required");
		}
	}

	/** Local HTTP identity route to one replicated lattice owner. */
	public record DriveMount(String identity,AccountKey owner) {
	}
}
