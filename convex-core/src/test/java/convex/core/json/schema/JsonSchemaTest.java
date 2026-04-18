package convex.core.json.schema;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

public class JsonSchemaTest {

	// ========== Type checking ==========

	@Test public void testTypeString() {
		AMap<AString, ACell> schema = Maps.of("type", "string");
		assertNull(JsonSchema.validate(schema, Strings.create("hello")));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(42)));
		assertNotNull(JsonSchema.validate(schema, null));
	}

	@Test public void testTypeNumber() {
		AMap<AString, ACell> schema = Maps.of("type", "number");
		assertNull(JsonSchema.validate(schema, CVMLong.create(42)));
		assertNull(JsonSchema.validate(schema, CVMDouble.create(3.14)));
		assertNotNull(JsonSchema.validate(schema, Strings.create("42")));
	}

	@Test public void testTypeInteger() {
		AMap<AString, ACell> schema = Maps.of("type", "integer");
		assertNull(JsonSchema.validate(schema, CVMLong.create(42)));
		assertNotNull(JsonSchema.validate(schema, CVMDouble.create(3.14)));
	}

	@Test public void testTypeBoolean() {
		AMap<AString, ACell> schema = Maps.of("type", "boolean");
		assertNull(JsonSchema.validate(schema, CVMBool.TRUE));
		assertNull(JsonSchema.validate(schema, CVMBool.FALSE));
		assertNotNull(JsonSchema.validate(schema, Strings.create("true")));
	}

	@Test public void testTypeNull() {
		AMap<AString, ACell> schema = Maps.of("type", "null");
		assertNull(JsonSchema.validate(schema, null));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(0)));
	}

	@Test public void testTypeObject() {
		AMap<AString, ACell> schema = Maps.of("type", "object");
		assertNull(JsonSchema.validate(schema, Maps.empty()));
		assertNotNull(JsonSchema.validate(schema, Vectors.empty()));
	}

	@Test public void testTypeArray() {
		AMap<AString, ACell> schema = Maps.of("type", "array");
		assertNull(JsonSchema.validate(schema, Vectors.empty()));
		assertNotNull(JsonSchema.validate(schema, Maps.empty()));
	}

	@Test public void testTypeBlob() {
		AMap<AString, ACell> schema = Maps.of("type", "blob");
		assertNull(JsonSchema.validate(schema, Blob.EMPTY));
		assertNotNull(JsonSchema.validate(schema, Strings.create("0x1234")));
	}

	@Test public void testTypeAddress() {
		AMap<AString, ACell> schema = Maps.of("type", "address");
		assertNull(JsonSchema.validate(schema, Address.create(42)));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(42)));
	}

	@Test public void testNoTypeAcceptsAnything() {
		AMap<AString, ACell> schema = Maps.of("description", "anything goes");
		assertNull(JsonSchema.validate(schema, Strings.create("hello")));
		assertNull(JsonSchema.validate(schema, CVMLong.create(42)));
		assertNull(JsonSchema.validate(schema, null));
	}

	@Test public void testEmptySchemaAcceptsAnything() {
		assertNull(JsonSchema.validate(Maps.empty(), Strings.create("hello")));
		assertNull(JsonSchema.validate(Maps.empty(), null));
	}

	// ========== Required fields ==========

	@Test public void testRequired() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"required", Vectors.of(Strings.create("name"), Strings.create("age"))
		);
		assertNull(JsonSchema.validate(schema, Maps.of("name", "Alice", "age", CVMLong.create(30))));
		String err = JsonSchema.validate(schema, Maps.of("name", "Alice"));
		assertNotNull(err);
		assertTrue(err.contains("age"), "Error should mention missing field: " + err);
	}

	// ========== Properties ==========

	@Test public void testNestedPropertyValidation() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"name", Maps.of("type", "string"),
				"age", Maps.of("type", "integer")
			)
		);
		assertNull(JsonSchema.validate(schema, Maps.of("name", "Alice", "age", CVMLong.create(30))));
		String err = JsonSchema.validate(schema, Maps.of("name", "Alice", "age", "thirty"));
		assertNotNull(err);
		assertTrue(err.contains("$.age"), "Error should include path: " + err);
	}

	// ========== additionalProperties ==========

	@Test public void testAdditionalPropertiesFalse() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of("name", Maps.of("type", "string")),
			"additionalProperties", CVMBool.FALSE
		);
		assertNull(JsonSchema.validate(schema, Maps.of("name", "Alice")));
		String err = JsonSchema.validate(schema, Maps.of("name", "Alice", "extra", "bad"));
		assertNotNull(err);
		assertTrue(err.contains("extra"), "Error should mention extra field: " + err);
	}

	// ========== Array items ==========

	@Test public void testArrayItems() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "array",
			"items", Maps.of("type", "integer")
		);
		assertNull(JsonSchema.validate(schema, Vectors.of(CVMLong.create(1), CVMLong.create(2))));
		String err = JsonSchema.validate(schema, Vectors.of(CVMLong.create(1), Strings.create("two")));
		assertNotNull(err);
		assertTrue(err.contains("[1]"), "Error should include array index: " + err);
	}

	@Test public void testArrayBounds() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "array",
			"minItems", CVMLong.create(2),
			"maxItems", CVMLong.create(4)
		);
		assertNull(JsonSchema.validate(schema, Vectors.of(CVMLong.create(1), CVMLong.create(2))));
		assertNotNull(JsonSchema.validate(schema, Vectors.of(CVMLong.create(1))));
	}

	// ========== Enum ==========

	@Test public void testEnum() {
		AMap<AString, ACell> schema = Maps.of(
			"enum", Vectors.of(Strings.create("APPROVED"), Strings.create("REJECTED"), Strings.create("ESCALATED"))
		);
		assertNull(JsonSchema.validate(schema, Strings.create("APPROVED")));
		assertNotNull(JsonSchema.validate(schema, Strings.create("INVALID")));
	}

	// ========== Const ==========

	@Test public void testConst() {
		AMap<AString, ACell> schema = Maps.of("const", CVMLong.create(42));
		assertNull(JsonSchema.validate(schema, CVMLong.create(42)));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(43)));
	}

	// ========== Numeric bounds ==========

	@Test public void testNumericBounds() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "number",
			"minimum", CVMLong.create(0),
			"maximum", CVMLong.create(100)
		);
		assertNull(JsonSchema.validate(schema, CVMLong.create(50)));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(-1)));
		assertNotNull(JsonSchema.validate(schema, CVMLong.create(101)));
	}

	// ========== String bounds ==========

	@Test public void testStringBounds() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "string",
			"minLength", CVMLong.create(3),
			"maxLength", CVMLong.create(10)
		);
		assertNull(JsonSchema.validate(schema, Strings.create("hello")));
		assertNotNull(JsonSchema.validate(schema, Strings.create("hi")));
		assertNotNull(JsonSchema.validate(schema, Strings.create("hello world!")));
	}

	// ========== Pattern ==========

	@Test public void testPattern() {
		AMap<AString, ACell> schema = Maps.of("type", "string", "pattern", "^INV-\\d+$");
		assertNull(JsonSchema.validate(schema, Strings.create("INV-12345")));
		assertNotNull(JsonSchema.validate(schema, Strings.create("PO-12345")));
	}

	// ========== Deep nesting ==========

	@Test public void testDeepNesting() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"vendor", Maps.of(
					"type", "object",
					"properties", Maps.of(
						"name", Maps.of("type", "string"),
						"status", Maps.of("type", "string", "enum",
							Vectors.of(Strings.create("ACTIVE"), Strings.create("SUSPENDED")))
					),
					"required", Vectors.of(Strings.create("name"), Strings.create("status"))
				)
			)
		);
		assertNull(JsonSchema.validate(schema, Maps.of(
			"vendor", Maps.of("name", "Acme Corp", "status", "ACTIVE"))));
		String err = JsonSchema.validate(schema, Maps.of(
			"vendor", Maps.of("name", "Acme Corp", "status", "INVALID")));
		assertNotNull(err);
		assertTrue(err.contains("$.vendor.status"), "Error path: " + err);
	}

	// ========== validateAll ==========

	@Test public void testValidateAllCollectsMultipleErrors() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"name", Maps.of("type", "string"),
				"age", Maps.of("type", "integer")
			),
			"required", Vectors.of(Strings.create("name"), Strings.create("age"))
		);
		AVector<AString> errors = JsonSchema.validateAll(schema, Maps.empty());
		assertTrue(errors.count() >= 2, "Should report at least 2 missing required fields");
	}

	// ========== infer ==========

	@Test public void testInferString() {
		AMap<AString, ACell> schema = JsonSchema.infer(Strings.create("hello"));
		assertEquals(Strings.create("string"), schema.get(Strings.create("type")));
	}

	@Test public void testInferObject() {
		AMap<AString, ACell> schema = JsonSchema.infer(
			Maps.of("name", "Alice", "age", CVMLong.create(30)));
		assertEquals(Strings.create("object"), schema.get(Strings.create("type")));
		assertNotNull(schema.get(Strings.create("properties")));
		assertNotNull(schema.get(Strings.create("required")));
	}

	@Test public void testInferRoundTrip() {
		ACell value = Maps.of(
			"vendor", "Acme Corp",
			"amount", CVMLong.create(5000),
			"items", Vectors.of(Maps.of("desc", "Widget", "qty", CVMLong.create(10)))
		);
		AMap<AString, ACell> schema = JsonSchema.infer(value);
		assertNull(JsonSchema.validate(schema, value), "Inferred schema should validate the source value");
	}

	@Test public void testInferBlob() {
		AMap<AString, ACell> schema = JsonSchema.infer(Blob.fromHex("deadbeef"));
		assertEquals(Strings.create("blob"), schema.get(Strings.create("type")));
	}

	@Test public void testInferAddress() {
		AMap<AString, ACell> schema = JsonSchema.infer(Address.create(42));
		assertEquals(Strings.create("address"), schema.get(Strings.create("type")));
	}

	// ========== checkSchema ==========

	@Test public void testMapGetBehavior() {
		// Verify Maps.of doesn't return phantom values for missing keys
		AMap<AString, ACell> m = Maps.of("type", "string");
		assertNull(m.get(Strings.intern("items")), "items key should not exist");
		assertNull(m.get(Strings.intern("properties")), "properties key should not exist");
		assertEquals(1, m.count());
	}

	@Test public void testCheckSchemaSimpleString() {
		assertNull(JsonSchema.checkSchema(Maps.of("type", "string")));
	}

	@Test public void testCheckSchemaObjectWithProperties() {
		AMap<AString, ACell> propSchema = Maps.of("type", "string");
		AMap<AString, ACell> props = Maps.of("name", propSchema);
		AMap<AString, ACell> schema = Maps.of("type", "object", "properties", props);
		assertNull(JsonSchema.checkSchema(schema));
	}

	@Test public void testCheckSchemaInvalidType() {
		AMap<AString, ACell> schema = Maps.of("type", "any");
		assertNotNull(JsonSchema.checkSchema(schema));
	}

	@Test public void testCheckSchemaNull() {
		assertNotNull(JsonSchema.checkSchema(null));
	}

	// ========== coerce ==========

	@Test public void testCoerceStringToNumber() {
		AMap<AString, ACell> schema = Maps.of("type", "number");
		ACell result = JsonSchema.coerce(schema, Strings.create("42.5"));
		assertEquals(CVMDouble.create(42.5), result);
	}

	@Test public void testCoerceStringToInteger() {
		AMap<AString, ACell> schema = Maps.of("type", "integer");
		ACell result = JsonSchema.coerce(schema, Strings.create("42"));
		assertEquals(CVMLong.create(42), result);
	}

	@Test public void testCoerceStringToBoolean() {
		AMap<AString, ACell> schema = Maps.of("type", "boolean");
		assertEquals(CVMBool.TRUE, JsonSchema.coerce(schema, Strings.create("true")));
		assertEquals(CVMBool.FALSE, JsonSchema.coerce(schema, Strings.create("false")));
	}

	@Test public void testCoerceNumberToString() {
		AMap<AString, ACell> schema = Maps.of("type", "string");
		ACell result = JsonSchema.coerce(schema, CVMLong.create(42));
		assertTrue(result instanceof AString);
	}

	@Test public void testCoerceStringToBlob() {
		AMap<AString, ACell> schema = Maps.of("type", "blob");
		ACell result = JsonSchema.coerce(schema, Strings.create("0xdeadbeef"));
		assertTrue(result instanceof ABlob, "Should coerce hex string to blob");
	}

	@Test public void testCoerceStringToAddress() {
		AMap<AString, ACell> schema = Maps.of("type", "address");
		ACell result = JsonSchema.coerce(schema, Strings.create("#42"));
		assertEquals(Address.create(42), result);
	}

	@Test public void testCoerceIntToAddress() {
		AMap<AString, ACell> schema = Maps.of("type", "address");
		ACell result = JsonSchema.coerce(schema, CVMLong.create(42));
		assertEquals(Address.create(42), result);
	}

	@Test public void testCoerceNestedObject() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"amount", Maps.of("type", "number"),
				"count", Maps.of("type", "integer")
			)
		);
		ACell input = Maps.of("amount", "42.5", "count", "7");
		ACell result = JsonSchema.coerce(schema, input);
		AMap<AString, ACell> map = (AMap<AString, ACell>) result;
		assertEquals(CVMDouble.create(42.5), map.get(Strings.create("amount")));
		assertEquals(CVMLong.create(7), map.get(Strings.create("count")));
	}

	@Test public void testCoerceNoOpWhenCorrect() {
		AMap<AString, ACell> schema = Maps.of("type", "string");
		AString value = Strings.create("already correct");
		assertSame(value, JsonSchema.coerce(schema, value), "Should return same object when no coercion needed");
	}

	// ========== Spec conformance: keywords without type ==========

	@Test public void testRequiredWithoutType() {
		// Per spec: required applies to objects even without "type": "object"
		AMap<AString, ACell> schema = Maps.of(
			"required", Vectors.of(Strings.create("name"))
		);
		// Object missing required field — should fail
		assertNotNull(JsonSchema.validate(schema, Maps.empty()),
			"required should apply to objects even without type declaration");
		// Object with required field — should pass
		assertNull(JsonSchema.validate(schema, Maps.of("name", "Alice")));
		// Non-object — required doesn't apply (spec §7.6.1: passes silently)
		assertNull(JsonSchema.validate(schema, Strings.create("hello")),
			"required should not apply to non-objects");
	}

	@Test public void testPropertiesWithoutType() {
		// properties should validate object fields even without type: object
		AMap<AString, ACell> schema = Maps.of(
			"properties", Maps.of("age", Maps.of("type", "integer"))
		);
		// Object with wrong type — should fail
		assertNotNull(JsonSchema.validate(schema, Maps.of("age", "not a number")));
		// Object with correct type — should pass
		assertNull(JsonSchema.validate(schema, Maps.of("age", CVMLong.create(30))));
		// Non-object — passes silently
		assertNull(JsonSchema.validate(schema, Strings.create("hello")));
	}

	@Test public void testItemsWithoutType() {
		// items should validate array elements even without type: array
		AMap<AString, ACell> schema = Maps.of(
			"items", Maps.of("type", "string")
		);
		assertNotNull(JsonSchema.validate(schema, Vectors.of(CVMLong.create(1))));
		assertNull(JsonSchema.validate(schema, Vectors.of(Strings.create("ok"))));
		// Non-array — passes silently
		assertNull(JsonSchema.validate(schema, Strings.create("hello")));
	}

	@Test public void testTypeAnyWithRequiredProperties() {
		// The critical case: "type": "any" with properties and required
		// After sanitising, should still enforce required and properties
		AMap<AString, ACell> schema = Maps.of(
			"type", "any",
			"properties", Maps.of("name", Maps.of("type", "string")),
			"required", Vectors.of(Strings.create("name"))
		);
		// Sanitise should infer type: object (since properties/required present)
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("object"), sanitised.get(Strings.intern("type")),
			"Sanitise should infer type: object when properties are present");
		// Validate with sanitised schema — required still enforced
		assertNotNull(JsonSchema.validate(sanitised, Maps.empty()),
			"Required should still be enforced after sanitising type: any");
		assertNull(JsonSchema.validate(sanitised, Maps.of("name", "Alice")));
		// Non-object should now fail (type: object enforced)
		assertNotNull(JsonSchema.validate(sanitised, Strings.create("hello")),
			"Type: object should reject non-objects after sanitisation");
	}

	@Test public void testTypeAnyBareSchema() {
		// "type": "any" with no other keywords → strip type, result is {}
		AMap<AString, ACell> schema = Maps.of("type", "any");
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertNull(sanitised.get(Strings.intern("type")),
			"Bare type: any should produce empty schema");
		// Empty schema accepts anything
		assertNull(JsonSchema.validate(sanitised, Strings.create("hello")));
		assertNull(JsonSchema.validate(sanitised, CVMLong.create(42)));
		assertNull(JsonSchema.validate(sanitised, Maps.empty()));
	}

	@Test public void testTypeAnyWithItems() {
		// "type": "any" with items → should infer type: array
		AMap<AString, ACell> schema = Maps.of(
			"type", "any",
			"items", Maps.of("type", "string")
		);
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("array"), sanitised.get(Strings.intern("type")),
			"Sanitise should infer type: array when items is present");
	}

	@Test public void testTypeAnyWithMinimum() {
		// "type": "any" with minimum → should infer type: number
		AMap<AString, ACell> schema = Maps.of(
			"type", "any", "minimum", CVMLong.create(0)
		);
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("number"), sanitised.get(Strings.intern("type")));
	}

	@Test public void testTypeAnyWithPattern() {
		// "type": "any" with pattern → should infer type: string
		AMap<AString, ACell> schema = Maps.of(
			"type", "any", "pattern", "^INV-\\d+$"
		);
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("string"), sanitised.get(Strings.intern("type")));
	}

	@Test public void testTypeAnyNestedInProperties() {
		// Property with type: any should become empty schema (any value for that field)
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"data", Maps.of("type", "any"),
				"name", Maps.of("type", "string")
			),
			"required", Vectors.of(Strings.create("name"), Strings.create("data"))
		);
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		// Validate: name required (string), data required (any type)
		assertNull(JsonSchema.validate(sanitised, Maps.of("name", "Alice", "data", CVMLong.create(42))),
			"data field should accept any type after sanitisation");
		assertNull(JsonSchema.validate(sanitised, Maps.of("name", "Alice", "data", "string too")));
		// Missing required data — should fail
		assertNotNull(JsonSchema.validate(sanitised, Maps.of("name", "Alice")),
			"data is required even though it accepts any type");
	}

	@Test public void testValidateAllWithoutType() {
		// validateAll should also apply keywords without type
		AMap<AString, ACell> schema = Maps.of(
			"required", Vectors.of(Strings.create("a"), Strings.create("b"))
		);
		AVector<AString> errors = JsonSchema.validateAll(schema, Maps.empty());
		assertEquals(2, errors.count(), "Should report both missing required fields");
	}

	// ========== sanitise ==========

	@Test public void testSanitiseCvmTypes() {
		AMap<AString, ACell> schema = Maps.of("type", "blob");
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("string"), sanitised.get(Strings.intern("type")),
			"CVM type 'blob' should be replaced with 'string'");
	}

	@Test public void testSanitiseInvalidType() {
		AMap<AString, ACell> schema = Maps.of("type", "any");
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertNull(sanitised.get(Strings.intern("type")),
			"Invalid type 'any' should be stripped");
	}

	@Test public void testSanitiseStandardTypeUnchanged() {
		AMap<AString, ACell> schema = Maps.of("type", "string");
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertSame(schema, sanitised, "Standard schema should be returned unchanged");
	}

	@Test public void testSanitiseStripCustomKeys() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "string", "secret", CVMBool.TRUE, "secretFields", Vectors.of(Strings.create("key")));
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema,
			Strings.intern("secret"), Strings.intern("secretFields"));
		assertNull(sanitised.get(Strings.intern("secret")));
		assertNull(sanitised.get(Strings.intern("secretFields")));
		assertEquals(Strings.create("string"), sanitised.get(Strings.intern("type")));
	}

	@Test public void testSanitiseNestedProperties() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"data", Maps.of("type", "blob"),
				"name", Maps.of("type", "string")
			)
		);
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> props = (AMap<AString, ACell>) sanitised.get(Strings.intern("properties"));
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> dataProp = (AMap<AString, ACell>) props.get(Strings.create("data"));
		assertEquals(Strings.create("string"), dataProp.get(Strings.intern("type")),
			"Nested CVM type should be sanitised");
	}

	@Test public void testSanitiseAddressType() {
		AMap<AString, ACell> schema = Maps.of("type", "address");
		AMap<AString, ACell> sanitised = JsonSchema.sanitise(schema);
		assertEquals(Strings.create("string"), sanitised.get(Strings.intern("type")));
	}

	// ========== Error message quality ==========

	@Test public void testErrorIncludesPath() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of(
				"items", Maps.of(
					"type", "array",
					"items", Maps.of("type", "number")
				)
			)
		);
		String err = JsonSchema.validate(schema, Maps.of(
			"items", Vectors.of(CVMLong.create(1), Strings.create("bad"), CVMLong.create(3))));
		assertNotNull(err);
		assertTrue(err.contains("$.items[1]"), "Should include full path: " + err);
	}
}
