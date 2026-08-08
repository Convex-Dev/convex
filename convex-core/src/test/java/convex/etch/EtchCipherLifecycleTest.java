package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/** Lifecycle checks for file-scoped cipher keys and per-thread operation state. */
public class EtchCipherLifecycleTest {

	@Test
	public void testAESDestroyAcrossWorkerThreads() throws Exception {
		assertDestroyedLifecycle(()->AES256CTREtchCipher.fromKey(sequence()));
	}

	@Test
	public void testChaChaDestroyAcrossWorkerThreads() throws Exception {
		assertDestroyedLifecycle(()->ChaCha20EtchCipher.fromKey(sequence()));
	}

	private static void assertDestroyedLifecycle(Supplier<EtchFileCipher> factory)
			throws Exception {
		EtchFileCipher cipher=factory.get();
		ExecutorService executor=Executors.newFixedThreadPool(2);
		List<Object> states;
		try {
			CyclicBarrier start=new CyclicBarrier(2);
			List<Future<Long>> futures=new ArrayList<>();
			for (int i=0;i<2;i++) {
				long offset=i*Long.BYTES;
				futures.add(executor.submit(()->{
					start.await();
					return cipher.transformLong(offset,0x0123456789abcdefL);
				}));
			}
			for (Future<Long> future:futures) future.get();

			states=createdStates(cipher);
			assertEquals(2,states.size());
			Class<?> stateClass=states.get(0).getClass();
			assertTrue(Modifier.isStatic(stateClass.getModifiers()),
					"ThreadLocal state must not retain its cipher owner");

			cipher.destroy();
			cipher.destroy();
			assertTrue(cipher.isDestroyed());
			assertEquals(0,createdStates(cipher).size());
			assertThrows(IllegalStateException.class,()->cipher.initialise(0L));
			assertThrows(IllegalStateException.class,()->cipher.transformLong(0L,0L));
			for (Object state:states) assertArrayFieldsZero(state);
			assertOwnedKeyZero(cipher);
		} finally {
			executor.shutdownNow();
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Object> createdStates(EtchFileCipher cipher) throws Exception {
		Field field=cipher.getClass().getDeclaredField("createdStates");
		field.setAccessible(true);
		return List.copyOf((List<Object>)field.get(cipher));
	}

	private static void assertOwnedKeyZero(EtchFileCipher cipher) throws Exception {
		if (cipher instanceof AES256CTREtchCipher) {
			Field keyField=cipher.getClass().getDeclaredField("key");
			keyField.setAccessible(true);
			Object key=keyField.get(cipher);
			Field encodedField=key.getClass().getDeclaredField("encoded");
			encodedField.setAccessible(true);
			assertZero((byte[])encodedField.get(key));
		} else {
			Field keyField=cipher.getClass().getDeclaredField("keyWords");
			keyField.setAccessible(true);
			assertZero((int[])keyField.get(cipher));
		}
	}

	private static void assertArrayFieldsZero(Object target) throws Exception {
		for (Field field:target.getClass().getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;
			field.setAccessible(true);
			Object value=field.get(target);
			if (value instanceof byte[] bytes) assertZero(bytes);
			if (value instanceof int[] ints) assertZero(ints);
		}
	}

	private static void assertZero(byte[] values) {
		for (byte value:values) assertEquals(0,value);
	}

	private static void assertZero(int[] values) {
		for (int value:values) assertEquals(0,value);
	}

	private static byte[] sequence() {
		byte[] result=new byte[32];
		for (int i=0;i<result.length;i++) result[i]=(byte)i;
		return result;
	}
}
