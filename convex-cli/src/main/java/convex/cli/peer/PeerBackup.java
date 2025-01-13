package convex.cli.peer;

import java.io.File;
import java.io.IOException;
import java.util.List;

import convex.cli.CLIError;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.util.FileUtils;
import convex.core.util.Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
		name = "backup",
		mixinStandardHelpOptions = true, 
		description = "Backup stored data for a peer")
public class PeerBackup extends APeerCommand {
	
	@Option(names = { "--output-file" }, 
			description = "Output file for peer CAD3 data. Defaults to timestamped CAD3 file.")
	private String outFile;


	@SuppressWarnings("resource")
	@Override
	protected void execute() throws InterruptedException {

		AccountKey k= peerKeyMixin.getAcountKey();
		
		if (k==null) {
			List<AccountKey> peerList=etchMixin.getPeerList();
			int n=peerList.size();
			if (n==0) {
				throw new CLIError("No peers available in store: "+etchMixin.getEtchStore());
			}
			
			String s=peerKeyMixin.getPublicKey();
			if (s!=null) {
				peerList.removeIf(pk->!pk.toHexString().startsWith(s));
				n=peerList.size();
			}
			
			if (n==0) {
				throw new CLIError("No peer in store with prefix: "+s);
			} if (n==1) {
				k=peerList.get(0);
			} else if (n==0) {
				throw new CLIError("No peers available in store: "+etchMixin.getEtchStore());
			} else {
				informWarning("Need to select peer to backup, available peers are:");
				for (AccountKey pk: peerList) {
					inform(pk.toHexString());
				}
				
				throw new CLIError("Please specify which peer to backup with --peer-key");
			}
		}
		
		AMap<Keyword,ACell> peerData;
		try {
			peerData=convex.core.cvm.Peer.getPeerData(etchMixin.getEtchStore(), k);
			if (peerData==null) {
				throw new CLIError("No peer data found for key: "+k);
			}
		} catch (Exception e) {
			throw new CLIError("Unable to access peers data",e);
		}
		
		if (outFile==null) {
			outFile="peer-backup-"+k.toHexString(8)+"-"+Utils.timeString()+".cad3";
		}
		File f=FileUtils.getFile(outFile);
		
		try {
			FileUtils.writeCAD3(f.toPath(), peerData);
			inform("Peer data written to: "+f);
		} catch (IOException e) {
			throw new CLIError("Unable to write peer data",e);
		}
	}

}
