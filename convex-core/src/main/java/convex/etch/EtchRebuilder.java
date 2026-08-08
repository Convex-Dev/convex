package convex.etch;

import static convex.etch.EtchConstants.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.util.FileUtils;
import convex.core.util.Utils;

/**
 * Offline reconstruction of an Etch source into a fresh, independently valid
 * destination.
 *
 * <p>The source is held under an exclusive lock and is never modified. Every
 * recovered cell is accepted only when its declared CAD3 encoding is canonical
 * and its SHA3-256 content hash equals the stored key. Mutable source labels are
 * ignored; recovered cells enter the destination at {@link Ref#STORED} and only
 * the fully verified root tree is promoted to persisted status.</p>
 */
public final class EtchRebuilder {
	private static final int RECORD_HEADER_SIZE=KEY_SIZE+LABEL_SIZE+ENCODING_LENGTH_SIZE;
	private static final int MAX_RECORD_SIZE=RECORD_HEADER_SIZE+Format.LIMIT_ENCODING_LENGTH;
	private static final int SCAN_WINDOW_SIZE=1<<20;
	private static final int MAX_PROBLEMS=100;

	private EtchRebuilder() {
	}

	/** Reconstruction outcome for a valid, fully synced destination file. */
	public enum Status {
		/** Root complete and the physical source scan reached EOF. */
		COMPLETE,
		/** Root complete, but an I/O failure prevented an exhaustive physical scan. */
		ROOT_RECOVERED,
		/** Some valid cells were recovered, but the selected root is incomplete. */
		PARTIAL
	}

	/** Immutable summary of a reconstruction attempt. */
	public record Result(Status status, File source, File destination, Hash sourceRoot,
			long sourcePhysicalBytes, long indexedRecordsAccepted,
			long scannedRecordsAccepted, long destinationValues, long indexProblems,
			long bytesScanned, boolean exhaustiveScan, List<Hash> missingRootHashes,
			List<String> problems) {
		public Result {
			status=Objects.requireNonNull(status,"status");
			source=Objects.requireNonNull(source,"source");
			destination=Objects.requireNonNull(destination,"destination");
			sourceRoot=Objects.requireNonNull(sourceRoot,"sourceRoot");
			missingRootHashes=List.copyOf(missingRootHashes);
			problems=List.copyOf(problems);
		}

		public boolean isRootComplete() {
			return missingRootHashes.isEmpty();
		}
	}

	/** Rebuilds using source-equivalent destination format and cipher options. */
	public static Result rebuild(File source, File destination) throws IOException {
		return rebuild(source,null,destination,null);
	}

	/**
	 * Rebuilds an Etch source into a fresh destination.
	 *
	 * @param source existing source, opened exclusively and never modified
	 * @param sourceConfig source options including any required key function
	 * @param destination absent or empty destination file
	 * @param destinationConfig destination options; {@code null} preserves the
	 *        source version, cipher, index-encryption and public-key-hint options
	 */
	public static Result rebuild(File source, EtchConfig sourceConfig, File destination,
			EtchConfig destinationConfig) throws IOException {
		Objects.requireNonNull(source,"source");
		Objects.requireNonNull(destination,"destination");
		File sourceFile=source.getCanonicalFile();
		File destinationFile=destination.getCanonicalFile();
		if (sourceFile.equals(destinationFile)) {
			throw new IOException("Etch repair source and destination are the same file: "+sourceFile);
		}
		if (destinationFile.exists()&&(destinationFile.length()!=0L)) {
			throw new IOException("Etch repair destination is not empty: "+destinationFile);
		}
		destinationFile=FileUtils.ensureFilePath(destinationFile);

		try (EtchMaintenanceReader reader=EtchMaintenanceReader.openExclusive(
				sourceFile,sourceConfig)) {
			EtchConfig outputConfig=(destinationConfig==null)
					?reader.getConfig():destinationConfig;
			EtchStore destinationStore=new EtchStore(Etch.create(destinationFile,outputConfig));
			RebuildState state=new RebuildState(reader,destinationStore);
			try {
				state.walkIndex(0,reader.getIndexStart());
				state.scanBody();

				List<Hash> missing=EtchUtils.verify(destinationStore.getEtch(),reader.getRootHash());
				if (missing.isEmpty()) state.publishRoot(reader.getRootHash());
				destinationStore.flush();
			} finally {
				destinationStore.close();
			}

			return state.verifyDestination(destinationFile,outputConfig);
		}
	}

	private static final class RebuildState {
		private final EtchMaintenanceReader source;
		private final EtchStore destination;
		private final HashSet<Long> visitedIndexes=new HashSet<>();
		private final ArrayList<String> problems=new ArrayList<>();
		private final byte[] recordHeader=new byte[RECORD_HEADER_SIZE];
		private long indexProblems;
		private long indexedRecordsAccepted;
		private long scannedRecordsAccepted;
		private long bytesScanned;
		private boolean exhaustiveScan=true;

		private RebuildState(EtchMaintenanceReader source, EtchStore destination) {
			this.source=source;
			this.destination=destination;
		}

		private void walkIndex(int level, long indexPosition) {
			if (level>=MAX_LEVEL) {
				indexProblem("Index depth exceeds "+MAX_LEVEL+" at "+indexPosition);
				return;
			}
			if (!visitedIndexes.add(indexPosition)) {
				indexProblem("Repeated or cyclic child index at "+indexPosition);
				return;
			}

			int slotCount=indexSize(level);
			int blockLength=slotCount*POINTER_SIZE;
			long blockEnd;
			try {
				blockEnd=Math.addExact(indexPosition,blockLength);
			} catch (ArithmeticException e) {
				indexProblem("Overflowing index extent at "+indexPosition);
				return;
			}
			if ((indexPosition<source.getIndexStart())
					||(blockEnd>source.getPhysicalFileEnd())
					||((level>0)&&((indexPosition&(POINTER_SIZE-1L))!=0L))) {
				indexProblem("Invalid index extent at "+indexPosition+" level "+level);
				return;
			}

			byte[] block=new byte[blockLength];
			try {
				source.readIndex(indexPosition,block,0,block.length);
			} catch (IOException | RuntimeException | Error e) {
				if (e instanceof VirtualMachineError fatal) throw fatal;
				indexProblem("Cannot read index at "+indexPosition+": "+e.getMessage());
				return;
			}

			for (int i=0;i<slotCount;i++) {
				long slot=Utils.readLong(block,i*POINTER_SIZE,POINTER_SIZE);
				if (slot==0L) continue;
				long type=slot&POINTER_TYPE_MASK;
				long pointer=slot&~POINTER_TYPE_MASK;
				boolean plausible=plausiblePointer(type,pointer,level);

				if (type==POINTER_INDEX) {
					if (plausible) walkIndex(level+1,pointer);
				} else if (plausible) {
					tryRecoverRecord(pointer,true);
				}
			}
		}

		private boolean plausiblePointer(long type, long pointer, int level) {
			if ((pointer<source.getBodyStart())||(pointer>=source.getPhysicalFileEnd())) {
				indexProblem("Index pointer outside the body: "+pointer);
				return false;
			}
			if (type==POINTER_INDEX) {
				if ((level+1>=MAX_LEVEL)||((pointer&(POINTER_SIZE-1L))!=0L)) {
					indexProblem("Invalid child-index pointer: "+pointer);
					return false;
				}
				long end;
				try {
					end=Math.addExact(pointer,(long)indexSize(level+1)*POINTER_SIZE);
				} catch (ArithmeticException e) {
					indexProblem("Overflowing child-index pointer: "+pointer);
					return false;
				}
				if (end>source.getPhysicalFileEnd()) {
					indexProblem("Child index exceeds physical EOF: "+pointer);
					return false;
				}
			} else if (pointer>source.getPhysicalFileEnd()-RECORD_HEADER_SIZE) {
				indexProblem("Data pointer has no complete record header: "+pointer);
				return false;
			}
			return true;
		}

		private void scanBody() {
			long position=source.getBodyStart();
			long physicalEnd=source.getPhysicalFileEnd();
			byte[] raw=new byte[SCAN_WINDOW_SIZE+MAX_RECORD_SIZE];
			byte[] clear=new byte[raw.length];

			while (position<physicalEnd) {
				int startSpan=(int)Math.min(SCAN_WINDOW_SIZE,physicalEnd-position);
				int readLength=(int)Math.min((long)startSpan+MAX_RECORD_SIZE,
						physicalEnd-position);
				try {
					source.readRaw(position,raw,0,readLength);
					if (allZero(raw,readLength)) {
						position+=startSpan;
						bytesScanned+=startSpan;
						continue;
					}
					source.readData(position,clear,0,readLength);
				} catch (IOException | RuntimeException | Error e) {
					if (e instanceof VirtualMachineError fatal) throw fatal;
					exhaustiveScan=false;
					problem("Physical scan stopped at "+position+": "+e.getMessage());
					return;
				}

				int offset=0;
				while (offset<startSpan) {
					int available=readLength-offset;
					if (available<RECORD_HEADER_SIZE) break;
					int encodingLength=Utils.readShort(clear,offset+KEY_SIZE+LABEL_SIZE);
					int recordLength=RECORD_HEADER_SIZE+encodingLength;
					if ((encodingLength<=0)||(encodingLength>Format.LIMIT_ENCODING_LENGTH)
							||(recordLength>available)
							||!plausibleTag(clear[offset+RECORD_HEADER_SIZE])) {
						offset++;
						continue;
					}

					Hash stored=Hash.wrap(clear,offset);
					Blob encoding=Blob.wrap(clear,offset+RECORD_HEADER_SIZE,encodingLength);
					if (!stored.equals(Hashing.sha3(encoding))) {
						offset++;
						continue;
					}
					if (copyEncoding(stored,Blob.create(clear,
							offset+RECORD_HEADER_SIZE,encodingLength))) {
						scannedRecordsAccepted++;
						offset+=recordLength;
					} else {
						offset++;
					}
				}
				long consumed=Math.max(startSpan,offset);
				position+=consumed;
				bytesScanned+=consumed;
			}
		}

		private void tryRecoverRecord(long position, boolean indexed) {
			try {
				source.readData(position,recordHeader,0,recordHeader.length);
				int length=Utils.readShort(recordHeader,KEY_SIZE+LABEL_SIZE);
				if ((length<=0)||(length>Format.LIMIT_ENCODING_LENGTH)) {
					indexProblem("Invalid indexed record length at "+position+": "+length);
					return;
				}
				byte[] encodingBytes=new byte[length];
				source.readData(position+RECORD_HEADER_SIZE,encodingBytes,0,length);
				Hash stored=Hash.wrap(recordHeader,0);
				Blob encoding=Blob.wrap(encodingBytes);
				if (!stored.equals(Hashing.sha3(encoding))||!copyEncoding(stored,encoding)) {
					indexProblem("Invalid indexed record at "+position);
					return;
				}
				if (indexed) indexedRecordsAccepted++;
			} catch (IOException | RuntimeException | Error e) {
				if (e instanceof VirtualMachineError fatal) throw fatal;
				indexProblem("Cannot recover indexed record at "+position+": "+e.getMessage());
			}
		}

		private boolean copyEncoding(Hash expectedHash, Blob encoding) {
			try {
				ACell cell=destination.decode(encoding);
				if (!expectedHash.equals(Hash.get(cell))) return false;
				Blob canonical=(cell==null)?Blob.NULL_ENCODING:cell.getEncoding();
				if (!canonical.equals(encoding)) return false;
				destination.storeTopRef(Ref.get(cell),Ref.STORED,null);
				return true;
			} catch (BadFormatException | IOException | RuntimeException | Error e) {
				if (e instanceof VirtualMachineError fatal) throw fatal;
				return false;
			}
		}

		private void publishRoot(Hash rootHash) throws IOException {
			Etch etch=destination.getEtch();
			if (Hash.UNSET_HASH.equals(rootHash)) return;
			if (Hash.NULL_HASH.equals(rootHash)||Hash.EMPTY_HASH.equals(rootHash)) {
				etch.setRootHash(rootHash);
				return;
			}
			Ref<ACell> root=etch.read(rootHash);
			if (root==null) throw new IOException("Verified recovery root disappeared: "+rootHash);
			destination.setRootData(root.getValue());
		}

		private Result verifyDestination(File destinationFile, EtchConfig outputConfig)
				throws IOException {
			List<Hash> missing;
			long values;
			Etch rebuilt=Etch.create(destinationFile,outputConfig);
			EtchStore rebuiltStore=new EtchStore(rebuilt);
			try {
				Hash sourceRoot=source.getRootHash();
				missing=EtchUtils.verify(rebuilt,sourceRoot);
				Hash expectedRoot=missing.isEmpty()?sourceRoot:Hash.UNSET_HASH;
				if (!expectedRoot.equals(rebuilt.getRootHash())) {
					throw new IOException("Rebuilt Etch root does not match its recovery result");
				}
				EtchUtils.FullValidator validator=EtchUtils.getFullValidator();
				rebuilt.visitIndex(validator);
				values=validator.values;
			} finally {
				rebuiltStore.close();
			}

			Status status=missing.isEmpty()
					?(exhaustiveScan?Status.COMPLETE:Status.ROOT_RECOVERED)
					:Status.PARTIAL;
			return new Result(status,source.getFile(),destinationFile,source.getRootHash(),
					source.getPhysicalFileEnd(),indexedRecordsAccepted,scannedRecordsAccepted,
					values,indexProblems,bytesScanned,exhaustiveScan,missing,problems);
		}

		private void indexProblem(String message) {
			indexProblems++;
			problem(message);
		}

		private void problem(String message) {
			if (problems.size()<MAX_PROBLEMS) problems.add(message);
		}
	}

	private static int indexSize(int level) {
		if (level==0) return ROOT_INDEX_SIZE;
		if (level==1) return SECOND_LEVEL_INDEX_SIZE;
		return DEEP_INDEX_SIZE;
	}

	private static boolean plausibleTag(byte tag) {
		int unsigned=tag&0xff;
		if (unsigned==0) return true;
		return switch (unsigned>>>4) {
			case 1,3,8,9,11,12,13,14 -> true;
			default -> false;
		};
	}

	private static boolean allZero(byte[] data, int length) {
		for (int i=0;i<length;i++) {
			if (data[i]!=0) return false;
		}
		return true;
	}
}
