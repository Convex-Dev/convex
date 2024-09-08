package convex.core.crypto;

public class Passwords {

	/**
	 * Dubious heuristic method of estimating entropy in a password.
	 * 
	 * Used for password strength estimation.
	 * 
	 * @param pass Proposed password
	 * @return Estimated entropy in bits
	 */
	public static int estimateEntropy(String pass) {
		int entropy=0;
		char last='\n';
		
		// We track category of character, so that we can give bonuses for switching
		int category=1;
		int lastCategory=1;
		int catsUsed=0;
		final int SWITCH=2;
		final int PER_CAT=2;
		
		for (int i=0; i<pass.length(); i++) {
			char c=pass.charAt(i);
			
			if ((c>='0')&&(c<='9')) {
				// Category 0: numeric
				entropy+=2;
				category=0;
			} else if ((c>='a')&&(c<='z')) {
				// Category 1: lowercase chars
				entropy+=2;
				category=1;
			} else if ((c>='A')&&(c<='Z')) {
				// category 2: uppercase chars
				entropy+=2;
				category=2;
			} else if (c=='-'||c=='.'||(c==',')||(c==' ')||(c==';')) {
				// category 3: common punctuation
				entropy+=3;
				category=3;
			} else {
				// category 4: other characters, rare punctuation
				entropy+=4;
				category=4;
			}
			
			// we penalise adjacent and identical characters. No "abcdef" for you....
			if ((c>=(last-1))&&(c<=(last+1))) entropy-=1;
			last=c;
			
			// set a bit for each category used
			catsUsed|=(1<<category);
			
			// bonus for switching categories
			if (category!=lastCategory) {
				entropy+=SWITCH;
			}
			lastCategory=category;
		}
		
		// 2 bits of entropy bonus per category used
		entropy +=Integer.bitCount(catsUsed*PER_CAT);
		
		return entropy;
	}
}
