package convex.api;

public class Applications {

	/**
	 * Helper function to launch a different JVM process with the same classpath.
	 * 
	 * @param c Main class to launch
	 * @param args Command line args for launched process
	 * @return Process instance that can be used to observe exit value etc.
	 * @throws Exception 
	 */
	public static Process launchApp(Class<?> c, String... args) throws Exception {
		// construct path to java executable
		String separator = System.getProperty("file.separator");
		String classpath = System.getProperty("java.class.path");
		String path = System.getProperty("java.home") + separator + "bin" + separator + "java";
		
		// construct process arguments
		String mainClassName=c.getName();
		int nargs=args.length;
		String[] pargs=new String[4+nargs];
		pargs[0]=path;
		pargs[1]="-cp";
		pargs[2]=classpath;
		pargs[3]=mainClassName;
		System.arraycopy(args, 0, pargs, 4, nargs);
		ProcessBuilder processBuilder = new ProcessBuilder(pargs);
		
		// Execute process and return Process instance
		// Calling code can use Process.wairFor(), exitValue() etc.
		Process process = processBuilder.start();
		return process;
	}
}
