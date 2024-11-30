package convex.cli.mixins;

import convex.cli.CLIError;
import convex.core.cvm.Address;
import picocli.CommandLine.Option;

public class AddressMixin extends AMixin {
	
	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Can specify with CONVEX_ADDRESS environment variable.")
	protected String addressValue = null;

	private Address address=null;
	
	/*
	 * Get user address
	 */
	public Address getAddress(String prompt) {
		if (address!=null) return address;
		
		if (addressValue==null) {
			if ((prompt!=null)&&isInteractive()) {
				addressValue=prompt(prompt);
			} else {
				throw new CLIError("You must specify an --address argument");
			}
		} 
		
		address = Address.parse(addressValue);
		return address;
	}
	

}
