package convex.etch;

import java.lang.reflect.Constructor;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Selects the best Etch mapping backend available in the running artefact.
 */
final class EtchFileMapperFactory {
	private static final String FFM_MAPPER_CLASS="convex.etch.FFMEtchFileMapper";
	private static final boolean FFM_AVAILABLE=detectFFM();

	private EtchFileMapperFactory() {
	}

	static EtchFileMapper create(FileChannel channel, short etchVersion) {
		return create(channel,defaultMapping(etchVersion));
	}

	static EtchFileMapper create(FileChannel channel, EtchConfig.MappingMode mappingMode) {
		Objects.requireNonNull(mappingMode,"mappingMode");
		return switch (mappingMode) {
			case MAPPED_BYTE_BUFFER -> new MappedByteBufferEtchFileMapper(channel);
			case MEMORY_SEGMENT -> {
				try {
					Class<?> type=Class.forName(FFM_MAPPER_CLASS);
					Constructor<?> constructor=type.getDeclaredConstructor(FileChannel.class);
					yield (EtchFileMapper)constructor.newInstance(channel);
				} catch (ReflectiveOperationException | LinkageError e) {
					throw new IllegalStateException("Unable to initialise Etch FFM mapping backend",e);
				}
			}
		};
	}

	static EtchConfig.MappingMode defaultMapping(short etchVersion) {
		return switch (etchVersion) {
			case EtchConstants.VERSION_1 -> EtchConfig.MappingMode.MAPPED_BYTE_BUFFER;
			case EtchConstants.VERSION_2, EtchConstants.VERSION_3 -> FFM_AVAILABLE
					?EtchConfig.MappingMode.MEMORY_SEGMENT
					:EtchConfig.MappingMode.MAPPED_BYTE_BUFFER;
			default -> throw new IllegalArgumentException("Unsupported Etch version: "+etchVersion);
		};
	}

	static void validate(short etchVersion, EtchConfig.MappingMode mappingMode) {
		Objects.requireNonNull(mappingMode,"mappingMode");
		if ((etchVersion==EtchConstants.VERSION_1)&&(mappingMode==EtchConfig.MappingMode.MEMORY_SEGMENT)) {
			throw new IllegalArgumentException("Etch v1 requires the mapped-byte-buffer backend");
		}
		if ((mappingMode==EtchConfig.MappingMode.MEMORY_SEGMENT)&&!FFM_AVAILABLE) {
			throw new IllegalArgumentException("Etch memory-segment backend is not available in this runtime artefact");
		}
	}

	static boolean isFFMAvailable() {
		return FFM_AVAILABLE;
	}

	private static boolean detectFFM() {
		if (Runtime.version().feature()<22) return false;
		try {
			Class.forName(FFM_MAPPER_CLASS);
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
