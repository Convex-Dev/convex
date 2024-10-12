package convex.core.data.type;

import convex.core.data.ACell;

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
	public static final Integer INTEGER=Integer.INSTANCE;
	public static final Double DOUBLE = Double.INSTANCE;
	public static final Number NUMBER = Number.INSTANCE;
	
	// Atomic types
	public static final Boolean BOOLEAN = Boolean.INSTANCE;
	public static final CharacterType CHARACTER = CharacterType.INSTANCE;

	// Named types
	public static final KeywordType KEYWORD = KeywordType.INSTANCE;
	public static final SymbolType SYMBOL = SymbolType.INSTANCE;
	public static final StringType STRING = StringType.INSTANCE;
	
	// Data Structures
	public static final DataStructure DATA_STRUCTURE = DataStructure.INSTANCE;
	public static final Record RECORD = Record.INSTANCE;
	public static final Map MAP = Map.INSTANCE;
	public static final Sequence SEQUENCE = Sequence.INSTANCE;

	public static final IndexType INDEX = IndexType.INSTANCE;

	public static final Blob BLOB = Blob.INSTANCE;
	public static final AddressType ADDRESS = AddressType.INSTANCE;

	
	public static final Function FUNCTION = Function.INSTANCE;
	public static final OpCode OP = OpCode.INSTANCE;
	public static final SyntaxType SYNTAX=SyntaxType.INSTANCE;
	
	public static final Transaction TRANSACTION=Transaction.INSTANCE;
	
	public static final Countable COUNTABLE = Countable.INSTANCE;
	public static final CAD3Type CAD3 = CAD3Type.INSTANCE;



	public static AType[] ALL_TYPES=new AType[] {
		NIL,
		ANY,
		
		COLLECTION,
		DATA_STRUCTURE,
		RECORD,
		SEQUENCE,
		VECTOR,
		MAP,
		LIST,
		SET,
		INDEX,
		
		NUMBER,
		LONG,
		INTEGER,
		DOUBLE,
		
		BOOLEAN,
		CHARACTER,
		STRING,
		KEYWORD,
		SYMBOL,
		
		BLOB,
		ADDRESS,
		
		FUNCTION,
		OP,
		SYNTAX,
	};



	public static AType get(ACell data) {
		if (data==null) return Types.NIL;
		return data.getType();
	}
}
