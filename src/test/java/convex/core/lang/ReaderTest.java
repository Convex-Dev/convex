package convex.core.lang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.parboiled.Parboiled;

import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.test.Samples;

public class ReaderTest {

	@Test
	public void testVectors() {
		assertSame(Vectors.empty(), Reader.read("[]"));
		assertSame(Vectors.empty(), Reader.read(" [ ] "));
		assertEquals(Vectors.of(Samples.FOO), Reader.read(" [ :foo ] "));
		assertEquals(Vectors.of(Vectors.empty()), Reader.read(" [ [] ] "));
	}

	@Test
	public void testKeywords() {
		assertEquals(Samples.FOO, Reader.read(":foo"));
		assertEquals(Keyword.create("foo.bar"), Reader.read(":foo.bar"));
	}

	@Test
	public void testBadKeywords() {
		assertThrows(Error.class, () -> Reader.read(":"));
	}

	@Test
	public void testComment() {
		assertEquals(1L, (long) Reader.read(";this is a comment\n 1 \n"));
		assertEquals(2L, (long) Reader.read("#_foo 2"));
		assertEquals(3L, (long) Reader.read("3 #_foo"));
	}

	@Test
	public void testReadAll() {
		assertSame(Lists.empty(), Reader.readAllSyntax(""));
		assertSame(Lists.empty(), Reader.readAllSyntax("  "));
		assertEquals(Samples.FOO, Reader.readAllSyntax(" :foo ").get(0).getValue());
		assertEquals(Symbol.create("+"), Reader.readAllSyntax("+ 1").get(0).getValue());
	}

	@Test
	public void testReadSymbol() {
		assertEquals(Symbols.FOO, Reader.readSymbol("foo"));
		assertThrows(Error.class, () -> Reader.readSymbol(""));
		assertThrows(Error.class, () -> Reader.readSymbol("1"));
	}

	@Test
	public void testSymbols() {
		assertEquals(Symbols.FOO, Reader.read("foo"));
		assertEquals(Lists.of(Symbol.create("+"), 1L), Reader.read("(+ 1)"));
		assertEquals(Lists.of(Symbol.create("+a")), Reader.read("( +a )"));
		assertEquals(Lists.of(Symbol.create("/")), Reader.read("(/)"));
		assertEquals(Symbol.createWithNamespace("b","a"), Reader.read("a/b"));
		assertEquals(Symbol.create("a*+!-_?<>=!"), Reader.read("a*+!-_?<>=!"));
		assertEquals(Symbol.create("foo.bar"), Reader.read("foo.bar"));
		assertEquals(Symbol.create(".bar"), Reader.read(".bar"));
		
		// namespaces cannot themselves be qualified
		assertThrows(ParseException.class,()->Reader.read("a/b/c"));
	}

	@Test
	public void testSymbolsRegressionCases() {
		assertEquals(Symbol.create("nils"), Reader.read("nils"));

		// symbol starting with a boolean value
		assertEquals(Symbol.create("falsey"), Reader.read("falsey"));
		assertEquals(Symbol.create("true-exp"), Reader.read("true-exp"));
	}

	@Test
	public void testChar() {
		assertEquals('A', (char) Reader.read("\\A"));
		assertEquals('a', (char) Reader.read("\\u0061"));
		assertEquals(' ', (char) Reader.read("\\space"));
		assertEquals('\t', (char) Reader.read("\\tab"));
		assertEquals('\n', (char) Reader.read("\\newline"));
		assertEquals('\f', (char) Reader.read("\\formfeed"));
		assertEquals('\b', (char) Reader.read("\\backspace"));
		assertEquals('\r', (char) Reader.read("\\return"));
	}

	@Test
	public void testNumbers() {
		assertEquals(1L, (long) Reader.read("1"));
		assertEquals(Double.valueOf(2.0), Reader.read("2.0"));

		// metadata ignored
		assertEquals(3.23, Reader.read("^:foo 3.23"));
	}
	
	@Test
	public void testHexBlobs() {
		assertEquals(Blobs.fromHex("cafebabe"), Reader.read("0xcafebabe"));
		assertEquals(Blobs.fromHex("0aA1"), Reader.read("0x0Aa1"));
		assertEquals(Blob.EMPTY, Reader.read("0x"));
	
		// TODO: figure out the edge case
		assertThrows(Error.class, () -> Reader.read("0x1"));
		//assertThrows(Error.class, () -> Reader.read("[0x1]")); // odd number of hex digits

		assertThrows(Error.class, () -> Reader.read("0x123")); // odd number of hex digits
	}

	@Test
	public void testNil() {
		assertNull(Reader.read("nil"));

		// metadata ignored
		assertNull(Reader.read("^:foo nil"));
	}

	@Test
	public void testStrings() {
		assertEquals("", Reader.read("\"\""));
		assertEquals("bar", Reader.read("\"bar\""));
		assertEquals(Vectors.of("bar"), Reader.read("[\"bar\"]"));
		assertEquals("\"bar\"", Reader.read("\"\\\"bar\\\"\""));

	}

	@Test
	public void testList() {
		assertSame(Lists.empty(), Reader.read(" ()"));
		assertEquals(Lists.of(1L, 2L), Reader.read("(1 2)"));
		assertEquals(Lists.of(Vectors.empty()), Reader.read(" ([] )"));
	}

	@Test
	public void testNoWhiteSpace() {
		assertEquals(Lists.of(Vectors.empty(), Vectors.empty()), Reader.read("([][])"));
		assertEquals(Lists.of(Vectors.empty(), 13L), Reader.read("([]13)"));
		assertEquals(Lists.of(Symbols.SET, Vectors.empty()), Reader.read("(set[])"));
	}

	@Test
	public void testMaps() {
		assertSame(Maps.empty(), Reader.read("{}"));
		assertEquals(Maps.of(1L, 2L), Reader.read("{1,2}"));
		assertEquals(Maps.of(Samples.FOO, Samples.BAR), Reader.read("{:foo :bar}"));
	}

	@Test
	public void testQuote() {
		assertEquals(Lists.of(Symbols.QUOTE, 1L), Reader.read("'1"));
		assertEquals(Lists.of(Symbols.QUOTE, Lists.of(Symbols.QUOTE, Vectors.empty())), Reader.read("''[]"));
	}

	@Test
	public void testRules() {
		Reader reader = Parboiled.createParser(Reader.class, false);
		assertEquals(1L, (long) Reader.doParse(reader.Long(), "1  "));
	}

	@Test
	public void testWrongSizeMaps() {
		assertThrows(ParseException.class, () -> Reader.read("{:foobar}"));
	}

	@Test
	public void testParsingNothing() {
		assertThrows(ParseException.class, () -> Reader.read("  "));
	}

	@Test
	public void testSyntaxReader() {
		assertEquals(Syntax.class, Reader.readSyntax("nil").getClass());
		assertEquals(Syntax.create(1L), Reader.readSyntax("1").withoutMeta());
		assertEquals(Syntax.create(Symbols.FOO), Reader.readSyntax("foo").withoutMeta());
		assertEquals(Syntax.create(Keywords.FOO), Reader.readSyntax(":foo").withoutMeta());
	}

	@Test
	public void testSyntaxReaderExample() {
		String src = "[1 2 nil '(a b) :foo 2 \\a \"bar\" #{} {1 2 3 4}]";
		Syntax s = Reader.readSyntax(src);
		AVector<Syntax> v = s.getValue();
		Syntax v1 = v.get(1);
		assertEquals(2L, (long) v1.getValue());
		//assertEquals(3L, v1.getStart());
		//assertEquals(4L, v1.getEnd());

		//assertEquals(src, s.getSource());
	}

	@Test
	public void testMetadata() {
		assertEquals(Boolean.TRUE, Reader.readSyntax("^:foo a").getMeta().get(Keywords.FOO));
		
		{
			Syntax def=Reader.readAllSyntax("(def ^{:foo 2} a 1)").get(0);
			AList<Syntax> form=def.getValue();
			assertEquals(2L, form.get(1).getMeta().get(Keywords.FOO));
		}

		// TODO: Decide how to handle values within meta - unwrap Syntax Objects?
		assertEquals(Boolean.FALSE, Reader.readSyntax("^{:foo false} a").getMeta().get(Keywords.FOO));
		assertEquals(Vectors.of(1L, 2L), Reader.readSyntax("^{:foo [1 2]} a").getMeta().get(Keywords.FOO));
	}
}
