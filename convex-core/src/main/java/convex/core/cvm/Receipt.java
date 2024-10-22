package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.RecordFormat;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Transaction Receipt record.
 * 
 * This forms part of the official CVM state transition, so must be correctly computed and encoded
 */
public class Receipt extends ARecord {
	
	private static final Keyword[] RECEIPT_KEYS = new Keyword[] { Keywords.RESULT, Keywords.ERROR, Keywords.LOG};
	private static final RecordFormat FORMAT = RecordFormat.of(RECEIPT_KEYS);
	private static final long RECEIPT_LENGTH=RECEIPT_KEYS.length;
	
	private static final AVector<AVector<ACell>> EMPTY_LOG = Vectors.empty();

	private final boolean isError;
	private final ACell result;
	private final AVector<AVector<ACell>> log; // Log vector, or null if empty


	protected Receipt(boolean error, ACell value, AVector<AVector<ACell>> log) {
		super(RECEIPT_LENGTH);
		this.isError=error;
		this.result=value;
		this.log=log;
	}
	
	public static Receipt create(ACell result) {
		return new Receipt(false,result,null);
	}
	
	public static Receipt create(boolean isError,ACell value, AVector<AVector<ACell>> log) {
		if ((log!=null)&&(log.isEmpty())) log=null;
		return new Receipt(isError,value,log);
	}
	
	public static Receipt createError(ACell errorCode) {
		return new Receipt(true,errorCode,null);
	}

	@Override
	public ACell get(Keyword k) {
		if (Keywords.RESULT.equals(k)) return getResult();
		if (Keywords.ERROR.equals(k)) return getErrorCode();
		if (Keywords.LOG.equals(k)) return getLog();
		return null;
	}

	private AVector<AVector<ACell>> getLog() {
		return (log==null)?EMPTY_LOG:log;
	}

	/**
	 * Gets the error code of this Receipt, or null if not an error
	 * @return Error code (probably a Keyword or nil)
	 */
	public ACell getErrorCode() {
		if (!isError) return null;
		return result;
	}

	/**
	 * Gets the result value of this Receipt, or null if an error
	 * @return Result from this Receipt
	 */
	public ACell getResult() {
		if (isError) return null;
		return result;
	}

	@Override
	public byte getTag() {
		int tag=Tag.RECEIPT+(isError?Tag.RECEIPT_ERROR_MASK:0)+((log==null)?0:Tag.RECEIPT_LOG_MASK);
		return (byte)tag;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (result!=null) {
			if (!result.isCVMValue()) throw new InvalidDataException("Receipt Result must be CVM value",this);
			result.validateCell();
		}
	}

	@Override
	public boolean equals(ACell a) {
		if (!(a instanceof Receipt)) return false;
		return equals((Receipt) a);
	}
	
	public boolean equals(Receipt a) {
		if (a==this) return true;
		if (this.isError!=a.isError) return false;
		if (!Cells.equals(result, a.result)) return false;
		if (!Cells.equals(log, a.log)) return false;
		return true;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos]=getTag();
		return encodeRaw(bs,pos+1);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.write(bs, pos, result);
		if (log!=null) pos=log.encode(bs, pos);
		return pos;
	}
	
	public static Receipt read(byte tag,Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		boolean isError=((tag&Tag.RECEIPT_ERROR_MASK)!=0);
		boolean hasLog=((tag&Tag.RECEIPT_LOG_MASK)!=0);

		ACell result = Format.read(b,epos);
		epos+=Format.getEncodingLength(result);

		AVector<AVector<ACell>> log=null;
		if (hasLog) {
			log = Vectors.read(b, epos); 
			if ((log==null)||(log.isEmpty())) throw new BadFormatException("Expected non-empty log");
			epos+=Format.getEncodingLength(log);
		}
		
		Receipt receipt=new Receipt(isError,result,log);
		receipt.attachEncoding(b.slice(pos, epos));
		return receipt;
	}
	
	@Override
	public Receipt updateRefs(IRefFunction func) {
		ACell newResult=Ref.update(result, func);
		AVector<AVector<ACell>> newLog=(AVector<AVector<ACell>>) Ref.update(log, func);
		if ((log==newLog)&&(result==newResult)) return this;
		return new Receipt(isError,newResult,newLog);
	}

	@Override
	public int getRefCount() {
		return Cells.refCount(result)+Cells.refCount(log);
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		int rr=Cells.refCount(result);
		if (i<rr) {
			if (result==null) throw new IndexOutOfBoundsException("Negative ref index");
			return result.getRef(i);
		} else {
			if (log==null) throw new IndexOutOfBoundsException("Excessive ref index");
			return log.getRef(i-rr);
		}
	}

	public boolean isError() {
		return isError;
	}


	

}
