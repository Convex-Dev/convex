package convex.tools;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Diagnostic tool: finds class initialisation cycles across the Convex modules.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A cycle in the class initialisation graph is a latent deadlock. If initialising
 * class {@code A} can trigger initialisation of {@code B} and vice versa, two threads
 * entering the two ends at the same moment each hold one initialisation monitor and
 * wait forever for the other. A single thread traversing the same cycle does not hang,
 * but silently observes the half-initialised class's static fields at their default
 * values — a quieter bug with the same root cause. Neither shows up in ordinary
 * single-threaded use, so a cycle can sit in the codebase a long time before a
 * parallel test run on a many-core machine finds it.</p>
 *
 * <p>Such a cycle is not visible in the source. The one found in August 2026 ran
 * {@code Utils.<clinit>} → {@code CVMLong} → {@code Cells.intern} → {@code Ref} →
 * {@code Hash} → {@code Utils}, where the {@code CVMLong → Ref} step happened inside
 * the body of a static method that {@code CVMLong} merely called; nothing in
 * {@code CVMLong}'s source mentions {@code Ref}. Finding these needs bytecode, and
 * needs the analysis to follow static calls out of {@code <clinit>}.</p>
 *
 * <h2>Running it</h2>
 *
 * <p>Deliberately a {@code main} rather than a JUnit test: it needs every module
 * already compiled, so it is run on demand after a build rather than during one.</p>
 *
 * <pre>{@code
 * ./mvnw -q -T1C install -DskipTests    # ensure every module's classes exist
 * java convex-integration/src/test/java/convex/tools/ClassInitCycles.java
 * }</pre>
 *
 * <p>It is run straight from source rather than compiled by Maven. The analysis uses
 * the JDK's {@code java.lang.classfile} API (Java 24+), while the build targets Java
 * 21 bytecode and CI still verifies a JDK 21 build, so compiling this file would break
 * that job. The source launcher compiles it in memory at the running JDK's level, which
 * for any Convex developer is 25 or later. The build excludes it from test compilation
 * for the same reason — see {@code convex-integration/pom.xml}.</p>
 *
 * <p>With no arguments it discovers every {@code convex-*}/{@code target/classes}
 * under the repository root. Pass directories explicitly to narrow the scan. Exits
 * non-zero when a cycle is found, so it can gate a script. It lives in
 * {@code convex-integration} because it reasons about the whole build, and because
 * that module is assembled last, by which point every module's classes exist.</p>
 *
 * <h2>What counts as an edge</h2>
 *
 * <p>An edge {@code A → B} means "initialising A can trigger initialisation of B".
 * Per JVMS 5.5 that is a {@code getstatic}, {@code putstatic} or {@code invokestatic}
 * whose owner is B, a {@code new B}, or B being A's superclass (or a superinterface
 * declaring a default method). The walk starts at {@code A.<clinit>} and follows
 * static methods and constructors it calls, transitively, because everything they do
 * happens while A's initialisation monitor is held.</p>
 *
 * <p><b>Known limits, all causing false negatives rather than false positives.</b>
 * Virtual and interface calls are not followed, since resolving them needs a
 * whole-program call graph. Lambda bodies reached via {@code invokedynamic} are not
 * followed. Reflection is not modelled. A clean report means "no cycle among the edges
 * we can see", which is strong evidence rather than proof.</p>
 */
public class ClassInitCycles {

	/** Parsed classes by internal name, e.g. {@code convex/core/util/Utils}. */
	private final Map<String, ClassModel> classes = new HashMap<>();

	/** Initialisation-trigger graph: class → classes its initialisation can trigger. */
	private final Map<String, Set<String>> edges = new LinkedHashMap<>();

	/** Why each edge exists: source → target → the path that produced it. */
	private final Map<String, Map<String, String>> reasons = new HashMap<>();

	/** Class → every other class whose code can trigger its initialisation. */
	private final Map<String, Set<String>> referencedFrom = new HashMap<>();

	public static void main(String[] args) throws Exception {
		// Tarjan recurses to the depth of the graph; give it room rather than tune it
		int[] status = new int[1];
		Thread worker = new Thread(null, () -> status[0] = run(args), "class-init-scan", 256L * 1024 * 1024);
		worker.start();
		worker.join();
		if (status[0] != 0) System.exit(status[0]);
	}

	private static int run(String[] args) {
		try {
			List<Path> roots = new ArrayList<>();
			if (args.length > 0) {
				for (String a : args) roots.add(Path.of(a));
			} else {
				roots.addAll(defaultRoots());
			}
			if (roots.isEmpty()) {
				System.err.println("No class directories found. Build first, or pass directories explicitly.");
				return 2;
			}
			ClassInitCycles scan = new ClassInitCycles();
			for (Path root : roots) scan.load(root);
			return scan.report(roots);
		} catch (IOException e) {
			System.err.println("Scan failed: " + e);
			return 2;
		}
	}

	/** Finds every compiled {@code convex-*} module under the repository root. */
	private static List<Path> defaultRoots() throws IOException {
		Path dir = Path.of("").toAbsolutePath();
		while ((dir != null) && !Files.isRegularFile(dir.resolve("convex-core/pom.xml"))) {
			dir = dir.getParent();
		}
		if (dir == null) return List.of();
		try (Stream<Path> s = Files.list(dir)) {
			List<Path> found = new ArrayList<>();
			s.filter(p -> p.getFileName().toString().startsWith("convex-"))
				.map(p -> p.resolve("target/classes"))
				.filter(Files::isDirectory)
				.sorted()
				.forEach(found::add);
			return found;
		}
	}

	private void load(Path root) throws IOException {
		try (Stream<Path> s = Files.walk(root)) {
			List<Path> files = new ArrayList<>();
			s.filter(f -> f.toString().endsWith(".class")).forEach(files::add);
			for (Path p : files) {
				try {
					ClassModel cm = ClassFile.of().parse(Files.readAllBytes(p));
					classes.put(cm.thisClass().asInternalName(), cm);
				} catch (IOException | RuntimeException e) {
					System.err.println("Skipped unreadable " + p + ": " + e);
				}
			}
		}
	}

	// ===== Graph construction =====

	private void buildGraph() {
		for (String name : classes.keySet()) edges.put(name, triggersOf(name));
	}

	/** Classes whose initialisation initialising {@code cls} can trigger. */
	private Set<String> triggersOf(String cls) {
		Set<String> out = new LinkedHashSet<>();
		ClassModel cm = classes.get(cls);
		if (cm == null) return out;

		// Initialising a class first initialises its superclass ...
		Optional<ClassEntry> superclass = cm.superclass();
		if (superclass.isPresent()) {
			add(cls, internalName(superclass.get()), out, "superclass of " + simple(cls));
		}

		// ... and any superinterface declaring a default method
		for (ClassEntry itf : cm.interfaces()) {
			String name = internalName(itf);
			ClassModel im = (name == null) ? null : classes.get(name);
			if ((im != null) && hasDefaultMethod(im)) {
				add(cls, name, out, "default-method interface of " + simple(cls));
			}
		}

		MethodModel clinit = lookup(cls, "<clinit>", "()V");
		if (clinit != null) {
			Deque<String> path = new ArrayDeque<>();
			path.addLast(simple(cls) + ".<clinit>");
			walk(cls, cls, clinit, out, new HashSet<>(), path);
		}
		return out;
	}

	/**
	 * Walks a method body reachable from {@code origin}'s initialisation, recording
	 * every class whose initialisation it can trigger.
	 */
	private void walk(String origin, String owner, MethodModel mm, Set<String> out,
			Set<String> visited, Deque<String> path) {
		if (!visited.add(owner + "." + mm.methodName().stringValue() + mm.methodType().stringValue())) return;

		Optional<CodeModel> code = mm.code();
		if (code.isEmpty()) return;

		for (CodeElement ce : code.get()) {
			if (ce instanceof FieldInstruction fi) {
				if ((fi.opcode() == Opcode.GETSTATIC) || (fi.opcode() == Opcode.PUTSTATIC)) {
					String target = internalName(fi.owner());
					add(origin, target, out, describe(path, fi.opcode(), target, fi.name().stringValue()));
				}
			} else if (ce instanceof NewObjectInstruction ni) {
				String target = internalName(ni.className());
				add(origin, target, out, describe(path, Opcode.NEW, target, ""));
			} else if (ce instanceof InvokeInstruction ii) {
				String target = internalName(ii.owner());
				String name = ii.name().stringValue();
				String desc = ii.type().stringValue();
				if (ii.opcode() == Opcode.INVOKESTATIC) {
					add(origin, target, out, describe(path, Opcode.INVOKESTATIC, target, name));
				}
				// A static call or a constructor runs its body inside our initialisation,
				// so whatever it touches is triggered by us too. Virtual and interface
				// calls would need a call graph to resolve, so they are left alone.
				if ((ii.opcode() == Opcode.INVOKESTATIC) || (ii.opcode() == Opcode.INVOKESPECIAL)) {
					String declaring = (target == null) ? null : resolveOwner(target, name, desc);
					MethodModel next = (declaring == null) ? null : lookup(declaring, name, desc);
					if ((next != null) && next.code().isPresent()) {
						path.addLast(simple(target) + "." + name);
						walk(origin, declaring, next, out, visited, path);
						path.removeLast();
					}
				}
			}
		}
	}

	private void add(String from, String to, Set<String> out, String reason) {
		if ((to == null) || to.equals(from) || !classes.containsKey(to)) return;
		if (out.add(to)) reasons.computeIfAbsent(from, k -> new HashMap<>()).putIfAbsent(to, reason);
	}

	private String describe(Deque<String> path, Opcode op, String owner, String member) {
		String verb = switch (op) {
			case GETSTATIC -> "getstatic";
			case PUTSTATIC -> "putstatic";
			case INVOKESTATIC -> "invokestatic";
			case NEW -> "new";
			default -> op.toString().toLowerCase();
		};
		String target = simple(owner) + (member.isEmpty() ? "" : ("." + member));
		return String.join(" -> ", path) + " -> " + verb + " " + target;
	}

	/** Finds the class actually declaring a method, searching up the superclass chain. */
	private String resolveOwner(String owner, String name, String desc) {
		String cls = owner;
		while (cls != null) {
			ClassModel cm = classes.get(cls);
			if (cm == null) return null;
			if (declared(cm, name, desc) != null) return cls;
			cls = cm.superclass().map(ClassInitCycles::internalName).orElse(null);
		}
		return null;
	}

	private MethodModel lookup(String cls, String name, String desc) {
		ClassModel cm = classes.get(cls);
		return (cm == null) ? null : declared(cm, name, desc);
	}

	private static MethodModel declared(ClassModel cm, String name, String desc) {
		for (MethodModel mm : cm.methods()) {
			if (mm.methodName().equalsString(name) && mm.methodType().equalsString(desc)) return mm;
		}
		return null;
	}

	private static boolean hasDefaultMethod(ClassModel cm) {
		for (MethodModel mm : cm.methods()) {
			if (mm.code().isPresent()
				&& !mm.flags().has(AccessFlag.STATIC)
				&& !mm.methodName().equalsString("<clinit>")) return true;
		}
		return false;
	}

	private static String internalName(ClassEntry ce) {
		String raw = ce.name().stringValue();
		// Array types never trigger initialisation of their component class
		return raw.startsWith("[") ? null : raw;
	}

	private static String simple(String internalName) {
		if (internalName == null) return "?";
		int i = internalName.lastIndexOf('/');
		return (i < 0) ? internalName : internalName.substring(i + 1);
	}

	/**
	 * Records, for every class, which other classes contain code able to trigger its
	 * initialisation — from any method, not only {@code <clinit>}.
	 *
	 * <p>This is what decides whether a cycle can actually deadlock. A deadlock needs
	 * two threads to enter the cycle at <em>different</em> classes, so a cycle whose
	 * members are all reached through a single entry point cannot deadlock however
	 * many threads race. That is the common and harmless case of a class whose
	 * {@code <clinit>} instantiates a nested subclass of itself.</p>
	 */
	private void indexReferences() {
		for (Map.Entry<String, ClassModel> entry : classes.entrySet()) {
			String from = entry.getKey();
			ClassModel cm = entry.getValue();

			// A subclass initialises its superclasses, so it is an entry point to them
			cm.superclass().map(ClassInitCycles::internalName)
				.ifPresent(sup -> reference(sup, from));

			for (MethodModel mm : cm.methods()) {
				Optional<CodeModel> code = mm.code();
				if (code.isEmpty()) continue;
				for (CodeElement ce : code.get()) {
					if (ce instanceof FieldInstruction fi) {
						if ((fi.opcode() == Opcode.GETSTATIC) || (fi.opcode() == Opcode.PUTSTATIC)) {
							reference(internalName(fi.owner()), from);
						}
					} else if (ce instanceof NewObjectInstruction ni) {
						reference(internalName(ni.className()), from);
					} else if (ce instanceof InvokeInstruction ii) {
						if (ii.opcode() == Opcode.INVOKESTATIC) reference(internalName(ii.owner()), from);
					}
				}
			}
		}
	}

	private void reference(String target, String from) {
		if ((target == null) || target.equals(from) || !classes.containsKey(target)) return;
		referencedFrom.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(from);
	}

	/** Cycle members that some class outside the cycle can initialise directly. */
	private List<String> entryPoints(Set<String> component) {
		List<String> entries = new ArrayList<>();
		for (String member : component) {
			for (String source : referencedFrom.getOrDefault(member, Set.of())) {
				if (!component.contains(source)) {
					entries.add(member);
					break;
				}
			}
		}
		return entries;
	}

	// ===== Cycle detection (Tarjan) =====

	private int index = 0;
	private final Map<String, Integer> indices = new HashMap<>();
	private final Map<String, Integer> lowlink = new HashMap<>();
	private final Deque<String> stack = new ArrayDeque<>();
	private final Set<String> onStack = new HashSet<>();
	private final List<List<String>> components = new ArrayList<>();

	private void strongConnect(String v) {
		indices.put(v, index);
		lowlink.put(v, index);
		index++;
		stack.push(v);
		onStack.add(v);

		for (String w : edges.getOrDefault(v, Set.of())) {
			if (!indices.containsKey(w)) {
				strongConnect(w);
				lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
			} else if (onStack.contains(w)) {
				lowlink.put(v, Math.min(lowlink.get(v), indices.get(w)));
			}
		}

		if (lowlink.get(v).equals(indices.get(v))) {
			List<String> component = new ArrayList<>();
			String w;
			do {
				w = stack.pop();
				onStack.remove(w);
				component.add(w);
			} while (!w.equals(v));
			if (component.size() > 1) components.add(component);
		}
	}

	/** Finds a concrete cycle through {@code start}, staying inside one component. */
	private List<String> cycleThrough(String start, Set<String> component) {
		Map<String, String> previous = new HashMap<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			String v = queue.poll();
			for (String w : edges.getOrDefault(v, Set.of())) {
				if (!component.contains(w)) continue;
				if (w.equals(start)) {
					List<String> cycle = new ArrayList<>();
					for (String at = v; at != null; at = previous.get(at)) cycle.add(at);
					Collections.reverse(cycle);
					cycle.add(start);
					return cycle;
				}
				if (previous.putIfAbsent(w, v) == null) queue.add(w);
			}
		}
		return List.of();
	}

	// ===== Reporting =====

	private int report(List<Path> roots) {
		buildGraph();
		indexReferences();

		int withClinit = 0;
		int edgeCount = 0;
		for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
			if (lookup(e.getKey(), "<clinit>", "()V") != null) withClinit++;
			edgeCount += e.getValue().size();
		}

		System.out.println("Class initialisation cycle scan");
		for (Path root : roots) System.out.println("  scanned " + root);
		System.out.println("  " + classes.size() + " classes, " + withClinit
			+ " with a static initialiser, " + edgeCount + " initialisation edges");
		System.out.println();

		for (String v : edges.keySet()) {
			if (!indices.containsKey(v)) strongConnect(v);
		}

		if (components.isEmpty()) {
			System.out.println("No initialisation cycles found.");
			System.out.println("(Virtual calls, lambda bodies and reflection are not followed,");
			System.out.println(" so this is strong evidence rather than proof.)");
			return 0;
		}

		List<List<String>> dangerous = new ArrayList<>();
		List<List<String>> single = new ArrayList<>();
		for (List<String> component : components) {
			Set<String> members = new LinkedHashSet<>(component);
			(entryPoints(members).size() > 1 ? dangerous : single).add(component);
		}

		if (!dangerous.isEmpty()) {
			System.out.println(dangerous.size() + " CYCLE(S) THAT CAN DEADLOCK");
			System.out.println("(more than one member is reachable independently, so two threads");
			System.out.println(" can enter at different points and block each other):");
			for (List<String> component : dangerous) describe(component);
			System.out.println();
		}

		if (!single.isEmpty()) {
			System.out.println(single.size() + " cycle(s) with a single entry point - cannot deadlock,");
			System.out.println("but a thread traversing one sees half-initialised statics:");
			for (List<String> component : single) describe(component);
			System.out.println();
		}

		System.out.println("Note: this analysis is flow-insensitive. An edge on a branch that never");
		System.out.println("runs (e.g. an error path) is still reported, so confirm each cycle before");
		System.out.println("acting. Virtual calls, lambdas and reflection are not followed at all.");
		return dangerous.isEmpty() ? 0 : 1;
	}

	private void describe(List<String> component) {
		Set<String> members = new LinkedHashSet<>(component);
		List<String> cycle = cycleThrough(component.get(0), members);
		System.out.println();
		System.out.println("  cycle of " + component.size() + " classes; entry points: "
			+ entryPoints(members).stream().map(ClassInitCycles::simple).sorted().toList());
		for (int i = 0; (i + 1) < cycle.size(); i++) {
			String from = cycle.get(i);
			String to = cycle.get(i + 1);
			System.out.println("    " + from + "  ->  " + to);
			String reason = reasons.getOrDefault(from, Map.of()).get(to);
			if (reason != null) System.out.println("        via " + reason);
		}
	}
}
