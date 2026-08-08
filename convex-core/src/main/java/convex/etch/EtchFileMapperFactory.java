package convex.etch;

import java.io.IOException;
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
		return create(channel,mappingMode,false);
	}

	static EtchFileMapper createReadOnly(FileChannel channel, EtchConfig.MappingMode mappingMode) {
		return new ReadOnlyEtchFileMapper(create(channel,mappingMode,true));
	}

	private static EtchFileMapper create(FileChannel channel, EtchConfig.MappingMode mappingMode,
			boolean readOnly) {
		Objects.requireNonNull(mappingMode,"mappingMode");
		return switch (mappingMode) {
			case MAPPED_BYTE_BUFFER -> new MappedByteBufferEtchFileMapper(channel,readOnly);
			case MEMORY_SEGMENT -> {
				try {
					Class<?> type=Class.forName(FFM_MAPPER_CLASS);
					Constructor<?> constructor=type.getDeclaredConstructor(FileChannel.class,boolean.class);
					yield (EtchFileMapper)constructor.newInstance(channel,readOnly);
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

	/**
	 * Maintenance-only guard around a physically read-only mapping. Keeping the
	 * guard here avoids adding a read-only branch to ordinary Etch write paths.
	 */
	private static final class ReadOnlyEtchFileMapper implements EtchFileMapper {
		private final EtchFileMapper delegate;

		private ReadOnlyEtchFileMapper(EtchFileMapper delegate) {
			this.delegate=delegate;
		}

		@Override
		public void get(long position, byte[] destination, int offset, int length)
				throws IOException {
			delegate.get(position,destination,offset,length);
		}

		@Override
		public boolean matches(long position, byte[] expected, int offset, int length)
				throws IOException {
			return delegate.matches(position,expected,offset,length);
		}

		@Override
		public void getTransformed(long position, byte[] destination, int offset, int length,
				EtchFileCipher cipher) throws IOException {
			delegate.getTransformed(position,destination,offset,length,cipher);
		}

		@Override
		public void ensureWriteCapacity(long position, long length) throws IOException {
			throw readOnly();
		}

		@Override
		public void put(long position, byte[] source, int offset, int length)
				throws IOException {
			throw readOnly();
		}

		@Override
		public void putTransformed(long position, byte[] source, int offset, int length,
				EtchFileCipher cipher) throws IOException {
			throw readOnly();
		}

		@Override
		public long readIndexSlotAcquire(long position) throws IOException {
			return delegate.readIndexSlotAcquire(position);
		}

		@Override
		public void writeIndexSlotRelease(long position, long value) throws IOException {
			throw readOnly();
		}

		@Override
		public void force() {
			// A maintenance reader has no dirty pages to force.
		}

		@Override
		public void forceRange(long position, long length) {
			// A maintenance reader has no dirty pages to force.
		}

		@Override
		public String implementationName() {
			return delegate.implementationName();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		private IOException readOnly() {
			return new IOException("Etch mapping is read-only");
		}
	}
}
