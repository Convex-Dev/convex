package convex.core.data.type;

/**
 * Static base class for Type system functionality
 * 
 * NOTE: Currently Types are not planned for support in 1.0 runtime, but included here to support testing
 * 
 */
public class Types {
	// Fundamental types
	public static final Nil NIL=Nil.INSTANCE;
	public static final Any ANY = Any.INSTANCE;
	
	// Collection types
	public static final Collection COLLECTION=Collection.INSTANCE;
	public static final Vector VECTOR=Vector.INSTANCE;
	public static final List LIST=List.INSTANCE;
	public static final Set SET=Set.INSTANCE;
	
	// Numeric types
	public static final Long LONG=Long.INSTANCE;
	public static final Byte BYTE = Byte.INSTANCE;
	public static final Double DOUBLE = Double.INSTANCE;
	public static final Number NUMBER = Number.INSTANCE;
	
	// Atomic types
	public static final Boolean BOOLEAN = Boolean.INSTANCE;
	
	
	public static AType[] ALL_TYPES=new AType[] {
		NIL,
		ANY,
		COLLECTION,
		VECTOR,
		LIST,
		SET,
		LONG,
		BYTE,
		DOUBLE,
		NUMBER,
		BOOLEAN
	};
}
