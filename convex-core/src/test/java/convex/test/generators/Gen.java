package convex.test.generators;

public class Gen {

	public static final AddressGen ADDRESS=new AddressGen();
	
	public static final BlobGen BLOB=new BlobGen();

	public static final StringGen STRING = new StringGen();

	public static final NumericGen NUMERIC=new NumericGen();

	public static final DoubleGen DOUBLE = new DoubleGen();

	public static final LongGen LONG = new LongGen();

	public static final IntegerGen INTEGER = new IntegerGen();

	public static final PrimitiveGen PRIMITIVE = new PrimitiveGen();

	public static final ValueGen VALUE = new ValueGen();

	public static final HashMapGen HASHMAP = new HashMapGen();

	public static final VectorGen VECTOR = new VectorGen();

	public static final FormGen FORM = new FormGen();

	public static final KeywordGen KEYWORD = new KeywordGen();

	public static final ListGen LIST = new ListGen();

	public static final RecordGen RECORD = new RecordGen();

	public static final SymbolGen SYMBOL = new SymbolGen();

	public static final SetGen SET = new SetGen();

	public static final CharGen CHAR = new CharGen();

	public static final TransactionGen TRANSACTION = new TransactionGen();

	public static final BooleanGen BOOLEAN = new BooleanGen();

	public static final ByteFlagGen BYTE_FLAG = new ByteFlagGen();
	
	public static final DenseRecordGen DENSE_RECORD = new DenseRecordGen();

	public static final OpGen OP = new OpGen();

	public static final ExtensionValueGen EXTENSION_VALUE = new ExtensionValueGen();

	public static final CodedValueGen CODED_VALUE = new CodedValueGen();
	
	public static final SyntaxGen SYNTAX = new SyntaxGen();
}
