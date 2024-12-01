package convex.core.data;

import java.util.Set;

import convex.core.cvm.RecordFormat;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;

public class DenseRecord extends ACAD3Record {

	protected final AVector<ACell> data;
	
	protected DenseRecord(byte tag, AVector<ACell> data) {
		super(tag,data.count());
		this.data=data;
	}
	
	@SuppressWarnings("unchecked")
	public static DenseRecord create(int tag,AVector<?> data) {
		if (data==null) return null;
		if (Tag.category(tag)!=Tag.DENSE_RECORD_BASE) return null; // not an extension value
		
		return new DenseRecord((byte)tag,(AVector<ACell>) data);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return data.estimatedEncodingSize();
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}
	
	public static DenseRecord read(byte tag, Blob b, int pos) throws BadFormatException {
		AVector<ACell> data=Vectors.read(b, pos);
		
		Blob enc=data.cachedEncoding();
		data.attachEncoding(null); // clear invalid encoding
		
		DenseRecord dr=create(tag,data);
		if ((enc!=null)&&(enc.byteAt(0)==tag)) {
			dr.attachEncoding(enc);
		}
		return dr;
	}

	@Override
	public AType getType() {
		return Types.CAD3;
	}

	@Override
	public int getRefCount() {
		return data.getRefCount();
	}

	@Override
	public Ref<ACell> getRef(int i) {
		return data.getRef(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		AVector<ACell> newData=data.updateRefs(func);
		if (newData==data) return this;
		DenseRecord dr= new DenseRecord(tag,newData);
		dr.attachEncoding(getEncoding());
		return dr;
	}

	@Override
	public AVector<ACell> values() {
		return data;
	}
	
	@Override
	public ACell get(Keyword key) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RecordFormat getFormat() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public MapEntry<CVMLong, ACell> entryAt(long i) {
		if ((i<0)||(i>=count)) return null;
		return MapEntry.create(CVMLong.create(i), data.get(i));
	}

	@Override
	public AVector<CVMLong> getKeys() {
		// TODO Auto-generated method stub
		return Vectors.range(0,count);
	}

	@Override
	public Set<CVMLong> keySet() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ACell get(ACell key) {
		// TODO Auto-generated method stub
		return null;
	}


}
