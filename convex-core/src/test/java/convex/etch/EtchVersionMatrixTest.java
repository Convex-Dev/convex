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

	private static final byte[] ENCRYPTION_SECRET={
			0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,
			0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,
			0x10,0x11,0x12,0x13,0x14,0x15,0x16,0x17,
			0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f
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
		Map<MatrixCase,EtchStore> stores=createStores("etch-root-matrix");
		Map<MatrixCase,AString> values=new LinkedHashMap<>();

		for (Map.Entry<MatrixCase,EtchStore> entry: stores.entrySet()) {
			MatrixCase matrixCase=entry.getKey();
			AString value=latticeValue(matrixCase);
			values.put(matrixCase,value);
			entry.getValue().setRootData(rootFor(value));
		}

		// Merge every supported source version into every other target version.
		// With more than two versions the ordered pass also proves transitive data
		// remains readable as roots cross further version boundaries.
		for (Map.Entry<MatrixCase,EtchStore> sourceEntry: stores.entrySet()) {
			for (Map.Entry<MatrixCase,EtchStore> targetEntry: stores.entrySet()) {
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

		for (Map.Entry<MatrixCase,EtchStore> targetEntry: stores.entrySet()) {
			Index<Hash,ACell> data=dataIndex(targetEntry.getValue().getRootData());
			for (Map.Entry<MatrixCase,AString> valueEntry: values.entrySet()) {
				AString value=valueEntry.getValue();
				assertEquals(value,data.get(value.getHash()),
						targetEntry.getKey().name()+" root is missing "+valueEntry.getKey().name()+" data");
			}
		}
	}

	@Test
	public void testCrossVersionMigrationCopiesValuesOutsideRootIndex() throws IOException {
		Map<MatrixCase,EtchStore> stores=createStores("etch-migrate-matrix");
		Map<MatrixCase,Hash> originalRoots=new LinkedHashMap<>();
		Map<MatrixCase,AString> detachedValues=new LinkedHashMap<>();

		for (Map.Entry<MatrixCase,EtchStore> entry: stores.entrySet()) {
			MatrixCase matrixCase=entry.getKey();
			EtchStore store=entry.getValue();
			store.setRootData(rootFor(latticeValue(matrixCase)));
			originalRoots.put(matrixCase,store.getRootHash());

			AString detached=detachedValue(matrixCase);
			assertFalse(detached.isEmbedded());
			Cells.persist(detached,store);
			detachedValues.put(matrixCase,detached);

			assertNull(dataIndex(store.getRootData()).get(detached.getHash()),
					"Detached value must not be reachable through the lattice root");
		}

		for (Map.Entry<MatrixCase,EtchStore> sourceEntry: stores.entrySet()) {
			for (Map.Entry<MatrixCase,EtchStore> targetEntry: stores.entrySet()) {
				if (sourceEntry.getKey().equals(targetEntry.getKey())) continue;
				long migrated=EtchUtils.migrate(sourceEntry.getValue(),targetEntry.getValue());
				assertTrue(migrated>0,matrixMessage("migration",sourceEntry.getKey(),targetEntry.getKey()));
			}
		}

		for (Map.Entry<MatrixCase,EtchStore> targetEntry: stores.entrySet()) {
			MatrixCase targetCase=targetEntry.getKey();
			EtchStore target=targetEntry.getValue();
			assertEquals(originalRoots.get(targetCase),target.getRootHash(),
					"Migration must not change the "+targetCase.name()+" root");

			for (Map.Entry<MatrixCase,AString> valueEntry: detachedValues.entrySet()) {
				AString value=valueEntry.getValue();
				Ref<ACell> ref=target.refForHash(value.getHash());
				assertNotNull(ref,targetCase.name()+" is missing detached "
						+valueEntry.getKey().name()+" data");
				assertTrue(ref.getStatus()>=Ref.PERSISTED,
						"Detached "+valueEntry.getKey().name()+" data lost persisted status in "
						+targetCase.name());
				assertEquals(value,ref.getValue());
			}
		}
	}

	private Map<MatrixCase,EtchStore> createStores(String prefix) throws IOException {
		Map<MatrixCase,EtchStore> stores=new LinkedHashMap<>();
		for (MatrixCase matrixCase:matrixCases()) {
			EtchConfig config=matrixCase.config();
			EtchStore store=new EtchStore(Etch.createTempEtch(prefix+"-"+matrixCase.name(),config));
			openStores.add(store);
			stores.put(matrixCase,store);
			assertEquals(config,store.getEtch().getConfig());
			assertEquals(config.getVersion(),store.getEtch().getVersion());
		}
		return stores;
	}

	private static List<MatrixCase> matrixCases() {
		EtchConfig.MappingMode mapping=EtchConfig.create(EtchConstants.VERSION_3).getMappingMode();
		return List.of(
				new MatrixCase("v1",EtchConfig.create(EtchConstants.VERSION_1)),
				new MatrixCase("v2",EtchConfig.create(EtchConstants.VERSION_2)),
				new MatrixCase("v3-plain",EtchConfig.create(EtchConstants.VERSION_3)),
				new MatrixCase("v3-aes",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.AES_256_CTR,
						false,null,ENCRYPTION_SECRET)),
				new MatrixCase("v3-chacha20",EtchConfig.createV3(mapping,
						EtchConstants.DEFAULT_BUILD_CHAINS,EtchConfig.CipherMode.CHACHA20,
						false,null,ENCRYPTION_SECRET)));
	}

	private static Index<Keyword,ACell> rootFor(AString value) {
		Index<Hash,ACell> data=Index.create(value.getHash(),value);
		return Lattice.ROOT.zero().assoc(Keywords.DATA,data);
	}

	@SuppressWarnings("unchecked")
	private static Index<Hash,ACell> dataIndex(Index<Keyword,ACell> root) {
		return (Index<Hash,ACell>) root.get(Keywords.DATA);
	}

	private static AString latticeValue(MatrixCase matrixCase) {
		return Strings.create("deterministic lattice value for Etch "+matrixCase.name());
	}

	private static AString detachedValue(MatrixCase matrixCase) {
		return Strings.create("detached Etch "+matrixCase.name()+" value "
				+"0123456789abcdef".repeat(8));
	}

	private static String matrixMessage(String operation, MatrixCase source, MatrixCase target) {
		return operation+" failed from Etch "+source.name()+" to "+target.name();
	}

	private record MatrixCase(String name, EtchConfig config) {
	}
}
