package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import convex.core.util.Utils;

/** Test storage: ordinary stores change live bytes; only explicit persistence changes the crash image. */
final class DurableEtchFileMapper extends AFileMapper {
	enum Kind { BODY_WRITE, SLOT_WRITE, HEADER_WRITE, BODY_FORCE, HEADER_FORCE, CLOSE }
	record Event(Kind kind, long position, int length) {}
	private byte[] live;
	private byte[] durable;
	final List<Event> events=new ArrayList<>();
	private int cut=-1;
	private int prefix;
	private boolean crashed;
	boolean closed;
	boolean failClose;

	DurableEtchFileMapper(byte[] image) {
		super("durability-matrix",image.length,image.length,false);
		live=image.clone();
		durable=image.clone();
	}

	void arm(int event, int durablePrefix) { events.clear(); cut=event; prefix=durablePrefix; }
	byte[] image() { return durable.clone(); }
	byte[] liveImage() { return Arrays.copyOf(live,Math.toIntExact(length())); }

	private void persist(long position, int length) {
		int start=Math.toIntExact(position);
		if (durable.length<start+length) durable=Arrays.copyOf(durable,start+length);
		System.arraycopy(live,start,durable,start,length);
	}

	private void event(Kind kind, long position, int length, boolean force) throws IOException {
		if (crashed) throw new IOException("Storage is crashed");
		events.add(new Event(kind,position,length));
		if (events.size()-1==cut) {
			persist(position,Math.min(length,prefix));
			crashed=true;
			throw new IOException("Injected cut at "+kind+" prefix="+prefix);
		}
		if (force) persist(position,length);
	}

	@Override void ensureMapped(long position,long length) {
		int end=Math.toIntExact(position+length);
		if (live.length<end) live=Arrays.copyOf(live,end);
	}
	@Override void readMapped(long p,byte[] out,int offset,int n) { System.arraycopy(live,(int)p,out,offset,n); }
	@Override boolean matchesMapped(long p,byte[] bytes,int offset,int n) {
		return Arrays.equals(live,(int)p,(int)p+n,bytes,offset,offset+n);
	}
	@Override void readTransformedMapped(long p,byte[] out,int offset,int n,EtchFileCipher cipher) throws IOException {
		cipher.decrypt(ByteBuffer.wrap(live,(int)p,n),out,offset);
	}
	@Override void writeMapped(long p,byte[] bytes,int offset,int n) throws IOException {
		if (crashed) throw new IOException("Storage is crashed");
		System.arraycopy(bytes,offset,live,(int)p,n);
		event(p<EtchConstants.V3_HEADER_REGION_SIZE?Kind.HEADER_WRITE:Kind.BODY_WRITE,p,n,false);
	}
	@Override void writeTransformedMapped(long p,byte[] bytes,int offset,int n,EtchFileCipher cipher) throws IOException {
		if (crashed) throw new IOException("Storage is crashed");
		cipher.encrypt(bytes,offset,ByteBuffer.wrap(live,(int)p,n));
		event(Kind.BODY_WRITE,p,n,false);
	}
	@Override long readLongAcquireMapped(long p) { return Utils.readLong(live,(int)p,8); }
	@Override void writeLongReleaseMapped(long p,long value) throws IOException {
		if (crashed) throw new IOException("Storage is crashed");
		Utils.writeLong(live,(int)p,value);
		event(Kind.SLOT_WRITE,p,8,false);
	}
	@Override void force() throws IOException { event(Kind.BODY_FORCE,0,Math.toIntExact(length()),true); }
	@Override void forceRangeMapped(long p,long n) throws IOException { event(Kind.HEADER_FORCE,p,Math.toIntExact(n),true); }
	@Override String implementationName() { return "live-durable-test"; }
	@Override public void close() throws IOException {
		closed=true;
		if (failClose) throw new IOException("Injected mapper cleanup failure");
	}
}
