package convex.etch;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * Compiled, immutable configuration for an Etch instance.
 *
 * <p>External maps are converted, defaulted and validated when this object is
 * constructed. Etch therefore never performs configuration parsing or lookup
 * on its read or write paths.</p>
 */
public final class EtchConfig {
	/** JSON-style configuration key for the Etch file version. */
	public static final AString VERSION=Strings.intern("version");
	/** JSON-style configuration key for the mapping backend. */
	public static final AString MAPPING=Strings.intern("mapping");
	/** JSON-style configuration key controlling short collision chains. */
	public static final AString BUILD_CHAINS=Strings.intern("buildChains");
	/** JSON-style configuration key for the optional Etch v3 public-key hint. */
	public static final AString PUBLIC_KEY_HINT=Strings.intern("publicKeyHint");

	/** Concrete mapping implementations selectable at Etch construction. */
	public enum MappingMode {
		MAPPED_BYTE_BUFFER("mapped-byte-buffer"),
		MEMORY_SEGMENT("memory-segment");

		private final String configName;

		MappingMode(String configName) {
			this.configName=configName;
		}

		public String configName() {
			return configName;
		}
	}

	private final short version;
	private final MappingMode mappingMode;
	private final boolean buildChains;
	private final AccountKey publicKeyHint;

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains) {
		this(version,mappingMode,buildChains,null);
	}

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains,
			AccountKey publicKeyHint) {
		validateVersion(version);
		EtchFileMapperFactory.validate(version,mappingMode);
		publicKeyHint=normalisePublicKeyHint(publicKeyHint);
		if ((publicKeyHint!=null)&&(version!=EtchConstants.VERSION_3)) {
			throw new IllegalArgumentException("Etch public-key hint requires Etch v3");
		}
		this.version=version;
		this.mappingMode=mappingMode;
		this.buildChains=buildChains;
		this.publicKeyHint=publicKeyHint;
	}

	/**
	 * Creates the default compiled configuration for a new Etch file.
	 */
	public static EtchConfig create() {
		return create(EtchConstants.CURRENT_VERSION);
	}

	/**
	 * Creates a compiled configuration with defaults appropriate to a version.
	 * This is also used to infer the effective configuration of an existing file
	 * when the caller supplies no explicit configuration.
	 */
	public static EtchConfig create(short version) {
		validateVersion(version);
		return new EtchConfig(version,EtchFileMapperFactory.defaultMapping(version),EtchConstants.DEFAULT_BUILD_CHAINS);
	}

	/**
	 * Creates a fully specified compiled configuration.
	 */
	public static EtchConfig create(short version, MappingMode mappingMode, boolean buildChains) {
		return new EtchConfig(version,Objects.requireNonNull(mappingMode,"mappingMode"),buildChains);
	}

	/**
	 * Creates a fully specified compiled configuration with an optional v3
	 * public-key hint. A non-null hint is rejected for Etch v1 and v2.
	 */
	public static EtchConfig create(short version, MappingMode mappingMode,
			boolean buildChains, AccountKey publicKeyHint) {
		return new EtchConfig(version,Objects.requireNonNull(mappingMode,"mappingMode"),
				buildChains,publicKeyHint);
	}

	/**
	 * Compiles a JSON-style configuration map for a new file. Missing values use
	 * the current Etch defaults.
	 *
	 * @param source configuration map, or {@code null} for defaults
	 * @return compiled Etch configuration
	 * @throws IllegalArgumentException if a key or value is invalid
	 */
	public static EtchConfig fromMap(AMap<AString,ACell> source) {
		return fromMap(source,EtchConstants.CURRENT_VERSION);
	}

	/**
	 * Compiles a JSON-style configuration map using the supplied version when
	 * the map omits {@code version}. Used while opening an existing file after
	 * its version has been read under the file lock.
	 */
	static EtchConfig fromMap(AMap<AString,ACell> source, short defaultVersion) {
		if (source==null) return create(defaultVersion);
		validateKeys(source);

		short version=defaultVersion;
		MapEntry<AString,ACell> versionEntry=source.getEntry(VERSION);
		if (versionEntry!=null) {
			ACell versionValue=versionEntry.getValue();
			if (!(versionValue instanceof CVMLong value)) {
				throw invalid(VERSION,"expected an integer, got "+Utils.getClassName(versionValue));
			}
			long requested=value.longValue();
			if ((requested<Short.MIN_VALUE)||(requested>Short.MAX_VALUE)) {
				throw invalid(VERSION,"out of range: "+requested);
			}
			version=(short)requested;
		}
		validateVersion(version);

		boolean buildChains=EtchConstants.DEFAULT_BUILD_CHAINS;
		MapEntry<AString,ACell> chainsEntry=source.getEntry(BUILD_CHAINS);
		if (chainsEntry!=null) {
			ACell chainsValue=chainsEntry.getValue();
			if (!(chainsValue instanceof CVMBool value)) {
				throw invalid(BUILD_CHAINS,"expected a boolean, got "+Utils.getClassName(chainsValue));
			}
			buildChains=value.booleanValue();
		}

		AccountKey publicKeyHint=null;
		MapEntry<AString,ACell> hintEntry=source.getEntry(PUBLIC_KEY_HINT);
		if ((hintEntry!=null)&&(hintEntry.getValue()!=null)) {
			ACell hintValue=hintEntry.getValue();
			AccountKey parsed=AccountKey.parse(hintValue);
			if (parsed==null) {
				throw invalid(PUBLIC_KEY_HINT,"expected a 32-byte public key, got "
						+Utils.getClassName(hintValue));
			}
			publicKeyHint=normalisePublicKeyHint(parsed);
		}

		MappingMode mappingMode;
		MapEntry<AString,ACell> mappingEntry=source.getEntry(MAPPING);
		if (mappingEntry==null) {
			mappingMode=EtchFileMapperFactory.defaultMapping(version);
		} else {
			ACell mappingValue=mappingEntry.getValue();
			if (!(mappingValue instanceof AString value)) {
				throw invalid(MAPPING,"expected a string, got "+Utils.getClassName(mappingValue));
			}
			String name=value.toString();
			mappingMode=switch (name) {
				case "auto" -> EtchFileMapperFactory.defaultMapping(version);
				case "mapped-byte-buffer" -> MappingMode.MAPPED_BYTE_BUFFER;
				case "memory-segment" -> MappingMode.MEMORY_SEGMENT;
				default -> throw invalid(MAPPING,"unsupported value: "+name);
			};
		}

		return create(version,mappingMode,buildChains,publicKeyHint);
	}

	private static void validateKeys(AMap<AString,ACell> source) {
		long count=source.count();
		for (long i=0; i<count; i++) {
			MapEntry<AString,ACell> entry=source.entryAt(i);
			AString key=entry.getKey();
			if (!(VERSION.equals(key)||MAPPING.equals(key)||BUILD_CHAINS.equals(key)
					||PUBLIC_KEY_HINT.equals(key))) {
				throw new IllegalArgumentException("Unknown Etch configuration key: "+key);
			}
		}
	}

	private static void validateVersion(short version) {
		if ((version!=EtchConstants.VERSION_1)&&(version!=EtchConstants.VERSION_2)
				&&(version!=EtchConstants.VERSION_3)) {
			throw new IllegalArgumentException("Unsupported Etch version: "+version);
		}
	}

	private static IllegalArgumentException invalid(AString key, String message) {
		return new IllegalArgumentException("Invalid Etch configuration '"+key+"': "+message);
	}

	public short getVersion() {
		return version;
	}

	public MappingMode getMappingMode() {
		return mappingMode;
	}

	public boolean isBuildChains() {
		return buildChains;
	}

	/**
	 * Gets the optional Etch v3 public-key hint. This identifies key material to
	 * the application but does not define how an encryption secret is derived.
	 */
	public AccountKey getPublicKeyHint() {
		return publicKeyHint;
	}

	/** Returns a copy of this compiled configuration with the supplied hint. */
	public EtchConfig withPublicKeyHint(AccountKey hint) {
		return new EtchConfig(version,mappingMode,buildChains,hint);
	}

	@Override
	public boolean equals(Object obj) {
		if (this==obj) return true;
		if (!(obj instanceof EtchConfig other)) return false;
		return (version==other.version)&&(mappingMode==other.mappingMode)
				&&(buildChains==other.buildChains)
				&&Objects.equals(publicKeyHint,other.publicKeyHint);
	}

	@Override
	public int hashCode() {
		return Objects.hash(version,mappingMode,buildChains,publicKeyHint);
	}

	@Override
	public String toString() {
		return "EtchConfig[version="+version+", mapping="+mappingMode.configName()
				+", buildChains="+buildChains+", publicKeyHint="+publicKeyHint+"]";
	}

	private static AccountKey normalisePublicKeyHint(AccountKey hint) {
		return ((hint==null)||AccountKey.ZERO.equals(hint))?null:hint;
	}
}
