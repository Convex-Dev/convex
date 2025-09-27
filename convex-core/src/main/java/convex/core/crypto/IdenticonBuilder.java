package convex.core.crypto;

import convex.core.data.AArrayBlob;

public class IdenticonBuilder {

	
	public static final int SIZE=7;
	
	/**
	 *  Create a colour table of 4 colours for identicon
	 * @param data Data to build Identicon for (typically a Hash or AccountKey)
	 * @return colour table array in 0xffRRGGBB format
	 */
	public static int[] createColours(AArrayBlob data) {
		int[] cols=new int[4];
		
		// last 12 bytes define colours, 3 bytes per colour, we mask the first byte for ARGB
		long n=data.count(); // must be one byte at least
		for (int i=0; i<4; i++) {
			int r=(int) (0xff&(data.byteAt(Math.floorMod(n-12+i*3+0,n))));
			int g=(int) (0xff&(data.byteAt(Math.floorMod(n-12+i*3+1,n))));
			int b=(int) (0xff&(data.byteAt(Math.floorMod(n-12+i*3+2,n))));
			
			// XOR with red high bit, tends to make invalid / non-random data look suspicious
			int col=0x800000^((r<<16)|(g<<8)|b);
			
			// set pixel with full opacity (alpha=0xff)
			cols[i]=0xFF000000|col;
		}
		return cols;
	}

	/**
	 * Build an Identicon from the given source data
	 * @param data Data to build Identicon for (typically a Hash or AccountKey)
	 * @return Identicon pixel data
	 */
	public static int[] build(AArrayBlob data) {
		int[] result=new int[SIZE*SIZE];
		
		int[] cols=createColours(data);
		
		// number of differentiated pixels for each row (4)
		int width=((SIZE+1)/2);

		// First 20 bytes define bitmap. Take 2 bits for each 
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x <= width; x++) {
				int i = (x + y * width); // 4 2-byte segments per byte
				
				int byteIndex=i/4;
				if (byteIndex>=data.count()) break;
				
				byte b=data.byteAt(byteIndex);
				int bits = 0x03&(b>>(2*(3-(i%4)))); // take 2 bits for colour index, high bits first
				int rgb = cols[bits];
				result[x+ y*SIZE]= rgb;
				result[(SIZE-x-1)+ y*SIZE]= rgb;
			}
		}
		return result;
	}

}
