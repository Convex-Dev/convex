package convex.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Tests for StringUtils, particularly the escapeHtml method
 */
public class StringUtilsTest {

    /**
     * Test basic HTML escaping functionality
     */
    @Test
    public void testBasicHtmlEscaping() {
        AString input = Strings.create("Hello <world> & \"quotes\" 'apostrophes'");
        AString result = StringUtils.escapeHtml(input);
        AString expected = Strings.create("Hello &lt;world&gt; &amp; &quot;quotes&quot; &#39;apostrophes&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test empty string handling
     */
    @Test
    public void testEmptyString() {
        AString input = Strings.create("");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("", result.toString());
    }

    /**
     * Test null string handling
     */
    @Test
    public void testNullString() {
        AString result = StringUtils.escapeHtml(null);
        
        assertNotNull(result);
        assertEquals("", result.toString());
    }

    /**
     * Test string with no special characters
     */
    @Test
    public void testNoSpecialCharacters() {
        AString input = Strings.create("Hello World 123");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("Hello World 123", result.toString());
    }

    /**
     * Test only ampersands
     */
    @Test
    public void testOnlyAmpersands() {
        AString input = Strings.create("&&&");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&amp;&amp;&amp;", result.toString());
    }

    /**
     * Test only angle brackets
     */
    @Test
    public void testOnlyAngleBrackets() {
        AString input = Strings.create("<<<>>>");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&lt;&lt;&lt;&gt;&gt;&gt;", result.toString());
    }

    /**
     * Test only quotes
     */
    @Test
    public void testOnlyQuotes() {
        AString input = Strings.create("\"\"''");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&quot;&quot;&#39;&#39;", result.toString());
    }

    /**
     * Test mixed special characters
     */
    @Test
    public void testMixedSpecialCharacters() {
        AString input = Strings.create("<tag>\"value\" & 'text'</tag>");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&lt;tag&gt;&quot;value&quot; &amp; &#39;text&#39;&lt;/tag&gt;", result.toString());
    }

    /**
     * Test Unicode characters (emojis, mathematical symbols, etc.)
     */
    @Test
    public void testUnicodeCharacters() {
        AString input = Strings.create("Hello 🌍 & <math>∑</math> & \"quotes\"");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should contain the Unicode characters unescaped
        assertTrue(resultStr.contains("🌍"));
        assertTrue(resultStr.contains("∑"));
        // But special HTML characters should be escaped
        assertTrue(resultStr.contains("&amp;"));
        assertTrue(resultStr.contains("&lt;math&gt;"));
        assertTrue(resultStr.contains("&quot;quotes&quot;"));
    }

    /**
     * Test international characters
     */
    @Test
    public void testInternationalCharacters() {
        AString input = Strings.create("你好 <world> & \"世界\" 'мир'");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should contain the international characters unescaped
        assertTrue(resultStr.contains("你好"));
        assertTrue(resultStr.contains("世界"));
        assertTrue(resultStr.contains("мир"));
        // But special HTML characters should be escaped
        assertTrue(resultStr.contains("&lt;world&gt;"));
        assertTrue(resultStr.contains("&amp;"));
        assertTrue(resultStr.contains("&quot;世界&quot;"));
        assertTrue(resultStr.contains("&#39;мир&#39;"));
    }

    /**
     * Test mathematical and scientific symbols
     */
    @Test
    public void testMathematicalSymbols() {
        AString input = Strings.create("α < β & γ > δ \"ε\" 'ζ'");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should contain the mathematical symbols unescaped
        assertTrue(resultStr.contains("α"));
        assertTrue(resultStr.contains("β"));
        assertTrue(resultStr.contains("γ"));
        assertTrue(resultStr.contains("δ"));
        assertTrue(resultStr.contains("ε"));
        assertTrue(resultStr.contains("ζ"));
        // But special HTML characters should be escaped
        assertTrue(resultStr.contains("&lt;"));
        assertTrue(resultStr.contains("&gt;"));
        assertTrue(resultStr.contains("&amp;"));
        assertTrue(resultStr.contains("&quot;"));
        assertTrue(resultStr.contains("&#39;"));
    }

    /**
     * Test long string with many special characters
     */
    @Test
    public void testLongString() {
        String testString = "<tag>0</tag> & \"quoted\" 'apostrophe'<tag>1</tag><tag>2</tag><tag>3</tag><tag>4</tag> & \"quoted\" 'apostrophe'<tag>5</tag>";
        AString input = Strings.create(testString);
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Verify that all angle brackets are escaped (no raw < or >)
        assertEquals(0, resultStr.chars().filter(c -> c == '<').count());
        assertEquals(0, resultStr.chars().filter(c -> c == '>').count());
        // Verify that all quotes are escaped (no raw " or ')
        assertEquals(0, resultStr.chars().filter(c -> c == '"').count());
        assertEquals(0, resultStr.chars().filter(c -> c == '\'').count());
        // Verify that all ampersands are part of escape sequences (no raw &)
        // This is more complex - we need to check that every & is followed by a valid escape sequence
        for (int i = 0; i < resultStr.length(); i++) {
            if (resultStr.charAt(i) == '&') {
                // Check if this is the start of a valid escape sequence
                boolean validEscape = false;
                if (i + 4 < resultStr.length() && resultStr.substring(i, i + 5).equals("&amp;")) {
                    validEscape = true;
                } else if (i + 3 < resultStr.length() && resultStr.substring(i, i + 4).equals("&lt;")) {
                    validEscape = true;
                } else if (i + 3 < resultStr.length() && resultStr.substring(i, i + 4).equals("&gt;")) {
                    validEscape = true;
                } else if (i + 5 < resultStr.length() && resultStr.substring(i, i + 6).equals("&quot;")) {
                    validEscape = true;
                } else if (i + 4 < resultStr.length() && resultStr.substring(i, i + 5).equals("&#39;")) {
                    validEscape = true;
                }
                assertTrue(validEscape, "Found unescaped ampersand at position " + i);
            }
        }
    }

    /**
     * Test string with newlines and tabs
     */
    @Test
    public void testNewlinesAndTabs() {
        AString input = Strings.create("Line1\nLine2\tTabbed");
        AString result = StringUtils.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("Line1\nLine2\tTabbed", result.toString());
    }
}
