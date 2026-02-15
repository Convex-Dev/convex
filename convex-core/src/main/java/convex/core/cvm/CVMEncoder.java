package convex.core.cvm;

import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.Order;
import convex.core.cvm.ops.Cond;
import convex.core.cvm.ops.Def;
import convex.core.cvm.ops.Do;
import convex.core.cvm.ops.Let;
import convex.core.cvm.ops.Local;
import convex.core.cvm.ops.Lookup;
import convex.core.cvm.ops.Special;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Multi;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.CAD3Encoder;
import convex.core.data.CodedValue;
import convex.core.data.DenseRecord;
import convex.core.data.ExtensionValue;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Core;
import convex.core.lang.impl.Fn;
import convex.core.store.AStore;
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
	protected ACell readExtension(byte tag, Blob blob, int offset) throws BadFormatException {
		long code=Format.readVLQCount(blob,offset+1);
		if (tag == CVMTag.CORE_DEF) {
			ACell cc=Core.fromCode(code);
			if (cc!=null) return cc;
		}

		if ((tag == CVMTag.OP_SPECIAL)&&(code<Special.NUM_SPECIALS)) {
			Special<?> spec= Special.create((int)code);
			if (spec!=null) return spec;
		}

		if (tag == CVMTag.OP_LOCAL) {
			return Local.create(code);
		}

		if (tag == CVMTag.ADDRESS) return Address.create(code);

		return ExtensionValue.create(tag, code);
	}

	@Override
	protected ACell readCodedData(byte tag, Blob b, int pos) throws BadFormatException {
		try {
			if (tag == CVMTag.OP_CODED) return Ops.readCodedOp(tag,b, pos);
			if (tag == CVMTag.OP_LOOKUP) return Lookup.read(b,pos);
			if (tag == CVMTag.OP_DEF) return Def.read(b, pos);
			if (tag == CVMTag.OP_LET) return Let.read(b,pos,false);
			if (tag == CVMTag.OP_LOOP) return Let.read(b,pos,true);
		} catch (Exception e) {
			// Catch all: tag may be shared with a generic CAD3 coded value
		}
		return CodedValue.read(tag,b,pos);
	}

	@Override
	protected ACell readDenseRecord(byte tag, Blob b, int pos) throws BadFormatException {
		try {
			if (tag == CVMTag.INVOKE) return Invoke.read(b,pos);
			if (tag == CVMTag.TRANSFER) return Transfer.read(b,pos);
			if (tag == CVMTag.CALL) return Call.read(b,pos);
			if (tag == CVMTag.MULTI) return Multi.read(b,pos);
			if (tag == CVMTag.FN) return Fn.read(b,pos);
			if (tag == CVMTag.STATE) return State.read(b,pos);
			if (tag == CVMTag.BELIEF) return Belief.read(b,pos);
			if (tag == CVMTag.BLOCK) return Block.read(b,pos);
			if (tag == CVMTag.RESULT) return Result.read(b,pos);
			if (tag == CVMTag.ORDER) return Order.read(b,pos);
			if (tag == CVMTag.BLOCK_RESULT) return BlockResult.read(b,pos);
			if (tag == CVMTag.OP_DO) return Do.read(b,pos);
			if (tag == CVMTag.OP_COND) return Cond.read(b,pos);
			if (tag == CVMTag.OP_INVOKE) return convex.core.cvm.ops.Invoke.read(b,pos);
			if (tag == CVMTag.PEER_STATUS) return PeerStatus.read(b,pos);
			if (tag == CVMTag.ACCOUNT_STATUS) return AccountStatus.read(b,pos);
		} catch (Exception e) {
			// Catch all: tag may be shared with a generic CAD3 dense record
		}

		DenseRecord dr=DenseRecord.read(tag,b,pos);
		if (dr==null) throw new BadFormatException(ErrorMessages.badTagMessage(tag));
		return dr;
	}
}
