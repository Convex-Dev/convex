package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.MissingValueException;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.Transactions;

class ValueComponentTest {
	private static final AString DATA=Strings.create("data");
	private static final AString NAME=Strings.create("name");
	private static final AString ZERO=Strings.create("0");
	private static final AString JSON=Strings.create("json");
	private static final AString RAW=Strings.create("raw");

	/** The parent chooses an interpretation for the same underlying value. */
	private static class Parent extends ALatticeComponent<ACell> {
		Parent(AForkableCursor<ACell> cursor) { super(cursor); }

		@Override public ALatticeComponent<?> resolve(AString[] path,int start,int end) {
			Objects.checkFromToIndex(start,end,path.length);
			if (start==end) return this;
			if (JSON.equals(path[start])) return new JSONValueComponent<>(cursor().path(DATA)).resolve(path,start+1,end);
			if (RAW.equals(path[start])) return new ValueComponent<>(cursor().path(DATA)).resolve(path,start+1,end);
			return super.resolve(path,start,end);
		}
	}

	@Test void baseHasValuesButNoImplicitNamespaceOrMerge() {
		var root=Root.<ACell>create(null);
		var base=new ALatticeComponent<>(root) {};
		assertNull(base.value());
		assertTrue(base.exists());
		assertSame(base,base.resolve());
		assertSame(base,base.resolve(new AString[] {null},1,1));
		assertThrows(UnsupportedOperationException.class,() -> base.resolve(NAME));
		assertThrows(UnsupportedOperationException.class,() -> new ValueComponent<>(root).merge(CVMLong.ONE));
		assertThrows(UnsupportedOperationException.class,() -> new JSONValueComponent<>(root).merge(CVMLong.ONE));
	}

	@Test void literalResolutionUsesOneOwnedSliceAndDoesNotRead() {
		AtomicInteger reads=new AtomicInteger();
		Root<ACell> root=new Root<>(Maps.of(DATA,Maps.of(NAME,CVMLong.ONE))) {
			@Override public ACell get() { reads.incrementAndGet(); return super.get(); }
		};
		var component=new ValueComponent<>(root);
		AString[] path={null,DATA,NAME,null};
		var child=component.resolve(path,1,3);
		assertEquals(0,reads.get());
		path[1]=NAME;
		path[2]=DATA;
		assertEquals(CVMLong.ONE,child.value());
		child.cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,RT.getIn(root.get(),DATA,NAME));
		assertSame(component,component.resolve(path,2,2));
		assertThrows(IndexOutOfBoundsException.class,() -> component.resolve(path,-1,2));
		assertThrows(IndexOutOfBoundsException.class,() -> component.resolve(path,3,2));
		assertThrows(IndexOutOfBoundsException.class,() -> component.resolve(path,0,5));
		assertThrows(NullPointerException.class,() -> component.resolve(path,0,2));
	}

	@Test void parentSelectsJSONAndDelegatesSlicesInsideTransactions() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var parent=new Parent(root);
		AString[] path={null,JSON,ZERO,null};
		assertEquals(CVMLong.ONE,parent.resolve(path,1,3).value());
		assertFalse(parent.resolve(RAW,ZERO).exists());
		assertThrows(MissingValueException.class,() -> parent.resolve(RAW,ZERO).value());
		assertThrows(UnsupportedOperationException.class,() -> parent.resolve(NAME));
		Transactions.transact(root,draft -> {
			var resolved=new Parent(draft).resolve(path,1,3);
			resolved.cursor().set(CVMLong.TWO);
			assertEquals(CVMLong.ONE,parent.resolve(path,1,3).value());
			assertEquals(CVMLong.TWO,resolved.value());
		});
		assertEquals(CVMLong.TWO,parent.resolve(path,1,3).value());
	}

	@Test void exposedCursorViewIsUsedForValueAndDescendants() {
		var root=Root.<ACell>create(Maps.of(DATA,Maps.of(NAME,CVMLong.ONE),RAW,CVMLong.ZERO));
		var component=new ValueComponent<ACell>(root) {
			private final AForkableCursor<ACell> exposed=ProjectionCursor.create(root,
				stored -> RT.getIn(stored,DATA),(stored,value) -> LatticeOps.assocExisting(stored,value,DATA));
			@Override public AForkableCursor<ACell> cursor() { return exposed; }
		};
		assertEquals(Maps.of(NAME,CVMLong.ONE),component.value());
		component.resolve(NAME).cursor().set(CVMLong.TWO);
		assertEquals(Maps.of(DATA,Maps.of(NAME,CVMLong.TWO),RAW,CVMLong.ZERO),root.get());
	}

	@Test void JSONUsesOneSnapshotAndPreservesSegmentOwnership() {
		AtomicInteger reads=new AtomicInteger();
		Root<ACell> root=new Root<>(Maps.of(DATA,Vectors.of(Maps.of(NAME,CVMLong.ONE)))) {
			@Override public ACell get() { reads.incrementAndGet(); return super.get(); }
		};
		var json=new JSONValueComponent<>(root);
		assertSame(json,json.resolve());
		assertEquals(0,reads.get());
		AString[] path={null,DATA,ZERO,NAME,null};
		var child=json.resolve(path,1,4);
		assertEquals(1,reads.get());
		path[2]=NAME;
		assertEquals(CVMLong.ONE,child.value());
		assertInstanceOf(JSONValueComponent.class,json.resolve(DATA));
		assertEquals(child.value(),json.resolve(DATA).resolve(ZERO).resolve(NAME).value());
		assertThrows(IndexOutOfBoundsException.class,() -> json.resolve(path,1,6));
		assertThrows(NullPointerException.class,() -> json.resolve(path,0,1));
	}

	@Test void JSONObjectKeysRemainNamesAndPresentStringsWinOverKeywords() {
		Keyword keyword=Keyword.create(NAME);
		var root=Root.<ACell>create(Maps.of(NAME,null,keyword,CVMLong.ONE,ZERO,CVMLong.TWO));
		var json=new JSONValueComponent<>(root);
		assertTrue(json.resolve(NAME).exists());
		assertNull(json.resolve(NAME).value());
		assertEquals(CVMLong.TWO,json.resolve(ZERO).value());
		root.set(Maps.of(keyword,null));
		assertTrue(json.resolve(NAME).exists());
		assertNull(json.resolve(NAME).value());
		assertFalse(new ValueComponent<>(root).resolve(NAME).exists());
		root.set(Maps.of(keyword,CVMLong.ONE));
		var chosen=json.resolve(NAME);
		root.set(Maps.of(keyword,CVMLong.ONE,NAME,CVMLong.TWO));
		assertEquals(CVMLong.ONE,chosen.value());
		assertEquals(CVMLong.TWO,json.resolve(NAME).value());
	}

	@Test void JSONArrayIndicesAreDecimalPositionsAndOtherKeysAreAbsent() {
		var json=new JSONValueComponent<>(Root.<ACell>create(Vectors.of(null,CVMLong.ONE)));
		assertTrue(json.resolve(ZERO).exists());
		assertNull(json.resolve(ZERO).value());
		assertEquals(CVMLong.ONE,json.resolve(Strings.create("01")).value());
		for (String segment:new String[] {"", "-1", "+1", "-0", " 1", "1.0", "2", "9223372036854775807", "9223372036854775808", "١"}) {
			var child=json.resolve(Strings.create(segment));
			assertFalse(child.exists(),segment);
			assertThrows(MissingValueException.class,child::value,segment);
		}
		assertFalse(new JSONValueComponent<>(Root.create(Lists.of(CVMLong.ONE))).resolve(ZERO).exists());
		assertFalse(new JSONValueComponent<>(Root.create(Index.of(Blob.fromHex("abcd"),CVMLong.ONE)))
			.resolve(Strings.create("abcd")).exists());
	}

	@Test void JSONKeysStayBoundAndWritesCannotChangeContainerKinds() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var json=new JSONValueComponent<>(root);
		var old=json.resolve(DATA,ZERO);
		ACell changed=Maps.of(DATA,Maps.of(ZERO,CVMLong.TWO,CVMLong.ZERO,CVMLong.ONE));
		root.set(changed);
		assertFalse(old.exists());
		assertThrows(IllegalArgumentException.class,() -> old.cursor().set(CVMLong.TWO));
		assertSame(changed,root.get());
		assertEquals(CVMLong.TWO,json.resolve(DATA,ZERO).value());
		root.set(Maps.empty());
		var absent=json.resolve(DATA,NAME);
		assertFalse(absent.exists());
		assertThrows(IllegalStateException.class,() -> absent.cursor().set(CVMLong.ONE));
		assertEquals(Maps.empty(),root.get());
		json.resolve(NAME).cursor().set(CVMLong.ONE);
		assertEquals(Maps.of(NAME,CVMLong.ONE),root.get());
	}

	@Test void JSONForkRetainsItsResolutionPolicyAndPublicationBoundary() {
		var root=Root.<ACell>create(Maps.of(DATA,Vectors.of(CVMLong.ONE)));
		var live=new JSONValueComponent<>(root);
		var draft=live.fork();
		draft.resolve(DATA,ZERO).cursor().set(CVMLong.TWO);
		assertEquals(CVMLong.ONE,live.resolve(DATA,ZERO).value());
		assertEquals(CVMLong.TWO,draft.resolve(DATA,ZERO).value());
		draft.sync();
		assertEquals(CVMLong.TWO,live.resolve(DATA,ZERO).value());
		var conflicting=live.fork();
		conflicting.cursor().set(CVMLong.ONE);
		live.cursor().set(CVMLong.TWO);
		assertThrows(UnsupportedOperationException.class,conflicting::sync);
	}
}
