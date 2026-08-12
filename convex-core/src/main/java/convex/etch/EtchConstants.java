package convex.etch;

import convex.core.data.AccountKey;
import convex.core.data.AArrayBlob;
import convex.core.data.Hash;

/**
 * Constants shared by Etch file implementations and format tooling.
 *
 * <p>Public constants describe stable on-disk values that external readers,
 * validators and migration tools may need. Package-private constants are
 * implementation tuning parameters and are not part of the file format.</p>
 */
public final class EtchConstants {

	private EtchConstants() {
	}

	// File identity and supported versions

	/** Unsigned 16-bit magic number at the start of every Etch file. */
	public static final int MAGIC_NUMBER=0xe7c6;

	public static final short VERSION_1=1;
	public static final short VERSION_2=2;
	/** Etch v3 dual-header format with optional file-overlay encryption. */
	public static final short VERSION_3=3;
	/** Version written by default for newly created Etch files. */
	public static final short CURRENT_VERSION=VERSION_2;

	// Header layout

	public static final int MAGIC_NUMBER_SIZE=Short.BYTES;
	public static final int VERSION_SIZE=Short.BYTES;
	public static final int DATA_LENGTH_SIZE=Long.BYTES;
	public static final int ROOT_HASH_SIZE=Hash.LENGTH;

	public static final long VERSION_OFFSET=MAGIC_NUMBER_SIZE;
	public static final long DATA_LENGTH_OFFSET=VERSION_OFFSET+VERSION_SIZE;
	public static final long ROOT_HASH_OFFSET=DATA_LENGTH_OFFSET+DATA_LENGTH_SIZE;

	public static final int COMMON_HEADER_SIZE=
			MAGIC_NUMBER_SIZE+VERSION_SIZE+DATA_LENGTH_SIZE+ROOT_HASH_SIZE;
	public static final int V1_HEADER_SIZE=COMMON_HEADER_SIZE;
	public static final int V2_HEADER_SIZE=64;
	/** Size of the immutable per-file salt in an Etch v3 header. */
	public static final int V3_FILE_SALT_SIZE=32;
	/** Size of caller-provided Etch v3 master keys. */
	public static final int V3_MASTER_KEY_SIZE=32;
	public static final int V3_HEADER_COPY_SIZE=4096;
	public static final int V3_HEADER_REGION_SIZE=2*V3_HEADER_COPY_SIZE;
	public static final int V3_CIPHER_OFFSET=0x004;
	public static final int V3_INDEX_ENCRYPTION_OFFSET=0x006;
	public static final int V3_GENERATION_OFFSET=0x008;
	public static final int V3_SYNCED_FILE_END_OFFSET=0x010;
	public static final int V3_INDEX_START_OFFSET=0x018;
	public static final int V3_ROOT_HASH_OFFSET=0x020;
	public static final int V3_FILE_SALT_OFFSET=0x040;
	public static final int V3_PUBLIC_KEY_HINT_OFFSET=0x060;
	public static final int V3_PUBLIC_KEY_HINT_SIZE=AccountKey.LENGTH;
	public static final int V3_CLOSE_STATE_OFFSET=0x080;
	public static final int V3_HEADER_PREFIX_SIZE=0x088;
	public static final int V3_HEADER_CHECK_SIZE=32;
	public static final int V3_HEADER_CHECK_OFFSET=0xfe0;

	public static final long V3_HEADER_A_OFFSET=0L;
	public static final long V3_HEADER_B_OFFSET=V3_HEADER_COPY_SIZE;
	public static final long V3_INDEX_START=V3_HEADER_REGION_SIZE;

	public static final int V3_CIPHER_NONE=0;
	public static final int V3_CIPHER_AES_256_CTR=1;
	public static final int V3_CIPHER_CHACHA20=2;
	/** Size of the canonical v3 cipher-block locator. */
	public static final int V3_CIPHER_LOCATOR_SIZE=16;
	/** Base-two block-size shift for AES: {@code 1 << 4 == 16} bytes. */
	public static final int V3_AES_BLOCK_SHIFT=4;
	/** AES cipher-block size in bytes. */
	public static final int V3_AES_BLOCK_SIZE=1<<V3_AES_BLOCK_SHIFT;
	/** Base-two block-size shift for ChaCha20: {@code 1 << 6 == 64} bytes. */
	public static final int V3_CHACHA_BLOCK_SHIFT=6;
	/** ChaCha20 cipher-block size in bytes. */
	public static final int V3_CHACHA_BLOCK_SIZE=1<<V3_CHACHA_BLOCK_SHIFT;
	/** Size of the ChaCha20 nonce prefix in the canonical locator. */
	public static final int V3_CHACHA_NONCE_SIZE=12;
	/** Number of counter bits in the ChaCha20 locator suffix. */
	public static final int V3_CHACHA_COUNTER_BITS=Integer.SIZE;
	/** Number of file bytes addressed by one ChaCha20 nonce. */
	public static final long V3_CHACHA_NONCE_REGION_SIZE=
			(1L<<V3_CHACHA_COUNTER_BITS)*V3_CHACHA_BLOCK_SIZE;
	public static final int V3_INDEX_PLAINTEXT=0;
	public static final int V3_INDEX_ENCRYPTED=1;
	public static final long V3_OPEN=0L;
	public static final long V3_CLEAN_CLOSED=1L;

	public static final long V1_INDEX_START=V1_HEADER_SIZE;
	public static final long V2_INDEX_START=V2_HEADER_SIZE;

	// Stored record and radix index layout

	public static final int KEY_SIZE=Hash.LENGTH;
	public static final int LABEL_SIZE=Byte.BYTES+Long.BYTES;
	public static final int ENCODING_LENGTH_SIZE=Short.BYTES;
	public static final int POINTER_SIZE=Long.BYTES;

	/** Maximum radix depth for a 32-byte hash key. */
	public static final int MAX_LEVEL=60;

	public static final int ROOT_INDEX_SIZE=1<<16;
	public static final int SECOND_LEVEL_INDEX_SIZE=1<<8;
	public static final int DEEP_INDEX_SIZE=1<<4;

	/** Returns the number of slots in an index block at the given radix level. */
	static int indexSize(int level) {
		if (level==0) return ROOT_INDEX_SIZE;
		if (level==1) return SECOND_LEVEL_INDEX_SIZE;
		return DEEP_INDEX_SIZE;
	}

	/** Returns the Etch radix digit selected from a hash key at a given level. */
	static int indexDigit(AArrayBlob key, int level) {
		if (level==0) return key.shortAt(0)&0xffff;
		if (level==1) return key.byteAt(2)&0xff;
		int byteIndex=(level+4)/2;
		int value=key.byteAt(byteIndex);
		return (((level&1)==0)?(value>>4):value)&0xf;
	}

	public static final long POINTER_TYPE_MASK=0xC000000000000000L;
	public static final long POINTER_PLAIN=0x0000000000000000L;
	public static final long POINTER_INDEX=0x4000000000000000L;
	public static final long POINTER_START=0x8000000000000000L;
	public static final long POINTER_CHAIN=0xC000000000000000L;

	// Configuration defaults

	public static final boolean DEFAULT_BUILD_CHAINS=true;

	// Mapper implementation tuning (not file-format values)

	static final long MAX_REGION_SIZE=1L<<30;
	static final long REGION_MARGIN=1L<<16;
}
