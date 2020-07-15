package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.test.Samples;

public class StreamsTest {

	@Test
	public void testIntStream() throws InvalidDataException, ValidationException {
		AVector<Integer> v = Samples.INT_VECTOR_300;
		Stream<Integer> s = v.stream();

		List<Integer> list = s.map(i -> i).collect(Collectors.toList());
		assertEquals(v.size(), list.size());

		AVector<Integer> v2 = Vectors.create(list);
		v2.validate();
		assertEquals(v.getClass(), v2.getClass());
		assertEquals(v, v2);

	}

}
