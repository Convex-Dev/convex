package convex.cli.mixins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.util.FileUtils;
import convex.etch.EtchConfig;
import convex.etch.EtchConfig.CipherMode;
import convex.peer.Config;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Safe source and destination configuration for Etch CLI commands.
 *
 * <p>Raw master keys are deliberately never command-line values. They may be
 * read from a file, standard input or a hidden interactive prompt. A Convex
 * PKCS12 key may instead be selected by alias; when no alias is supplied, an
 * encrypted v3 header's public-key hint is used automatically.</p>
 */
public class EtchConfigMixin extends AMixin {

	@Option(names="--etch-key",
			defaultValue="${env:CONVEX_ETCH_KEY}", scope=ScopeType.INHERIT,
			description="Convex keystore key alias or public-key prefix used to derive the source Etch key.")
	private String sourceKeyAlias;

	@Option(names="--etch-keypass",
			defaultValue="${env:CONVEX_ETCH_KEY_PASSWORD}", scope=ScopeType.INHERIT,
			description="Password for the source Convex keystore key entry.")
	private char[] sourceKeyPassword;

	@Option(names="--etch-key-file",
			defaultValue="${env:CONVEX_ETCH_KEY_FILE}", scope=ScopeType.INHERIT,
			description="Owner-protected file containing the source 32-byte master key (raw or hex); '-' reads stdin.")
	private String sourceKeyFile;

	@Option(names="--into-key",
			defaultValue="${env:CONVEX_ETCH_INTO_KEY}", scope=ScopeType.INHERIT,
			description="Convex keystore key alias or public-key prefix used to derive the destination Etch key.")
	private String destinationKeyAlias;

	@Option(names="--into-keypass",
			defaultValue="${env:CONVEX_ETCH_INTO_KEY_PASSWORD}", scope=ScopeType.INHERIT,
			description="Password for the destination Convex keystore key entry.")
	private char[] destinationKeyPassword;

	@Option(names="--into-key-file",
			defaultValue="${env:CONVEX_ETCH_INTO_KEY_FILE}", scope=ScopeType.INHERIT,
			description="Owner-protected file containing the destination 32-byte master key (raw or hex); '-' reads stdin.")
	private String destinationKeyFile;

	@Option(names="--into-version", scope=ScopeType.INHERIT,
			description="Etch destination format version (1, 2 or 3).")
	private Short destinationVersion;

	@Option(names="--into-cipher", scope=ScopeType.INHERIT,
			description="Etch v3 destination cipher: none, aes-256-ctr or chacha20.")
	private String destinationCipher;

	@Option(names="--into-encrypt-index", negatable=true, scope=ScopeType.INHERIT,
			description="Encrypt the Etch v3 destination index (or --no-into-encrypt-index).")
	private Boolean destinationEncryptedIndex;

	@Option(names="--into-public-key-hint", scope=ScopeType.INHERIT,
			description="Public key recorded in the Etch v3 destination header as a key lookup hint.")
	private String destinationPublicKeyHint;

	/** Creates a source resolver. Plaintext Etch files never call it. */
	public KeyContext sourceContext(KeyStoreMixin keyStore) {
		return new KeyContext(keyStore,false,sourceKeyAlias,sourceKeyPassword,sourceKeyFile);
	}

	/** Creates an independent destination resolver. */
	public KeyContext destinationContext(KeyStoreMixin keyStore) {
		if ("-".equals(sourceKeyFile)&&"-".equals(destinationKeyFile)) {
			throw new CLIError(ExitCodes.USAGE,
					"Source and destination Etch keys cannot both consume standard input");
		}
		return new KeyContext(keyStore,true,destinationKeyAlias,
				destinationKeyPassword,destinationKeyFile);
	}

	/** Configuration used to probe and open an existing source file. */
	public EtchConfig sourceConfig(KeyContext keys) {
		return EtchConfig.create().withKeyFunction(keys::resolve);
	}

	/** True when an independent destination secret provider was explicit. */
	public boolean hasDestinationKey() {
		return (destinationKeyAlias!=null)||(destinationKeyFile!=null);
	}

	/**
	 * Builds a destination creation policy. Existing destination headers remain
	 * authoritative when Etch opens them.
	 *
	 * @param source effective source configuration
	 * @param keys destination key context
	 * @param preserveSource whether omitted policy fields inherit from source
	 */
	public EtchConfig destinationConfig(EtchConfig source, KeyContext keys,
			boolean preserveSource) {
		EtchConfig base=preserveSource?source:EtchConfig.create();
		boolean encryptionOption=(destinationCipher!=null)
				||(destinationEncryptedIndex!=null)
				||(destinationPublicKeyHint!=null);
		short version=(destinationVersion!=null)?destinationVersion
				:(encryptionOption?(short)3:base.getVersion());
		EtchConfig versionDefaults=EtchConfig.create(version);
		EtchConfig.MappingMode mapping=(version==base.getVersion())
				?base.getMappingMode():versionDefaults.getMappingMode();
		boolean buildChains=base.isBuildChains();

		CipherMode cipher=(destinationCipher!=null)?parseCipher(destinationCipher)
				:((version==base.getVersion())?base.getCipherMode():CipherMode.NONE);
		boolean encryptedIndex=(destinationEncryptedIndex!=null)
				?destinationEncryptedIndex
				:((version==base.getVersion())&&base.isIndexEncrypted());

		AccountKey hint=parseHint(destinationPublicKeyHint);
		if (destinationPublicKeyHint==null) {
			if (hasDestinationKey()&&(version==3)&&(cipher!=CipherMode.NONE)) {
				hint=keys.selectedPublicKey(); // null for an opaque raw key
			} else if (preserveSource) {
				hint=base.getPublicKeyHint();
			}
		}

		try {
			if (version==3) {
				return EtchConfig.createV3(mapping,buildChains,cipher,encryptedIndex,
						hint,keys::resolve);
			}
			if ((cipher!=CipherMode.NONE)||encryptedIndex||(hint!=null)) {
				throw new CLIError(ExitCodes.USAGE,
						"Etch encryption and public-key hints require --into-version 3");
			}
			return EtchConfig.create(version,mapping,buildChains);
		} catch (IllegalArgumentException e) {
			throw new CLIError(ExitCodes.USAGE,"Invalid Etch destination configuration: "
					+e.getMessage(),e);
		}
	}

	private static CipherMode parseCipher(String value) {
		return switch (value.toLowerCase()) {
			case "none" -> CipherMode.NONE;
			case "aes-256-ctr" -> CipherMode.AES_256_CTR;
			case "chacha20" -> CipherMode.CHACHA20;
			default -> throw new CLIError(ExitCodes.USAGE,
					"Unsupported Etch destination cipher: "+value);
		};
	}

	private static AccountKey parseHint(String value) {
		if (value==null) return null;
		AccountKey hint=AccountKey.parse(value);
		if (hint==null) throw new CLIError(ExitCodes.USAGE,
				"Invalid Etch destination public-key hint: expected 32-byte public key");
		return hint;
	}

	/** Short-lived secret resolver. Close it as soon as the command finishes. */
	public final class KeyContext implements AutoCloseable {
		private final KeyStoreMixin keyStore;
		private final boolean destination;
		private final String alias;
		private final char[] configuredPassword;
		private final String keyFile;
		private final ArrayList<byte[]> borrowed=new ArrayList<>();
		private byte[] masterKey;
		private AccountKey selectedPublicKey;
		private char[] promptedPassword;
		private boolean closed;

		private KeyContext(KeyStoreMixin keyStore, boolean destination, String alias,
				char[] configuredPassword, String keyFile) {
			this.keyStore=keyStore;
			this.destination=destination;
			this.alias=(alias==null)?null:KeyStoreMixin.trimKey(alias);
			this.configuredPassword=configuredPassword;
			this.keyFile=keyFile;
			if ((this.alias!=null)&&(keyFile!=null)) {
				throw new CLIError(ExitCodes.USAGE,"Specify only one of "
						+(destination?"--into-key or --into-key-file"
								:"--etch-key or --etch-key-file"));
			}
		}

		/** Copies an already resolved source secret into this independent context. */
		public void inheritResolvedKey(KeyContext source) {
			if (hasExplicitProvider()||(source.masterKey==null)) return;
			clearMasterKey();
			masterKey=source.masterKey.clone();
			selectedPublicKey=source.selectedPublicKey;
		}

		private boolean hasExplicitProvider() {
			return (alias!=null)||(keyFile!=null);
		}

		private byte[] resolve(AccountKey headerHint) {
			ensureOpen();
			if (masterKey==null) loadMasterKey(headerHint);
			if ((selectedPublicKey!=null)&&(headerHint!=null)
					&&!selectedPublicKey.equals(headerHint)) {
				throw new CLIError(ExitCodes.CONFIG,"Selected Etch key "
						+selectedPublicKey+" does not match public-key hint "+headerHint);
			}
			byte[] result=masterKey.clone();
			borrowed.add(result);
			return result;
		}

		private AccountKey selectedPublicKey() {
			ensureOpen();
			if ((selectedPublicKey==null)&&(alias!=null)) loadMasterKey(null);
			return selectedPublicKey;
		}

		private void loadMasterKey(AccountKey headerHint) {
			if (keyFile!=null) {
				masterKey=readKeyFile(keyFile);
				return;
			}

			String keySpec=(alias!=null)?alias
					:((headerHint==null)?null:headerHint.toHexString());
			if (keySpec!=null) {
				AKeyPair keyPair=keyStore.loadExistingKeyFromStore(keySpec,this::keyPassword);
				if (keyPair!=null) {
					selectedPublicKey=keyPair.getAccountKey();
					masterKey=Config.deriveEtchMasterKey(keyPair);
					return;
				}
				if (alias!=null) {
					throw new CLIError(ExitCodes.CONFIG,
							"Etch key not found in configured keystore: "+keySpec);
				}
			}

			if (isInteractive()) {
				char[] chars=readPassword((destination?"Destination":"Source")
						+" Etch master key (64 hex digits): ");
				try {
					if (chars==null) throw new CLIError(ExitCodes.NOINPUT,
							"No Etch master key was entered");
					masterKey=decodeHex(chars);
				} finally {
					if (chars!=null) Arrays.fill(chars,'\0');
				}
				return;
			}

			String detail=(headerHint==null)?"the file has no public-key hint"
					:"no matching key exists in the configured keystore";
			throw new CLIError(ExitCodes.CONFIG,"Cannot resolve encrypted "
					+(destination?"destination":"source")+" Etch key: "+detail
					+". Use "+(destination?"--into-key/--into-key-file"
							:"--etch-key/--etch-key-file")+" or enable interactive mode.");
		}

		private char[] keyPassword() {
			if (configuredPassword!=null) return configuredPassword;
			if (promptedPassword!=null) return promptedPassword;
			if (isInteractive()) {
				promptedPassword=readPassword((destination?"Destination":"Source")
						+" Etch keystore key password: ");
				if (promptedPassword==null) throw new CLIError(ExitCodes.NOINPUT,
						"No Etch keystore key password was entered");
				return promptedPassword;
			}
			if (isParanoid()) throw new CLIError(ExitCodes.CONFIG,
					"Etch keystore key password required in strict-security mode");
			return new char[0];
		}

		private byte[] readKeyFile(String filename) {
			try {
				byte[] input;
				if ("-".equals(filename)) {
					input=readBounded(System.in);
				} else {
					Path path=FileUtils.getFile(filename).toPath();
					checkPermissions(path);
					try (InputStream in=Files.newInputStream(path)) {
						input=readBounded(in);
					}
				}
				try {
					return decodeKeyBytes(input);
				} finally {
					Arrays.fill(input,(byte)0);
				}
			} catch (CLIError e) {
				throw e;
			} catch (IOException e) {
				throw new CLIError(ExitCodes.NOINPUT,"Cannot read Etch key from "
						+("-".equals(filename)?"standard input":filename),e);
			}
		}

		private void checkPermissions(Path path) throws IOException {
			try {
				var permissions=Files.getPosixFilePermissions(path);
				var unsafe=EnumSet.of(PosixFilePermission.GROUP_READ,
						PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE,
						PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,
						PosixFilePermission.OTHERS_EXECUTE);
				if (!java.util.Collections.disjoint(permissions,unsafe)) {
					String message="Etch key file is accessible by group or other users: "+path;
					paranoia(message);
					informWarning(message);
				}
			} catch (UnsupportedOperationException e) {
				// Windows and other non-POSIX file systems enforce access via ACLs.
			}
		}

		private void ensureOpen() {
			if (closed) throw new IllegalStateException("Etch key context is closed");
		}

		private void clearMasterKey() {
			if (masterKey!=null) Arrays.fill(masterKey,(byte)0);
			masterKey=null;
		}

		@Override
		public void close() {
			if (closed) return;
			closed=true;
			clearMasterKey();
			for (byte[] key:borrowed) Arrays.fill(key,(byte)0);
			borrowed.clear();
			if (promptedPassword!=null) Arrays.fill(promptedPassword,'\0');
			promptedPassword=null;
		}
	}

	private static byte[] readBounded(InputStream in) throws IOException {
		byte[] bytes=in.readNBytes(4097);
		if (bytes.length>4096) {
			Arrays.fill(bytes,(byte)0);
			throw new CLIError(ExitCodes.DATAERR,"Etch key input is unexpectedly large");
		}
		return bytes;
	}

	private static byte[] decodeKeyBytes(byte[] bytes) {
		if (bytes.length==32) return bytes.clone();
		int start=0;
		int end=bytes.length;
		while ((start<end)&&isAsciiSpace(bytes[start])) start++;
		while ((end>start)&&isAsciiSpace(bytes[end-1])) end--;
		char[] chars=new char[end-start];
		for (int i=0;i<chars.length;i++) chars[i]=(char)(bytes[start+i]&0xff);
		try {
			return decodeHex(chars);
		} finally {
			Arrays.fill(chars,'\0');
		}
	}

	private static byte[] decodeHex(char[] chars) {
		int start=0;
		int end=chars.length;
		while ((start<end)&&Character.isWhitespace(chars[start])) start++;
		while ((end>start)&&Character.isWhitespace(chars[end-1])) end--;
		if (((end-start)==66)&&(chars[start]=='0')
				&&((chars[start+1]=='x')||(chars[start+1]=='X'))) start+=2;
		if ((end-start)!=64) throw new CLIError(ExitCodes.DATAERR,
				"Etch master key must contain exactly 64 hexadecimal digits");
		byte[] result=new byte[32];
		for (int i=0;i<32;i++) {
			int high=hexDigit(chars[start+i*2]);
			int low=hexDigit(chars[start+i*2+1]);
			if ((high<0)||(low<0)) {
				Arrays.fill(result,(byte)0);
				throw new CLIError(ExitCodes.DATAERR,"Etch master key is not valid hexadecimal");
			}
			result[i]=(byte)((high<<4)|low);
		}
		return result;
	}

	private static boolean isAsciiSpace(byte value) {
		return Character.isWhitespace((char)(value&0xff));
	}

	private static int hexDigit(char value) {
		return Character.digit(value,16);
	}
}
