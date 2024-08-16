package convex.core.crypto;

public class Passwords {

	/**
	 * Dubious method of estimating entropy in a password.
	 * 
	 * Used for password strength estimation.
	 * 
	 * @param pass
	 * @return
	 */
	public static int estimateEntropy(String pass) {
		int entropy=0;
		char last='\n';
		
		// We track category of character, bonus for switching
		int category=1;
		
		for (int i=0; i<pass.length(); i++) {
			char c=pass.charAt(i);
			
			if ((c>='0')&&(c<='9')) {
				// Category 0: numeric
				entropy+=1;
				if (category!=0) {entropy+=2; category=0;}
			} else if ((c>='a')&&(c<='z')) {
				// Category 1: lowercase chars
				entropy+=2;
				if (category!=1) {entropy+=2; category=1;}
			} else if ((c>='A')&&(c<='Z')) {
				// category 2: uppercase chars
				entropy+=2;
				if (category!=2) {entropy+=2; category=2;}
			} else if (c=='-'||c=='.'||(c==',')||(c==' ')) {
				// category 3: common punctuation
				entropy+=3;
				if (category!=3) {entropy+=2; category=3;}
			} else {
				// category 4: other characters, rare punctuation
				entropy+=4;
				if (category!=4) {entropy+=5; category=4;}
			}
			
			// we penalise adjacent and identical characters. No "abcdef" for you....
			if (c>=(last-1)&&(c<=last+1)) entropy-=1;
			
			last=c;
		}
		return entropy;
	}
}
