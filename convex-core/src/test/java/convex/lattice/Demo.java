package convex.lattice;

import java.io.IOException;
import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.MapEntry;
import convex.core.data.impl.LongBlob;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.etch.EtchStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.PathCursor;
import convex.lattice.cursor.Root;

/**
 * Simple demonstration of using an EtchStore together with lattice cursors.
 * <p>
 * The demo:
 * <ul>
 *   <li>Creates a temporary Etch database file.</li>
 *   <li>Creates a root cursor whose value contains a {@code /db/users} path.</li>
 *   <li>Populates 10,000 test user records under {@code /db/users} as an
 *       {@link Index} mapping {@link Hash} user IDs to user records.</li>
 *   <li>Persists the final cursor value as the Etch database root.</li>
 * </ul>
 */
public class Demo {

	// Interned CVM Strings for well-known keys / paths
	private static final AString KEY_USER_ID = Strings.intern("user-id");
	private static final AString KEY_FAMILY = Strings.intern("family");
	private static final AString KEY_EMAIL = Strings.intern("email");
	private static final AString KEY_BIRTHDATE = Strings.intern("birthdate");
	private static final AString KEY_CREATED_AT = Strings.intern("created-at");
	private static final AString KEY_DB = Strings.intern("db");
	private static final AString KEY_USERS = Strings.intern("users");

	/**
	 * Run the demo.
	 *
	 * @param args Command-line arguments (ignored)
	 * @throws IOException If an IO error occurs working with the Etch store
	 */
	public static void main(String[] args) throws IOException {
		long startTime = System.currentTimeMillis();
		System.out.println("Demo starting at " + startTime + " ms");

		// 1. Create an EtchStore backed by a temporary file
		EtchStore store = EtchStore.createTemp("lattice-demo");
		System.out.println("Created EtchStore at: " + store.getFileName());

		// 2. Create a root cursor with an initial structure containing /db/users
		//    /db is a map; /db/users is an Index<LongBlob, ACell> that will hold user records.
		Index<LongBlob, ACell> emptyUsersIndex = Index.none();
		AMap<ACell, ACell> initialDb = Maps.of(
				KEY_USERS, emptyUsersIndex
		);
		AMap<ACell, ACell> initialRootMap = Maps.of(
				KEY_DB, initialDb
		);

		Root<ACell> rootCursor = Cursors.create(RT.cvm(initialRootMap));

		// 3. Create a cursor focused on the /db/users path
		PathCursor<Index<LongBlob, ACell>> usersCursor = new PathCursor<>(
				rootCursor,
				KEY_DB,
				KEY_USERS
		);

		Index<LongBlob, ACell> snapshot=Index.none();
		ArrayList<Index<LongBlob, ACell>> snapshots=new ArrayList<>();

		
		// 4. Populate 10,000 test user records:
		//    Each record is a map with:
		//      - user-id  : Hash (the index key)
		//      - family   : family name (String)
		//      - email    : email address (String)
		//      - birthdate: birth date (String)
		int nUsers = 10_000;
		for (int i = 0; i < nUsers; i++) {
			// Derive a deterministic userID LongBlob from the loop index
			LongBlob userId = LongBlob.create(i);

			AMap<ACell, ACell> userRecord = createTestUser(userId, i);

			// Update the /db/users index: userID -> userRecord
			usersCursor.updateAndGet(currentIndex -> {
				Index<LongBlob, ACell> idx = currentIndex;
				return idx.assoc(userId, userRecord);
			});
			if (i==nUsers/2) {
				snapshot=usersCursor.get();
			}
			snapshots.add(usersCursor.get());
 		}
		
		System.out.println("Users in snapshot: "+snapshot.count());

		System.out.println("Added " + nUsers + " user records under /db/users");

		// 5. Persist the cursor value to the Etch DB root
		ACell finalRootValue = rootCursor.get();
		store.setRootData(finalRootValue);
		store.flush();

		System.out.println("Persisted root value to Etch store.");
		Hash rootHash = store.getRootHash();
		System.out.println("Root hash: " + rootHash);

		// Keep a reference to the underlying file so we can reopen the store
		java.io.File etchFile = store.getFile();

		store.close();

		// 6. Reload the root value from the Etch database
		EtchStore reopenedStore = EtchStore.create(etchFile);
		Hash reopenedRootHash = reopenedStore.getRootHash();
		ACell reloadedRoot = reopenedStore.refForHash(reopenedRootHash).getValue();

		// Recreate a cursor from the reloaded root value
		Root<ACell> reloadedRootCursor = Cursors.create(reloadedRoot);
		PathCursor<Index<LongBlob, ACell>> reloadedUsersCursor = new PathCursor<>(
				reloadedRootCursor,
				KEY_DB,
				KEY_USERS
		);

		Index<LongBlob, ACell> reloadedUsersIndex = reloadedUsersCursor.get();
		if (reloadedUsersIndex != null && reloadedUsersIndex.count() > 0) {
			long lastIx = reloadedUsersIndex.count() - 1;

			MapEntry<LongBlob, ACell> lastEntry = reloadedUsersIndex.entryAt(lastIx);
			System.out.println("Last user key  : " + lastEntry.getKey());
			System.out.println("Last user value: " + JSON.toStringPretty(lastEntry.getValue()));
		} else {
			System.out.println("No users found after reload.");
		}
		
		System.out.println("Total size: " + reloadedRootCursor.get().getMemorySize());


		reopenedStore.close();

		long endTime = System.currentTimeMillis();
		long duration = endTime - startTime;
		System.out.println("Demo finished at " + endTime + " ms");
		System.out.println("Total execution time: " + duration + " ms");
		
		// lattice.merge(rootCurstor,remoteCursorValue)
	}

	private static AMap<ACell, ACell> createTestUser(LongBlob userId, int index) {
		long createdAt = System.currentTimeMillis();

		return Maps.of(
				KEY_USER_ID, userId,
				KEY_FAMILY, Strings.create("User-" + index),
				KEY_EMAIL, Strings.create("user" + index + "@example.com"),
				KEY_BIRTHDATE, Strings.create("1970-01-01"),
				KEY_CREATED_AT, RT.cvm(createdAt)
		);
	}
}

