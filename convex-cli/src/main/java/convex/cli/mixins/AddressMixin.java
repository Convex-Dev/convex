package convex.cli.mixins;

import convex.cli.CLIError;
import convex.core.data.Address;
import picocli.CommandLine.Option;

public class AddressMixin extends AMixin {
	
	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Can specify with CONVEX_ADDRESS environment variable. with Defaulting to: ${DEFAULT-VALUE}")
	protected String addressValue = null;

	public Address getAddress(String prompt) {
		if (addressValue==null) {
			if ((prompt!=null)&&isInteractive()) {
				addressValue=prompt(prompt);
			} else {
				throw new CLIError("You must specify an --address argument");
			}
		} 
		
		Address a = Address.parse(addressValue);
		if (a==null) {
			throw new CLIError("Unable to parse --address argument. Should be a numerical address like '#789'. '#' is optional.");
		}
		return a;
	}
	

}
