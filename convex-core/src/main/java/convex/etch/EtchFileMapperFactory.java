package convex.etch;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Selects the best Etch mapping backend available in the running artefact.
 */
final class EtchFileMapperFactory {
	private static final String FFM_MAPPER_CLASS="convex.etch.FFMFileMapper";
	private static final boolean FFM_AVAILABLE=detectFFM();

	private EtchFileMapperFactory() {
	}

	static AFileMapper create(FileChannel channel, short etchVersion) throws IOException {
		return create(channel,defaultMapping(etchVersion));
	}

	static AFileMapper create(FileChannel channel, EtchConfig.MappingMode mappingMode)
			throws IOException {
		return create(channel,mappingMode,channel.size(),"mapped Etch file",false);
	}

	static AFileMapper create(FileChannel channel, EtchConfig.MappingMode mappingMode,
			long length, String fileName) throws IOException {
		return create(channel,mappingMode,length,fileName,false);
	}

	static AFileMapper createReadOnly(FileChannel channel, EtchConfig.MappingMode mappingMode,
			String fileName) throws IOException {
		return create(channel,mappingMode,channel.size(),fileName,true);
	}

	static AFileMapper createExisting(FileChannel channel, EtchConfig requestedConfig,
			String fileName, boolean readOnly) throws IOException {
		Short version=probeVersion(channel);
		EtchConfig.MappingMode mappingMode;
		if (requestedConfig!=null) {
			mappingMode=requestedConfig.getMappingMode();
		} else {
			mappingMode=(version==null)?EtchConfig.MappingMode.MAPPED_BYTE_BUFFER
					:defaultMapping(version);
		}
		return create(channel,mappingMode,channel.size(),fileName,readOnly);
	}

	private static Short probeVersion(FileChannel channel) throws IOException {
		byte[] first=readProbe(channel,0L);
		if (first==null) return null;
		int magic=((first[0]&0xff)<<8)|(first[1]&0xff);
		short version=(short)(((first[2]&0xff)<<8)|(first[3]&0xff));
		if ((magic==EtchConstants.MAGIC_NUMBER)&&(version==EtchConstants.VERSION_3)) {
			return version;
		}
		byte[] second=readProbe(channel,EtchConstants.V3_HEADER_B_OFFSET);
		if (second!=null) {
			int secondMagic=((second[0]&0xff)<<8)|(second[1]&0xff);
			short secondVersion=(short)(((second[2]&0xff)<<8)|(second[3]&0xff));
			if ((secondMagic==EtchConstants.MAGIC_NUMBER)
					&&(secondVersion==EtchConstants.VERSION_3)) return secondVersion;
		}
		if ((magic==EtchConstants.MAGIC_NUMBER)
				&&((version==EtchConstants.VERSION_1)||(version==EtchConstants.VERSION_2))) {
			return version;
		}
		return null;
	}

	private static byte[] readProbe(FileChannel channel, long position) throws IOException {
		if (channel.size()<position+Short.BYTES*2L) return null;
		byte[] bytes=new byte[Short.BYTES*2];
		ByteBuffer buffer=ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining()) {
			if (channel.read(buffer,position+buffer.position())<=0) return null;
		}
		return bytes;
	}

	private static AFileMapper create(FileChannel channel, EtchConfig.MappingMode mappingMode,
			long length, String fileName, boolean readOnly) throws IOException {
		Objects.requireNonNull(mappingMode,"mappingMode");
		return switch (mappingMode) {
			case MAPPED_BYTE_BUFFER -> new MBBFileMapper(channel,readOnly,length,fileName);
			case MEMORY_SEGMENT -> {
				try {
					Class<?> type=Class.forName(FFM_MAPPER_CLASS);
					Constructor<?> constructor=type.getDeclaredConstructor(FileChannel.class,
							boolean.class,long.class,String.class);
					yield (AFileMapper)constructor.newInstance(channel,readOnly,length,fileName);
				} catch (InvocationTargetException e) {
					Throwable cause=e.getCause();
					if (cause instanceof IOException io) throw io;
					if (cause instanceof RuntimeException runtime) throw runtime;
					if (cause instanceof Error error) throw error;
					throw new IllegalStateException("Unable to initialise Etch FFM mapping backend",cause);
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
