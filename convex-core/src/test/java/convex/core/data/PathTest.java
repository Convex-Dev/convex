package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.cvm.CVMEncoder;
import convex.core.data.prim.CVMLong;

class PathTest {
	private static final AString A=Strings.create("a");
	private static final AString B=Strings.create("b");
	private static final AString C=Strings.create("c");

	@Test void vectorContract() {
		VectorsTest.doVectorTests(Path.of());
		VectorsTest.doVectorTests(Path.of(A,B,C));
		VectorsTest.doVectorTests(Path.of(A,B,C).slice(1));
		// Beyond one chunk, operations delegated to the canonical vector use a tree
		AString[] many=new AString[40];
		for (int i=0; i<many.length; i++) many[i]=Strings.create("s"+i);
		VectorsTest.doVectorTests(Path.of(many));
		VectorsTest.doVectorTests(Path.of(many).slice(3,37));
		assertNull(Path.of().next());
		assertNull(Path.of(A).slice(1).next());
	}

	@Test void equalsAndHashesAsTheCanonicalVectorWhateverItsPosition() {
		AVector<AString> vector=Vectors.of(A,B);
		Path path=Path.of(C,A,B).slice(1);
		assertEquals(vector,path);
		assertEquals(path,vector);
		assertEquals(Path.of(A,B),path);
		assertEquals(vector.hashCode(),path.hashCode());
		assertEquals(vector.getEncoding(),path.getEncoding());
		assertEquals(vector.getHash(),path.getHash());
		assertNotEquals(Path.of(A,C),path);
		assertNotEquals(Path.of(A),path);
		assertNotEquals(Vectors.of(A,CVMLong.ONE),path);
		assertFalse(path.isCanonical());
		assertSame(path.getCanonical(),path.toVector());

		HashMap<ACell,String> byKey=new HashMap<>();
		byKey.put(vector,"found");
		assertEquals("found",byKey.get(path));
		byKey.clear();
		byKey.put(path,"found");
		assertEquals("found",byKey.get(vector));
	}

	@Test void encodesInsideStructuresAsTheCanonicalVector() throws Exception {
		AMap<ACell,ACell> withVector=Maps.of(A,Vectors.of(B,C));
		AMap<ACell,ACell> withPath=Maps.of(A,Path.of(A,B,C).slice(1));
		assertEquals(withVector,withPath);
		assertEquals(withVector.getEncoding(),withPath.getEncoding());
		assertEquals(withVector,CVMEncoder.INSTANCE.decode(withPath.getEncoding()));
		assertEquals("[\"b\" \"c\"]",Path.of(B,C).toString());
	}

	@Test void slicesShareTheRequestAndKeepTheirPosition() {
		Path request=Path.of(A,B,C);
		assertEquals(0,request.position());
		assertSame(request,request.request());
		Path rest=request.slice(1);
		assertEquals(1,rest.position());
		assertEquals(B,rest.get(0));
		assertEquals(request,rest.request());
		assertEquals(Path.of(B),rest.slice(0,1));
		assertEquals(2,rest.next().position());
		assertNull(rest.next().next());
		Path end=request.slice(3);
		assertTrue(end.isEmpty());
		assertEquals(3,end.position());
		assertSame(request,request.slice(0));
		assertNull(request.slice(2,1));
		assertThrows(IndexOutOfBoundsException.class,() -> rest.get(2));
	}

	@Test void ownsItsSegmentsAndRefusesNull() {
		AString[] segments={A,B};
		Path path=Path.of(segments);
		segments[0]=C;
		assertEquals(Path.of(A,B),path);
		assertThrows(NullPointerException.class,() -> Path.of(A,null));
	}
}
