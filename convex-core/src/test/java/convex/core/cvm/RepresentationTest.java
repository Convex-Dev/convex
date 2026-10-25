package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.ACVMTest;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.core.store.NullStore;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import convex.test.Samples;

/** Typed access must not depend on which Java representation a cache retained. */
public class RepresentationTest extends ACVMTest {

	private static ACell generic(ACell value) throws Exception {
		return CAD3Encoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(value,true));
	}

	@Test public void testAddressViews() throws Exception {
		ACell raw=generic(HERO);
		assertInstanceOf(ExtensionValue.class,raw);
		assertEquals(HERO,RT.ensureAddress(raw));
		assertEquals(HERO,RT.castAddress(raw));
		assertTrue(Types.ADDRESS.check(raw));
		assertEquals(Types.ADDRESS,RT.getType(raw));
		assertEquals(convex.core.data.prim.CVMBool.TRUE,
			CONTEXT.fork().invoke(Core.ADDRESS_Q,raw).getResult());
		assertNull(RT.ensureAddress(CVMLong.ONE));
		assertNull(RT.ensureAddress(Blob.fromHex("0000000000000001")));
		assertNull(RT.ensureAddress(ExtensionValue.create((byte)0xe0,1)));
		assertSame(HERO,RT.ensureAddress(HERO));
		assertEquals(RT.castLong(HERO),RT.castLong(raw));
		assertEquals(RT.str(HERO),RT.str(raw));
		assertEquals(RT.print(HERO),RT.print(raw));
		assertEquals(RT.print(Vectors.of(HERO)),RT.print(Vectors.of(raw)));
		for (AFn<?> fn:new AFn<?>[] {Core.ADDRESS_Q,Core.ADDRESS,Core.LONG,Core.STR,Core.PRINT}) {
			assertExecutionEquals(CONTEXT.fork().invoke(fn,HERO),CONTEXT.fork().invoke(fn,raw));
		}
	}

	@Test public void testOnlyCVMMaplikeRecordsAreMaps() throws Exception {
		ACell op=convex.core.cvm.ops.Do.create(convex.core.cvm.ops.Constant.of(1));
		ACell fn=convex.core.lang.impl.Fn.create(Vectors.empty(),convex.core.cvm.ops.Constant.nil());
		ACell account=INITIAL.getAccount(HERO);
		ACell unknown=DenseRecord.create(CVMTag.ACCOUNT_STATUS,Vectors.empty());
		for (ACell value:new ACell[] {op,fn,account,unknown,Maps.empty(),Index.none()}) {
			ACell raw=generic(value);
			boolean maplike=(value==account)||(value instanceof AHashMap)||(value instanceof Index);
			assertEquals(maplike,RT.ensureMap(raw)!=null);
			assertEquals(maplike,Types.MAP.check(raw));
			assertEquals(maplike,Types.MAP.implicitCast(raw)!=null);
			assertEquals(maplike,RT.ensureDataStructure(raw)!=null);
			assertEquals(maplike,RT.ensureCountable(raw)!=null);
			for (AFn<?> predicate:new AFn<?>[] {Core.MAP_Q,Core.COLL_Q,Core.COUNTABLE_Q,Core.EMPTY_Q,
				Core.FN_Q,Core.COUNT,Core.KEYS,Core.VALUES,Core.STR,Core.PRINT}) {
				assertExecutionEquals(CONTEXT.fork().invoke(predicate,value),CONTEXT.fork().invoke(predicate,raw));
			}
			assertExecutionEquals(CONTEXT.fork().invoke(Core.GET,value,Keywords.BALANCE),
				CONTEXT.fork().invoke(Core.GET,raw,Keywords.BALANCE));
		}
		assertNull(RT.castMap(unknown));
		assertNull(RT.castFunction(unknown));
		assertNull(RT.sequence(unknown));
		assertNull(RT.castVector(unknown));
		assertNull(RT.castSet(unknown));
		assertEquals(INITIAL.getAccount(HERO).getBalance(),RT.<CVMLong>getIn(generic(account),Keywords.BALANCE).longValue());
	}

	@ParameterizedTest
	@ValueSource(strings={"42", "(+ 1 2)", "(do 1 2 3)", "(cond true 10 20)",
		"(let [x 3] (+ x 4))", "(let [x 1] (set! x 7) x)",
		"((fn [x] (+ x 1)) 5)", "((fn ([] 1) ([x] x)) 7)",
		"(try (/ 1 0) 42)", "(query (+ 2 3))", "(do (def representation-test 6) representation-test)"})
	public void testGenericCompiledCode(String source) throws Exception {
		Context compiled=CONTEXT.fork().compile(Reader.read(source));
		assertFalse(compiled.isExceptional(),source);
		AOp<?> original=Ops.ensureOp(compiled.getResult());
		ACell raw=generic(original);
		AOp<?> converted=Ops.ensureOp(raw);
		assertNotNull(converted,source);
		assertEquals(original.getHash(),converted.getHash());
		assertEquals(original.getEncoding(),converted.getEncoding());
		assertTrue(Types.OP.check(raw));
		Context expected=CONTEXT.fork().exec(original);
		Context actual=CONTEXT.fork().exec(converted);
		assertExecutionEquals(expected,actual);
		assertCacheIndependent(original);
	}

	private static void assertExecutionEquals(Context expected,Context actual) {
		assertEquals(expected.isExceptional(),actual.isExceptional());
		assertEquals(expected.getErrorCode(),actual.getErrorCode());
		if (expected.isError()) assertEquals(expected.getError().getMessage(),actual.getError().getMessage());
		if (!expected.isExceptional()) assertEquals(expected.getResult(),actual.getResult());
		assertEquals(expected.getState(),actual.getState());
		assertEquals(expected.getLog(),actual.getLog());
		assertEquals(expected.getJuiceUsed(),actual.getJuiceUsed());
		assertEquals(expected.getDepth(),actual.getDepth());
		assertEquals(expected.getLocalBindings(),actual.getLocalBindings());
	}

	private void assertCacheIndependent(ACell value) throws Exception {
		Blob encoding=Format.encodeMultiCell(value,true);
		ACell decoded=CVMEncoder.INSTANCE.decodeMultiCell(encoding);
		ACell raw=CAD3Encoder.INSTANCE.decodeMultiCell(encoding);
		Context expected=CONTEXT.fork().eval(decoded);
		assertExecutionEquals(expected,CONTEXT.fork().eval(raw));
		// Exercise both orders of cache population, including generic child refs.
		for (ACell first:new ACell[] {raw,decoded}) {
			try (MemoryStore store=new MemoryStore()) {
				Cells.persist(first,store);
				assertExecutionEquals(expected,CONTEXT.fork().eval(store.decode(value.getEncoding())));
				assertExecutionEquals(expected,CONTEXT.fork().eval(store.refForHash(value.getHash()).getValue()));
			}
		}
	}

	@Test public void testGenericTransactionAndSignedValue() throws Exception {
		Invoke tx=Invoke.create(HERO,1,Reader.read("(+ 1 2)"));
		SignedData<ACell> signed=Samples.KEY_PAIR.signData(generic(tx));
		ATransaction converted=RT.ensureTransaction(signed.getValue());
		assertInstanceOf(Invoke.class,converted);
		assertEquals(HERO,converted.getOrigin());
		assertEquals(tx.getEncoding(),converted.getEncoding());
		assertTrue(Types.TRANSACTION.check(signed.getValue()));
		assertTrue(signed.checkSignature());
		assertEquals(CVMLong.create(3),converted.apply(CONTEXT.fork()).getResult());
	}

	@Test public void testShallowConversionAndUnknownValues() throws Exception {
		CodedValue constant=CodedValue.create(CVMTag.OP_CODED,
			new convex.core.data.prim.ByteFlag(CVMTag.OPCODE_CONSTANT),CVMLong.ONE);
		ACell converted=CVMEncoder.specialise(constant);
		assertInstanceOf(AOp.class,converted);
		assertSame(constant.getRef(1),converted.getRef(1));
		assertSame(converted,CVMEncoder.specialise(converted));
		for (ACell unknown:new ACell[] {ExtensionValue.create((byte)0xe0,42),
			DenseRecord.create(CVMTag.INVOKE,Vectors.empty()),
			CodedValue.create(0xcf,null,null)}) {
			assertSame(unknown,CVMEncoder.specialise(unknown));
		}
		// Shallow conversion does not fetch an unresolved constant payload.
		CodedValue lazy=CodedValue.createFromRefs(CVMTag.OP_CODED,constant.getRef(0),
			RefSoft.createForHash(Samples.BAD_HASH,NullStore.INSTANCE));
		ACell specialised=CVMEncoder.specialise(lazy);
		assertInstanceOf(AOp.class,specialised);
		assertSame(lazy.getRef(1),specialised.getRef(1));
		assertThrows(MissingDataException.class,()->specialised.getRef(1).getValue());
	}

	@Test public void testCachedBranchAndDefaultDecoder() throws Exception {
		try (EtchStore store=EtchStore.createTemp(EtchConfig.create().withRefCacheSize(1));
			MemoryStore memory=new MemoryStore()) {
			ACell raw=generic(Address.MAX_VALUE);
			AVector<ACell> branch=Vectors.empty();
			for (int i=0;i<16;i++) branch=branch.conj(raw);
			assertFalse(branch.isEmbedded());
			Cells.persist(branch,store);
			AVector<?> parent=store.decode(Vectors.of(branch).getEncoding());
			ACell nested=((AVector<?>)parent.get(0)).get(0);
			assertInstanceOf(ExtensionValue.class,nested);
			assertEquals(Address.MAX_VALUE,RT.ensureAddress(nested));

			// Large generic CVM records hit the cache, but decode still returns a CVM view.
			AOp<?>[] ops=new AOp<?>[16];
			java.util.Arrays.fill(ops,convex.core.cvm.ops.Constant.of(CVMLong.MAX_VALUE));
			AOp<?> bigOp=convex.core.cvm.ops.Do.create(ops);
			ACell genericOp=generic(bigOp);
			assertFalse(genericOp.isEmbedded());
			for (var target:new convex.core.store.AStore[] {store,memory}) {
				Cells.persist(genericOp,target);
				ACell decoded=target.decode(bigOp.getEncoding());
				assertInstanceOf(convex.core.cvm.ops.Do.class,decoded);
				assertEquals(bigOp,decoded);
				assertExecutionEquals(CONTEXT.fork().eval(bigOp),CONTEXT.fork().eval(decoded));
				ACell genericState=generic(INITIAL);
				Cells.persist(genericState,target);
				State state=target.decode(INITIAL.getEncoding());
				assertEquals(INITIAL,state);
				assertEquals(INITIAL.getAccount(HERO),state.getAccount(HERO));
			}
		}
	}

	@Test public void testEmbeddedDecodeAvoidsCacheAdmission() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			Keyword keyword=Keyword.create("representation-test-embedded");
			assertNull(store.checkCache(keyword.getHash()));
			assertEquals(keyword,store.decode(keyword.getEncoding()));
			assertNull(store.checkCache(keyword.getHash()));
			assertEquals(0,store.getCacheStats().l1Hits);
			assertEquals(1,store.getCacheStats().decodes);
			Cells.persist(generic(HERO),store);
			assertInstanceOf(Address.class,store.decode(HERO.getEncoding()));
		}
	}

	@Test public void testEmbeddedDecodeNeverHashesForLookup() throws Exception {
		try (EtchStore etch=EtchStore.createTemp(); MemoryStore memory=new MemoryStore()) {
			AVector<ACell> value=Vectors.of(1,2,3);
			for (var store:new convex.core.store.AStore[] {etch,memory}) {
				AVector<ACell> stored=Cells.persist(value,store);
				Blob fresh=Blob.wrap(value.getEncoding().getBytes());
				assertNull(fresh.cachedContentHash());
				assertEquals(value,store.decode(fresh));
				assertNull(fresh.cachedContentHash(),"Embedded decode must not compute a lookup hash");
				assertSame(stored,store.decode(stored.getEncoding()),"Reuse a cached root when its hash is known");
				ACell raw=generic(Address.create(456789));
				Cells.persist(raw,store);
				assertNotNull(raw.getEncoding().cachedContentHash());
				assertInstanceOf(Address.class,store.decode(raw.getEncoding()));
			}
			assertEquals(2,etch.getCacheStats().l1Hits);
			assertEquals(1,etch.getCacheStats().decodes);
		}
	}

	@Test public void testMalformedScalarFieldDoesNotFetchBranch() throws Exception {
		Blob branch=Blob.wrap(new byte[200]);
		ACell record=DenseRecord.create(CVMTag.PEER_STATUS,Vectors.of(
			HERO,branch,Index.none(),CVMLong.ZERO,Maps.empty(),CVMLong.ZERO,CVMLong.ZERO));
		assertSame(record,CVMEncoder.specialise(record));
		assertEquals(record,CVMEncoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(record,true)));
	}

	@Test public void testMalformedOpsFailDeterministicallyAtExecution() throws Exception {
		for (ACell value:new ACell[] {
			CodedValue.create(CVMTag.OP_LOOP,convex.core.data.prim.CVMBool.FALSE,null),
			CodedValue.create(CVMTag.OP_LOOKUP,null,CodedValue.create(0xcf,null,null)),
			CodedValue.create(CVMTag.OP_LOOKUP,CVMLong.ONE,Symbols.FOO),
			CodedValue.create(CVMTag.OP_DEF,CVMLong.ONE,null),
			CodedValue.create(CVMTag.OP_DEF,Symbols.FOO,CVMLong.ONE),
			CodedValue.create(CVMTag.OP_LET,Vectors.of(Symbols.FOO),Vectors.empty()),
			CodedValue.create(CVMTag.OP_LET,Vectors.of(Symbols.FOO),Vectors.of(CVMLong.ONE)),
			CodedValue.create(CVMTag.OP_CODED,new convex.core.data.prim.ByteFlag(CVMTag.OPCODE_LAMBDA),null),
			CodedValue.create(CVMTag.OP_CODED,new convex.core.data.prim.ByteFlag(CVMTag.OPCODE_TRY),CVMLong.ONE),
			CodedValue.create(CVMTag.OP_CODED,new convex.core.data.prim.ByteFlag(CVMTag.OPCODE_QUERY),CVMLong.ONE)}) {
			Context compiled=CONTEXT.fork().compile(value);
			assertFalse(compiled.isExceptional());
			assertEquals(convex.core.ErrorCodes.FORMAT,CONTEXT.fork().exec(Ops.ensureOp(compiled.getResult())).getErrorCode());
			assertCacheIndependent(value);
		}
	}

	@Test public void testLiteralOperandsKeepExistingSemantics() throws Exception {
		for (byte tag:new byte[] {CVMTag.OP_INVOKE,CVMTag.OP_DO,CVMTag.OP_COND}) {
			ACell value=DenseRecord.create(tag,Vectors.of(CVMLong.ONE));
			assertCacheIndependent(value);
		}
	}

	@Test public void testBranchPayloadsAndLazyUnselectedCode() throws Exception {
		Blob badPayload=Blob.wrap(new byte[200]);
		for (byte opcode:new byte[] {CVMTag.OPCODE_TRY,CVMTag.OPCODE_QUERY,CVMTag.OPCODE_LAMBDA}) {
			assertCacheIndependent(CodedValue.create(CVMTag.OP_CODED,new convex.core.data.prim.ByteFlag(opcode),badPayload));
		}
		// Neither shallow conversion nor execution of the selected branch may fetch this.
		CodedValue missing=CodedValue.createFromRefs(CVMTag.OP_CODED,
			new convex.core.data.prim.ByteFlag(CVMTag.OPCODE_CONSTANT).getRef(),
			RefSoft.createForHash(Samples.BAD_HASH,NullStore.INSTANCE));
		ACell cond=DenseRecord.create(CVMTag.OP_COND,Vectors.of(
			convex.core.cvm.ops.Constant.of(true),convex.core.cvm.ops.Constant.of(42),missing));
		Context compiled=CONTEXT.fork().compile(cond);
		assertFalse(compiled.isExceptional());
		assertEquals(CVMLong.create(42),CONTEXT.fork().exec(Ops.ensureOp(compiled.getResult())).getResult());
	}

	@Test public void testGenericStateExecution() throws Exception {
		State state=RT.ensureCVM(generic(INITIAL),State.class);
		Context actual=Context.create(state,HERO).eval(Reader.read("(+ 1 2)"));
		assertFalse(actual.isExceptional());
		assertEquals(CVMLong.create(3),actual.getResult());
		assertEquals(INITIAL.getPeer(Samples.KEY_PAIR.getAccountKey()),state.getPeer(Samples.KEY_PAIR.getAccountKey()));
	}

	@Test public void testExistingStateEncodingsAreUnchanged() throws Exception {
		for (State original:new State[] {convex.core.init.InitTest.LIVE,INITIAL,
			GenesisStateTest.getGenesisState(),SnapshotStateTest.getConsensusState()}) {
			Blob encoding=Format.encodeMultiCell(original,true);
			ACell raw=CAD3Encoder.INSTANCE.decodeMultiCell(encoding);
			State interpreted=RT.ensureCVM(raw,State.class);
			assertNotNull(interpreted);
			assertEquals(original.getHash(),interpreted.getHash());
			assertEquals(original.getProtocolVersion(),interpreted.getProtocolVersion());
			// Check every reachable branch, not just a cached encoding of the root.
			assertEquals(encoding,Format.encodeMultiCell(interpreted,true));
			// Exercise compiled state-resident code and an update through generic refs.
			for (String source:new String[] {"(map inc [1 2 3])",
				"(do (def representation-state-check 42) representation-state-check)"}) {
				ACell form=Reader.read(source);
				Context expected=Context.create(original,HERO).eval(form);
				assertFalse(expected.isExceptional(),source);
				Context actual=Context.create(interpreted,HERO).eval(form);
				assertExecutionEquals(expected,actual);
				assertEquals(expected.getState().getHash(),actual.getState().getHash());
			}
			assertEquals(encoding,Format.encodeMultiCell(interpreted,true));
		}
	}

	@Test public void testCoreDefinitionViews() throws Exception {
		ACell raw=generic(Core.COUNT);
		assertSame(Core.COUNT,CVMEncoder.specialise(raw));
		for (AFn<?> fn:new AFn<?>[] {Core.FN_Q,Core.MAP_Q,Core.COUNTABLE_Q,Core.COUNT,Core.STR,Core.PRINT,Core.BLOB,Core.CAT}) {
			assertExecutionEquals(CONTEXT.fork().invoke(fn,Core.COUNT),CONTEXT.fork().invoke(fn,raw));
		}
	}

	@Test public void testGenericStateAccountingAndTransfers() throws Exception {
		State raw=RT.ensureCVM(generic(INITIAL),State.class);
		assertEquals(INITIAL.computeTotalBalance(),raw.computeTotalBalance());
		assertEquals(INITIAL.computeTotalMemory(),raw.computeTotalMemory());
		assertEquals(INITIAL.computeSupply(),raw.computeSupply());
		Context expected=Context.create(INITIAL,HERO);
		Context actual=Context.create(raw,HERO);
		assertExecutionEquals(expected.fork().transfer(VILLAIN,1),actual.fork().transfer(VILLAIN,1));
		assertExecutionEquals(expected.fork().transferMemoryAllowance(VILLAIN,CVMLong.ONE),
			actual.fork().transferMemoryAllowance(VILLAIN,CVMLong.ONE));
		long memory=INITIAL.getAccount(HERO).getMemory();
		assertExecutionEquals(expected.fork().setMemory(memory+1),actual.fork().setMemory(memory+1));
	}

	@Test public void testGenericDelegatorAddressOnEviction() throws Exception {
		AccountKey key=Samples.KEY_PAIR.getAccountKey();
		State original=INITIAL.withPeer(key,PeerStatus.create(HERO,100).withDelegatedStake(VILLAIN,10));
		State raw=RT.ensureCVM(generic(original),State.class);
		Context expected=Context.create(original,HERO).evictPeer(key);
		assertFalse(expected.isExceptional());
		assertExecutionEquals(expected,Context.create(raw,HERO).evictPeer(key));
	}
}
