package convex.etch;

import java.lang.reflect.Constructor;
import java.nio.channels.FileChannel;

/**
 * Selects the best Etch mapping backend available in the running artefact.
 */
final class EtchFileMapperFactory {
	private static final String FFM_MAPPER_CLASS="convex.etch.FFMEtchFileMapper";

	private EtchFileMapperFactory() {
	}

	static EtchFileMapper create(FileChannel channel, short etchVersion) {
		if ((etchVersion==Etch.ETCH_VERSION_2)&&(Runtime.version().feature()>=22)) {
			try {
				Class<?> type=Class.forName(FFM_MAPPER_CLASS);
				Constructor<?> constructor=type.getDeclaredConstructor(FileChannel.class);
				return (EtchFileMapper)constructor.newInstance(channel);
			} catch (ClassNotFoundException e) {
				// A build performed on JDK 21 contains only the compatible NIO backend.
			} catch (ReflectiveOperationException | LinkageError e) {
				throw new IllegalStateException("Unable to initialise Etch FFM mapping backend",e);
			}
		}
		return new MappedByteBufferEtchFileMapper(channel);
	}
}
