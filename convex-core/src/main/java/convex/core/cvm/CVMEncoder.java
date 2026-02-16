package convex.core.cvm;

import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.Order;
import convex.core.cvm.ops.Cond;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Def;
import convex.core.cvm.ops.Do;
import convex.core.cvm.ops.Lambda;
import convex.core.cvm.ops.Let;
import convex.core.cvm.ops.Local;
import convex.core.cvm.ops.Lookup;
import convex.core.cvm.ops.Query;
import convex.core.cvm.ops.Set;
import convex.core.cvm.ops.Special;
import convex.core.cvm.ops.Try;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Multi;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.CAD3Encoder;
import convex.core.data.CodedValue;
import convex.core.data.DenseRecord;
import convex.core.data.ExtensionValue;
import convex.core.data.Ref;
import convex.core.data.prim.AByteFlag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.Core;
import convex.core.lang.impl.Fn;
import convex.core.lang.impl.MultiFn;
import convex.core.store.AStore;
import convex.core.store.NullStore;
import convex.core.util.ErrorMessages;

/**
 * Encoder for CVM values and data structures.
 *
 * Extends CAD3Encoder with CVM-specific types: transactions, ops, consensus
 * records, and other CVM value types. Format.read delegates to INSTANCE
 * for all tag-based dispatch, making this the default decoder.
 */
public class CVMEncoder extends CAD3Encoder {

	/**
	 * Default storeless encoder instance used by Format.read for all decode operations.
	 */
	public static final CVMEncoder INSTANCE = new CVMEncoder();

	public CVMEncoder() {}

	/**
	 * Creates a store-bound encoder. The encoder will manage the thread-local
	 * store context during decode operations.
	 * @param store Store to associate with this encoder
	 */
	public CVMEncoder(AStore store) {
		super(store);
	}

	@Override
	protected CVMEncoder withStore(AStore store) {
		return new CVMEncoder(store);
	}

	private static final CVMEncoder NULL_STORE_CVM_ENCODER = new CVMEncoder(NullStore.INSTANCE);

	@Override
	protected CAD3Encoder nullStoreEncoder() {
		return NULL_STORE_CVM_ENCODER;
	}

	@Override
	protected ACell readExtension(byte tag, DecodeState ds) throws BadFormatException {
		long code = readVLQCount(ds);
		if (tag == CVMTag.CORE_DEF) {
			ACell cc = Core.fromCode(code);
			if (cc != null) return cc;
		}
		if ((tag == CVMTag.OP_SPECIAL) && (code < Special.NUM_SPECIALS)) {
			Special<?> spec = Special.create((int) code);
			if (spec != null) return spec;
		}
		if (tag == CVMTag.OP_LOCAL) return Local.create(code);
		if (tag == CVMTag.ADDRESS) return Address.create(code);
		return ExtensionValue.create(tag, code);
	}

	@Override
	protected ACell readCodedData(byte tag, DecodeState ds) throws BadFormatException {
		Ref<ACell> r1 = readRef(ds);
		Ref<ACell> r2 = readRef(ds);

		try {
			if (tag == CVMTag.OP_CODED) {
				ACell code = r1.getValue();
				if (code instanceof AByteFlag) {
					byte opCode = ((AByteFlag) code).getTag();
					switch (opCode) {
						case CVMTag.OPCODE_CONSTANT: return Constant.createFromRef(r2);
						case CVMTag.OPCODE_TRY: return Try.createFromRefs(r1, r2);
						case CVMTag.OPCODE_LAMBDA: return Lambda.createFromRef(r2);
						case CVMTag.OPCODE_QUERY: return Query.createFromRefs(r1, r2);
					}
				}
				// CVMLong codes (0x10-0x18) encode Set op position
				return Set.createFromRefs(r1, r2);
			}
			if (tag == CVMTag.OP_LOOKUP) return Lookup.createFromRefs(r1, r2);
			if (tag == CVMTag.OP_DEF) return Def.createFromRefs(r1, r2);
			if (tag == CVMTag.OP_LET) return Let.createFromRefs(r1, r2, false);
			if (tag == CVMTag.OP_LOOP) return Let.createFromRefs(r1, r2, true);
		} catch (MissingDataException e) {
			throw e; // fail-fast: don't degrade type on unresolvable refs
		} catch (Exception e) {
			// Catch all: tag may be shared with a generic CAD3 coded value
		}

		// Unknown or failed CVM coded tag — generic CodedValue
		return CodedValue.create(tag & 0xFF, r1.getValue(), r2.getValue());
	}

	@Override
	protected ACell readDenseRecord(byte tag, DecodeState ds) throws BadFormatException {
		AVector<ACell> data = readVector(ds);

		try {
			if (tag == CVMTag.INVOKE) return Invoke.create(data);
			if (tag == CVMTag.TRANSFER) return Transfer.create(data);
			if (tag == CVMTag.CALL) return Call.create(data);
			if (tag == CVMTag.MULTI) return Multi.create(data);
			if (tag == CVMTag.FN) return readFn(data);
			if (tag == CVMTag.STATE) return new State(data);
			if (tag == CVMTag.BELIEF) return Belief.create(data);
			if (tag == CVMTag.BLOCK) return Block.create(data);
			if (tag == CVMTag.RESULT) return Result.buildFromVector(data);
			if (tag == CVMTag.ORDER) return Order.create(data);
			if (tag == CVMTag.BLOCK_RESULT) return new BlockResult(data);
			if (tag == CVMTag.OP_DO) return Do.create(data);
			if (tag == CVMTag.OP_COND) return Cond.create(data);
			if (tag == CVMTag.OP_INVOKE) return convex.core.cvm.ops.Invoke.fromData(data);
			if (tag == CVMTag.PEER_STATUS) return new PeerStatus(data);
			if (tag == CVMTag.ACCOUNT_STATUS) return AccountStatus.create(data);
		} catch (MissingDataException e) {
			throw e; // fail-fast: don't degrade type on unresolvable refs
		} catch (Exception e) {
			// Catch all: tag may be shared with a generic CAD3 dense record
		}

		// Unknown or failed CVM tag — generic DenseRecord
		DenseRecord dr = DenseRecord.create(tag & 0xFF, data);
		if (dr == null) throw new BadFormatException(ErrorMessages.badTagMessage(tag));
		return dr;
	}

	/**
	 * Reads a function (Fn or MultiFn) from decoded vector data.
	 * Dispatches based on first element: if embedded ByteFlag FN_NORMAL → Fn,
	 * otherwise MultiFn. Uses isEmbedded() to avoid dereferencing non-embedded
	 * refs (which may not be resolvable during multi-cell child decode).
	 */
	private ACell readFn(AVector<ACell> data) throws BadFormatException {
		if (data.isEmpty()) throw new BadFormatException("Empty record in Fn");
		Ref<ACell> firstRef = data.getRef(0);
		if (firstRef.isEmbedded()) {
			ACell typeFlag = firstRef.getValue();
			if (typeFlag instanceof AByteFlag && ((AByteFlag) typeFlag).getTag() == CVMTag.FN_NORMAL) {
				return Fn.create(data);
			}
		}
		return MultiFn.create(data);
	}

}
