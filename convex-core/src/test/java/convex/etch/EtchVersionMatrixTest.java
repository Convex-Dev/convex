package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.lattice.Lattice;

/**
 * Compatibility matrix for data moving between supported Etch versions.
 */
public class EtchVersionMatrixTest {

	private static final short[] VERSIONS={
		EtchConstants.VERSION_1,
		EtchConstants.VERSION_2
		// TODO: Add EtchConstants.VERSION_3 when Etch v3 is implemented.
	};

	private final List<EtchStore> openStores=new ArrayList<>();

	@AfterEach
	public void closeStores() {
		for (EtchStore store: openStores) {
			store.close();
		}
		openStores.clear();
	}

	@Test
	public void testCrossVersionLatticeRootMerges() throws IOException {
		Map<Short,EtchStore> stores=createVersionStores("etch-root-matrix");
		Map<Short,AString> values=new LinkedHashMap<>();

		for (Map.Entry<Short,EtchStore> entry: stores.entrySet()) {
			short version=entry.getKey();
			AString value=latticeValue(version);
			values.put(version,value);
			entry.getValue().setRootData(rootFor(value));
		}

		// Merge every supported source version into every other target version.
		// With more than two versions the ordered pass also proves transitive data
		// remains readable as roots cross further version boundaries.
		for (Map.Entry<Short,EtchStore> sourceEntry: stores.entrySet()) {
			for (Map.Entry<Short,EtchStore> targetEntry: stores.entrySet()) {
				if (sourceEntry.getKey().equals(targetEntry.getKey())) continue;

				Index<Keyword,ACell> sourceRoot=sourceEntry.getValue().getRootData();
				Index<Keyword,ACell> targetRoot=targetEntry.getValue().getRootData();
				Index<Keyword,ACell> merged=Lattice.ROOT.merge(targetRoot,sourceRoot);
				targetEntry.getValue().setRootData(merged);

				AString sourceValue=values.get(sourceEntry.getKey());
				assertEquals(sourceValue,dataIndex(targetEntry.getValue().getRootData()).get(sourceValue.getHash()),
						matrixMessage("root merge",sourceEntry.getKey(),targetEntry.getKey()));
			}
		}

		for (Map.Entry<Short,EtchStore> targetEntry: stores.entrySet()) {
			Index<Hash,ACell> data=dataIndex(targetEntry.getValue().getRootData());
			for (Map.Entry<Short,AString> valueEntry: values.entrySet()) {
				AString value=valueEntry.getValue();
				assertEquals(value,data.get(value.getHash()),
						"v"+targetEntry.getKey()+" root is missing v"+valueEntry.getKey()+" data");
			}
		}
	}

	@Test
	public void testCrossVersionMigrationCopiesValuesOutsideRootIndex() throws IOException {
		Map<Short,EtchStore> stores=createVersionStores("etch-migrate-matrix");
		Map<Short,Hash> originalRoots=new LinkedHashMap<>();
		Map<Short,AString> detachedValues=new LinkedHashMap<>();

		for (Map.Entry<Short,EtchStore> entry: stores.entrySet()) {
			short version=entry.getKey();
			EtchStore store=entry.getValue();
			store.setRootData(rootFor(latticeValue(version)));
			originalRoots.put(version,store.getRootHash());

			AString detached=detachedValue(version);
			assertFalse(detached.isEmbedded());
			Cells.persist(detached,store);
			detachedValues.put(version,detached);

			assertNull(dataIndex(store.getRootData()).get(detached.getHash()),
					"Detached value must not be reachable through the lattice root");
		}

		for (Map.Entry<Short,EtchStore> sourceEntry: stores.entrySet()) {
			for (Map.Entry<Short,EtchStore> targetEntry: stores.entrySet()) {
				if (sourceEntry.getKey().equals(targetEntry.getKey())) continue;
				long migrated=EtchUtils.migrate(sourceEntry.getValue(),targetEntry.getValue());
				assertTrue(migrated>0,matrixMessage("migration",sourceEntry.getKey(),targetEntry.getKey()));
			}
		}

		for (Map.Entry<Short,EtchStore> targetEntry: stores.entrySet()) {
			short targetVersion=targetEntry.getKey();
			EtchStore target=targetEntry.getValue();
			assertEquals(originalRoots.get(targetVersion),target.getRootHash(),
					"Migration must not change the v"+targetVersion+" root");

			for (Map.Entry<Short,AString> valueEntry: detachedValues.entrySet()) {
				AString value=valueEntry.getValue();
				Ref<ACell> ref=target.refForHash(value.getHash());
				assertNotNull(ref,"v"+targetVersion+" is missing detached v"+valueEntry.getKey()+" data");
				assertTrue(ref.getStatus()>=Ref.PERSISTED,
						"Detached v"+valueEntry.getKey()+" data lost persisted status in v"+targetVersion);
				assertEquals(value,ref.getValue());
			}
		}
	}

	private Map<Short,EtchStore> createVersionStores(String prefix) throws IOException {
		Map<Short,EtchStore> stores=new LinkedHashMap<>();
		for (short version: VERSIONS) {
			EtchConfig config=EtchConfig.create(version);
			EtchStore store=new EtchStore(Etch.createTempEtch(prefix+"-v"+version,config));
			openStores.add(store);
			stores.put(version,store);
			assertEquals(config,store.getEtch().getConfig());
			assertEquals(version,store.getEtch().getVersion());
		}
		return stores;
	}

	private static Index<Keyword,ACell> rootFor(AString value) {
		Index<Hash,ACell> data=Index.create(value.getHash(),value);
		return Lattice.ROOT.zero().assoc(Keywords.DATA,data);
	}

	@SuppressWarnings("unchecked")
	private static Index<Hash,ACell> dataIndex(Index<Keyword,ACell> root) {
		return (Index<Hash,ACell>) root.get(Keywords.DATA);
	}

	private static AString latticeValue(short version) {
		return Strings.create("deterministic lattice value for Etch v"+version);
	}

	private static AString detachedValue(short version) {
		return Strings.create("detached Etch v"+version+" value "+"0123456789abcdef".repeat(8));
	}

	private static String matrixMessage(String operation, short sourceVersion, short targetVersion) {
		return operation+" failed from Etch v"+sourceVersion+" to v"+targetVersion;
	}
}
