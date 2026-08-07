package convex.etch;

import java.util.Arrays;
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
	/** JSON-style configuration key for the Etch v3 file cipher. */
	public static final AString CIPHER=Strings.intern("cipher");
	/** JSON-style configuration key controlling Etch v3 index encryption. */
	public static final AString ENCRYPT_INDEX=Strings.intern("encryptIndex");

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

	/** Complete v3 file-overlay cipher choices supported by normal Etch access. */
	public enum CipherMode {
		NONE("none",EtchConstants.V3_CIPHER_NONE),
		AES_256_CTR("aes-256-ctr",EtchConstants.V3_CIPHER_AES_256_CTR);

		private final String configName;
		private final int fileId;

		CipherMode(String configName, int fileId) {
			this.configName=configName;
			this.fileId=fileId;
		}

		public String configName() {
			return configName;
		}

		int fileId() {
			return fileId;
		}

		static CipherMode fromFileId(int fileId) {
			for (CipherMode mode:values()) {
				if (mode.fileId==fileId) return mode;
			}
			throw new IllegalArgumentException("Unsupported Etch v3 cipher ID: "+fileId);
		}
	}

	private final short version;
	private final MappingMode mappingMode;
	private final boolean buildChains;
	private final AccountKey publicKeyHint;
	private final CipherMode cipherMode;
	private final boolean encryptedIndex;
	private final byte[] encryptionSecret;

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains) {
		this(version,mappingMode,buildChains,null,CipherMode.NONE,false,null);
	}

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains,
			AccountKey publicKeyHint) {
		this(version,mappingMode,buildChains,publicKeyHint,CipherMode.NONE,false,null);
	}

	private EtchConfig(short version, MappingMode mappingMode, boolean buildChains,
			AccountKey publicKeyHint, CipherMode cipherMode, boolean encryptedIndex,
			byte[] encryptionSecret) {
		validateVersion(version);
		EtchFileMapperFactory.validate(version,mappingMode);
		cipherMode=Objects.requireNonNull(cipherMode,"cipherMode");
		publicKeyHint=normalisePublicKeyHint(publicKeyHint);
		if ((publicKeyHint!=null)&&(version!=EtchConstants.VERSION_3)) {
			throw new IllegalArgumentException("Etch public-key hint requires Etch v3");
		}
		if (version!=EtchConstants.VERSION_3) {
			if ((cipherMode!=CipherMode.NONE)||encryptedIndex||(encryptionSecret!=null)) {
				throw new IllegalArgumentException("Etch encryption requires Etch v3");
			}
		} else if (cipherMode==CipherMode.NONE) {
			if (encryptedIndex) {
				throw new IllegalArgumentException("Etch index encryption requires a file cipher");
			}
			if (encryptionSecret!=null) {
				throw new IllegalArgumentException("Plaintext Etch must not receive encryption secret material");
			}
		} else if ((encryptionSecret==null)||(encryptionSecret.length==0)) {
			throw new IllegalArgumentException("Encrypted Etch requires caller secret material");
		}
		this.version=version;
		this.mappingMode=mappingMode;
		this.buildChains=buildChains;
		this.publicKeyHint=publicKeyHint;
		this.cipherMode=cipherMode;
		this.encryptedIndex=encryptedIndex;
		this.encryptionSecret=(encryptionSecret==null)?null:encryptionSecret.clone();
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
	 * Creates a fully specified Etch v3 configuration. Secret material is opaque
	 * to Etch and is copied defensively before per-file keys are derived from it.
	 */
	public static EtchConfig createV3(MappingMode mappingMode, boolean buildChains,
			CipherMode cipherMode, boolean encryptedIndex, AccountKey publicKeyHint,
			byte[] encryptionSecret) {
		return new EtchConfig(EtchConstants.VERSION_3,
				Objects.requireNonNull(mappingMode,"mappingMode"),buildChains,
				publicKeyHint,cipherMode,encryptedIndex,encryptionSecret);
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
		return fromMap(source,EtchConstants.CURRENT_VERSION,null);
	}

	/**
	 * Compiles a JSON-style configuration map with separately resolved opaque
	 * secret material. This keeps key lookup and string conversion outside Etch.
	 */
	public static EtchConfig fromMap(AMap<AString,ACell> source, byte[] encryptionSecret) {
		return fromMap(source,EtchConstants.CURRENT_VERSION,encryptionSecret);
	}

	/**
	 * Compiles a JSON-style configuration map using the supplied version when
	 * the map omits {@code version}. Used while opening an existing file after
	 * its version has been read under the file lock.
	 */
	static EtchConfig fromMap(AMap<AString,ACell> source, short defaultVersion) {
		return fromMap(source,defaultVersion,null);
	}

	private static EtchConfig fromMap(AMap<AString,ACell> source, short defaultVersion,
			byte[] encryptionSecret) {
		if (source==null) {
			if (encryptionSecret!=null) {
				throw new IllegalArgumentException("Encryption secret supplied without an Etch cipher");
			}
			return create(defaultVersion);
		}
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

		CipherMode cipherMode=CipherMode.NONE;
		MapEntry<AString,ACell> cipherEntry=source.getEntry(CIPHER);
		if (cipherEntry!=null) {
			ACell cipherValue=cipherEntry.getValue();
			if (!(cipherValue instanceof AString value)) {
				throw invalid(CIPHER,"expected a string, got "+Utils.getClassName(cipherValue));
			}
			String name=value.toString();
			cipherMode=switch (name) {
				case "none" -> CipherMode.NONE;
				case "aes-256-ctr" -> CipherMode.AES_256_CTR;
				default -> throw invalid(CIPHER,"unsupported value: "+name);
			};
		}

		boolean encryptedIndex=false;
		MapEntry<AString,ACell> indexEntry=source.getEntry(ENCRYPT_INDEX);
		if (indexEntry!=null) {
			ACell indexValue=indexEntry.getValue();
			if (!(indexValue instanceof CVMBool value)) {
				throw invalid(ENCRYPT_INDEX,"expected a boolean, got "+Utils.getClassName(indexValue));
			}
			encryptedIndex=value.booleanValue();
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

		return new EtchConfig(version,mappingMode,buildChains,publicKeyHint,
				cipherMode,encryptedIndex,encryptionSecret);
	}

	private static void validateKeys(AMap<AString,ACell> source) {
		long count=source.count();
		for (long i=0; i<count; i++) {
			MapEntry<AString,ACell> entry=source.entryAt(i);
			AString key=entry.getKey();
			if (!(VERSION.equals(key)||MAPPING.equals(key)||BUILD_CHAINS.equals(key)
					||PUBLIC_KEY_HINT.equals(key)||CIPHER.equals(key)||ENCRYPT_INDEX.equals(key))) {
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

	public CipherMode getCipherMode() {
		return cipherMode;
	}

	public boolean isIndexEncrypted() {
		return encryptedIndex;
	}

	public boolean hasEncryptionSecret() {
		return encryptionSecret!=null;
	}

	byte[] encryptionSecret() {
		return (encryptionSecret==null)?null:encryptionSecret.clone();
	}

	EtchConfig withV3FileOptions(CipherMode fileCipher, boolean fileIndexEncrypted,
			AccountKey filePublicKeyHint) {
		return new EtchConfig(version,mappingMode,buildChains,filePublicKeyHint,
				fileCipher,fileIndexEncrypted,encryptionSecret);
	}

	/** Returns a copy of this compiled configuration with the supplied hint. */
	public EtchConfig withPublicKeyHint(AccountKey hint) {
		return new EtchConfig(version,mappingMode,buildChains,hint,cipherMode,
				encryptedIndex,encryptionSecret);
	}

	@Override
	public boolean equals(Object obj) {
		if (this==obj) return true;
		if (!(obj instanceof EtchConfig other)) return false;
		return (version==other.version)&&(mappingMode==other.mappingMode)
				&&(buildChains==other.buildChains)
				&&Objects.equals(publicKeyHint,other.publicKeyHint)
				&&(cipherMode==other.cipherMode)&&(encryptedIndex==other.encryptedIndex)
				&&Arrays.equals(encryptionSecret,other.encryptionSecret);
	}

	@Override
	public int hashCode() {
		int result=Objects.hash(version,mappingMode,buildChains,publicKeyHint,
				cipherMode,encryptedIndex);
		return 31*result+Arrays.hashCode(encryptionSecret);
	}

	@Override
	public String toString() {
		return "EtchConfig[version="+version+", mapping="+mappingMode.configName()
				+", buildChains="+buildChains+", cipher="+cipherMode.configName()
				+", encryptedIndex="+encryptedIndex+", publicKeyHint="+publicKeyHint
				+", encryptionSecret="+(encryptionSecret==null?"absent":"present")+"]";
	}

	private static AccountKey normalisePublicKeyHint(AccountKey hint) {
		return ((hint==null)||AccountKey.ZERO.equals(hint))?null:hint;
	}
}
