package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.logging.Logger;


import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.IDefaultValueProvider;



class DefaultValueReader implements IDefaultValueProvider {


	public String defaultValue(ArgSpec argSpec) throws Exception {
		System.out.print(argSpec);
		System.out.print(" command name: " + argSpec.command().name());
		System.out.print(" default value: " + argSpec.defaultValue());
		System.out.println(" label " + argSpec.paramLabel());
		return null;
	}

}
