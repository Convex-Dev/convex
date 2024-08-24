package convex.gui.utils;

import java.awt.Color;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import convex.core.lang.reader.AntlrReader;

/**
 * Tools for highlighting Convex Lisp code in Swing
 */
public class CVXHighlighter {
	static StyleContext sc = StyleContext.getDefaultStyleContext();
	static AttributeSet BASE = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Italic, false);
	static AttributeSet ITALIC = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Italic, true);
	
	static AttributeSet NORMAL = sc.addAttribute(BASE, StyleConstants.Foreground, Color.LIGHT_GRAY);
	static AttributeSet STRING = sc.addAttribute(ITALIC, StyleConstants.Foreground, Color.CYAN);
	static AttributeSet KEYWORD = sc.addAttribute(BASE, StyleConstants.Foreground, Color.PINK);
	static AttributeSet SYMBOL = NORMAL;
	static AttributeSet NUMBER = sc.addAttribute(BASE, StyleConstants.Foreground, Color.ORANGE);

	private static final AttributeSet[] PARENS = new AttributeSet[10];
	
	static {
		// Generator code for Rainbow parens
		for (int i=0; i<PARENS.length; i++) {
			Color c=Color.getHSBColor((0.1f*i), 1, 1);
			c=c.brighter();
			AttributeSet aset = sc.addAttribute(BASE, StyleConstants.Foreground, c);
			PARENS[i]=aset;
		}
	}

	public static void highlight(JTextPane inputArea, int start, int end) {
		try {
			// Get range of text to format
			StyledDocument d=inputArea.getStyledDocument();
			int dlen=d.getLength();
			end=Math.min(end, dlen); // ensure in bounds
			
			String input=d.getText(start, end-start);
			
			CharStream cs=CharStreams.fromString(input);
			Lexer lexer=AntlrReader.getLexer(cs);
			lexer.removeErrorListeners();
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			tokens.fill();
			//ConvexParser parser = new ConvexParser(tokens);
			//parser.removeErrorListeners();
			//ParseTree pt = parser.forms();
			
			int tcount=tokens.size();
			int nest=0;
			for (int i=0; i<tcount; i++) {
				Token tok=tokens.get(i);
				int tstart=start+tok.getStartIndex();
				int tend=start+tok.getStopIndex()+1;
				int tlen=tend-tstart;
				
				if (tlen>0) {
					char c=input.charAt(tstart-start);
					
					AttributeSet aset=NORMAL;
					switch (c) {
						case '"': {
							aset=STRING; break;
						}
						case ':': {
							aset=KEYWORD; break;
						}
						case '(':{
							aset=getParen(nest++); break;
						}
						case ')': {
							aset=getParen(--nest); break;
						}
						case '[':{
							nest+=2;
							aset=getParen(nest++); break;
						}
						case ']': {
							aset=getParen(--nest); 
							nest-=2; break;
						}
						case '{':{
							nest+=4;
							aset=getParen(nest++); break;
						}
						case '}': {
							aset=getParen(--nest); 
							nest-=4; break;
						}
						// Whitespace uses paren colour, this colours commas!
						case ' ': case '\t': case ',': {
							aset=getParen(nest); 
							break;
						}

						default: {
							if (Character.isDigit(c)) {
								aset=NUMBER; break;
							}
						}
					}
					
					d.setCharacterAttributes(tstart, tlen, aset, false);
				}
			}
		} catch (Exception t) {
			t.printStackTrace();
		}
	}

	private static AttributeSet getParen(int i) {
		i++;
		if (i<0) i=0;
		return PARENS[i%PARENS.length];
	}

}
