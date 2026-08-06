package convex.etch;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
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

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains) {
		validateVersion(version);
		EtchFileMapperFactory.validate(version,mappingMode);
		this.version=version;
		this.mappingMode=mappingMode;
		this.buildChains=buildChains;
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

		return create(version,mappingMode,buildChains);
	}

	private static void validateKeys(AMap<AString,ACell> source) {
		long count=source.count();
		for (long i=0; i<count; i++) {
			MapEntry<AString,ACell> entry=source.entryAt(i);
			AString key=entry.getKey();
			if (!(VERSION.equals(key)||MAPPING.equals(key)||BUILD_CHAINS.equals(key))) {
				throw new IllegalArgumentException("Unknown Etch configuration key: "+key);
			}
		}
	}

	private static void validateVersion(short version) {
		if ((version!=EtchConstants.VERSION_1)&&(version!=EtchConstants.VERSION_2)) {
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

	@Override
	public boolean equals(Object obj) {
		if (this==obj) return true;
		if (!(obj instanceof EtchConfig other)) return false;
		return (version==other.version)&&(mappingMode==other.mappingMode)&&(buildChains==other.buildChains);
	}

	@Override
	public int hashCode() {
		return Objects.hash(version,mappingMode,buildChains);
	}

	@Override
	public String toString() {
		return "EtchConfig[version="+version+", mapping="+mappingMode.configName()
				+", buildChains="+buildChains+"]";
	}
}
