package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Tests for AGenericAPI, particularly the escapeHtml method
 */
public class AGenericAPITest {


    /**
     * Test basic HTML escaping functionality
     */
    @Test
    public void testBasicHtmlEscaping() {
        AString input = Strings.create("Hello <world> & \"quotes\" 'apostrophes'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("Hello &lt;world&gt; &amp; &quot;quotes&quot; &#39;apostrophes&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test empty string handling
     */
    @Test
    public void testEmptyString() {
        AString input = Strings.EMPTY;
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("", result.toString());
    }

    /**
     * Test null string handling
     */
    @Test
    public void testNullString() {
        AString result = AGenericAPI.escapeHtml(null);
        
        assertNotNull(result);
        assertEquals("", result.toString());
    }

    /**
     * Test string with no special characters
     */
    @Test
    public void testNoSpecialCharacters() {
        AString input = Strings.create("Hello World 123");
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("Hello World 123", result.toString());
    }

    /**
     * Test only ampersands
     */
    @Test
    public void testOnlyAmpersands() {
        AString input = Strings.create("&&&");
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&amp;&amp;&amp;", result.toString());
    }

    /**
     * Test only angle brackets
     */
    @Test
    public void testOnlyAngleBrackets() {
        AString input = Strings.create("<<<>>>");
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&lt;&lt;&lt;&gt;&gt;&gt;", result.toString());
    }

    /**
     * Test only quotes
     */
    @Test
    public void testOnlyQuotes() {
        AString input = Strings.create("\"\"''");
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("&quot;&quot;&#39;&#39;", result.toString());
    }

    /**
     * Test mixed special characters
     */
    @Test
    public void testMixedSpecialCharacters() {
        AString input = Strings.create("<script>alert('XSS')</script>");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("&lt;script&gt;alert(&#39;XSS&#39;)&lt;/script&gt;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - basic Latin
     */
    @Test
    public void testUnicodeBasicLatin() {
        AString input = Strings.create("Hello <café> & \"naïve\" 'résumé'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("Hello &lt;café&gt; &amp; &quot;naïve&quot; &#39;résumé&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - emoji
     */
    @Test
    public void testUnicodeEmoji() {
        AString input = Strings.create("Hello <😀> & \"🎉\" '🚀'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("Hello &lt;😀&gt; &amp; &quot;🎉&quot; &#39;🚀&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - mathematical symbols
     */
    @Test
    public void testUnicodeMathSymbols() {
        AString input = Strings.create("Math: <∑> & \"π\" '∞'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("Math: &lt;∑&gt; &amp; &quot;π&quot; &#39;∞&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - Chinese characters
     */
    @Test
    public void testUnicodeChinese() {
        AString input = Strings.create("中文 <世界> & \"你好\" '测试'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("中文 &lt;世界&gt; &amp; &quot;你好&quot; &#39;测试&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - Arabic
     */
    @Test
    public void testUnicodeArabic() {
        AString input = Strings.create("مرحبا <العالم> & \"السلام\" 'اختبار'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("مرحبا &lt;العالم&gt; &amp; &quot;السلام&quot; &#39;اختبار&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test Unicode characters - mixed scripts
     */
    @Test
    public void testUnicodeMixedScripts() {
        AString input = Strings.create("Hello <世界> & \"مرحبا\" '😀'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("Hello &lt;世界&gt; &amp; &quot;مرحبا&quot; &#39;😀&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test very long string with mixed content
     */
    @Test
    public void testLongString() {
        String testString = "<tag>0</tag> & \"quoted\" 'apostrophe'<tag>1</tag><tag>2</tag><tag>3</tag><tag>4</tag> & \"quoted\" 'apostrophe'<tag>5</tag>";
        AString input = Strings.create(testString);
        AString result = AGenericAPI.escapeHtml(input);
        
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
     * Test edge case - string with only special characters
     */
    @Test
    public void testOnlySpecialCharacters() {
        AString input = Strings.create("&<>\"'");
        AString result = AGenericAPI.escapeHtml(input);
        AString expected = Strings.create("&amp;&lt;&gt;&quot;&#39;");
        
        assertNotNull(result);
        assertEquals(expected.toString(), result.toString());
    }

    /**
     * Test edge case - string with newlines and tabs
     */
    @Test
    public void testNewlinesAndTabs() {
        AString input = Strings.create("Line1\nLine2\tTabbed");
        AString result = AGenericAPI.escapeHtml(input);
        
        assertNotNull(result);
        assertEquals("Line1\nLine2\tTabbed", result.toString());
    }

}
