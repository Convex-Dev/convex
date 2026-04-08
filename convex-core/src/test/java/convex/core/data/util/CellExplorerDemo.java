package convex.core.data.util;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Manual review harness for CellExplorer output quality.
 *
 * Runs representative scenarios and prints each input + its rendered output
 * so the output can be eyeballed for LLM readability. Not a JUnit test — run
 * via {@code mvn exec:java} or directly.
 */
public class CellExplorerDemo {

	public static void main(String[] args) {
		header("1. Simple account-like map (fits budget)");
		ACell account = Maps.empty();
		account = ((AMap<ACell, ACell>) account).assoc(Keyword.create("owner"), Address.create(1337));
		account = ((AMap<ACell, ACell>) account).assoc(Keyword.create("balance"), CVMLong.create(1_000_000_000L));
		account = ((AMap<ACell, ACell>) account).assoc(Keyword.create("name"), Strings.create("Alice"));
		show(account, 1024);

		header("2. Vector of records (fits budget)");
		ACell records = Vectors.of(
			Maps.of(Keyword.create("id"), CVMLong.create(1),
				Keyword.create("label"), Strings.create("first")),
			Maps.of(Keyword.create("id"), CVMLong.create(2),
				Keyword.create("label"), Strings.create("second")),
			Maps.of(Keyword.create("id"), CVMLong.create(3),
				Keyword.create("label"), Strings.create("third")));
		show(records, 1024);

		header("3. Same as #2 but PRETTY mode");
		showPretty(records, 1024);

		header("4. Large vector truncated (60-byte budget)");
		ACell[] items = new ACell[50];
		for (int i = 0; i < 50; i++) items[i] = CVMLong.create(i * 100);
		show(Vectors.create(items), 60);

		header("5. Large vector truncated (200-byte budget)");
		show(Vectors.create(items), 200);

		header("6. Wide map truncated (150-byte budget)");
		AMap<ACell, ACell> wide = Maps.empty();
		for (int i = 0; i < 30; i++) {
			wide = wide.assoc(Keyword.create("key" + i), Strings.create("value-" + i));
		}
		show(wide, 150);

		header("7. Wide map truncated (400-byte budget)");
		show(wide, 400);

		header("8. Very tight budget (fully truncated map)");
		show(wide, 25);

		header("9. Non-finite doubles in a vector");
		ACell doubles = Vectors.of(
			CVMDouble.create(3.14),
			CVMDouble.NaN,
			CVMDouble.POSITIVE_INFINITY,
			CVMDouble.NEGATIVE_INFINITY,
			CVMDouble.create(-0.0));
		show(doubles, 1024);

		header("10. Mixed CVM types (Address, Keyword, Symbol as values)");
		ACell mixed = Maps.of(
			Keyword.create("addr"), Address.create(42),
			Keyword.create("tag"), Keyword.create("active"),
			Keyword.create("sym"), Symbol.create("my-func"),
			Keyword.create("count"), CVMLong.create(7));
		show(mixed, 1024);

		header("11. Large string (partial form, 80-byte budget)");
		String bigText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
			+ "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. "
			+ "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.";
		show(Strings.create(bigText), 80);

		header("12. Large string (partial form, 40-byte budget)");
		show(Strings.create(bigText), 40);

		header("13. Large blob (partial form, 80-byte budget)");
		byte[] blobBytes = new byte[500];
		for (int i = 0; i < blobBytes.length; i++) blobBytes[i] = (byte) i;
		show(Blob.create(blobBytes), 80);

		header("14. Set with truncation (100-byte budget)");
		ACell[] setItems = new ACell[20];
		for (int i = 0; i < 20; i++) setItems[i] = Strings.create("element-" + i);
		show(Sets.of((Object[]) setItems), 100);

		header("15. Small set (fits budget, shows /* Set */ marker)");
		show(Sets.of(1, 2, 3), 1024);

		header("16. List rendering");
		show(Lists.of(10, 20, 30, 40, 50), 1024);

		header("17. Deeply nested structure (fits)");
		ACell deep = Maps.of(
			Keyword.create("l1"), Maps.of(
				Keyword.create("l2"), Maps.of(
					Keyword.create("l3"), Vectors.of(
						Maps.of(Keyword.create("leaf"), Strings.create("here"))))));
		show(deep, 1024);

		header("18. Same as #17 but PRETTY mode");
		showPretty(deep, 1024);

		header("19. Deeply nested truncated (100-byte budget)");
		show(deep, 100);

		header("20. Map of vectors truncated (realistic, 200-byte budget)");
		AMap<ACell, ACell> mapVecs = Maps.empty();
		for (int i = 0; i < 10; i++) {
			ACell[] inner = new ACell[20];
			for (int j = 0; j < 20; j++) inner[j] = CVMLong.create(i * 100 + j);
			mapVecs = mapVecs.assoc(Keyword.create("bucket" + i), Vectors.create(inner));
		}
		show(mapVecs, 200);

		header("21. Account-like state map (realistic blockchain example)");
		ACell state = Maps.empty();
		state = ((AMap<ACell, ACell>) state).assoc(Keyword.create("accounts"),
			Maps.of(
				Address.create(11), Maps.of(
					Keyword.create("balance"), CVMLong.create(5_000_000_000L),
					Keyword.create("sequence"), CVMLong.create(42),
					Keyword.create("key"), Blob.create(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 })),
				Address.create(12), Maps.of(
					Keyword.create("balance"), CVMLong.create(100_000_000L),
					Keyword.create("sequence"), CVMLong.create(7),
					Keyword.create("key"), Blob.create(new byte[] { 9, 10, 11, 12, 13, 14, 15, 16 }))));
		state = ((AMap<ACell, ACell>) state).assoc(Keyword.create("globals"),
			Maps.of(Keyword.create("timestamp"), CVMLong.create(1_712_000_000L),
				Keyword.create("fees"), CVMLong.create(0)));
		show(state, 1024);

		header("22. Same as #21 but PRETTY mode");
		showPretty(state, 1024);

		header("23. Same as #21 but TIGHT budget (150 bytes)");
		show(state, 150);

		header("24. Adversarial: string containing JSON5 metacharacters");
		show(Strings.create("has \"quotes\", /* comment */ and */ terminator"), 1024);

		header("25. Edge case: map with non-identifier keyword key");
		show(Maps.of(
			Keyword.create("normal"), CVMLong.create(1),
			Keyword.create("has-dash"), CVMLong.create(2),
			Keyword.create("has/slash"), CVMLong.create(3)), 1024);
	}

	private static void header(String title) {
		System.out.println();
		System.out.println("========================================================");
		System.out.println("  " + title);
		System.out.println("========================================================");
	}

	private static void show(ACell cell, int budget) {
		CellExplorer explorer = new CellExplorer(budget);
		String out = explorer.explore(cell).toString();
		System.out.println("[compact, budget=" + budget + " bytes, output=" + out.length() + " chars]");
		System.out.println(out);
	}

	private static void showPretty(ACell cell, int budget) {
		CellExplorer explorer = new CellExplorer(budget, false);
		String out = explorer.explore(cell).toString();
		System.out.println("[pretty, budget=" + budget + " bytes, output=" + out.length() + " chars]");
		System.out.println(out);
	}
}
