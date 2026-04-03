package convex.core.json.schema;

import convex.core.cvm.Address;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Lightweight, ACell-native JSON Schema validation, inference, and coercion.
 *
 * <p>Supports the subset of JSON Schema draft 2020-12 used in Convex/Covia
 * operation metadata, plus CVM type extensions (blob, address, hash, accountKey).</p>
 *
 * <p>No external dependencies — operates entirely on CVM data types.</p>
 *
 * @see <a href="https://json-schema.org/draft/2020-12/json-schema-core">JSON Schema 2020-12</a>
 */
public class JsonSchema {

	/** Safe map extraction — returns null for null cells (unlike RT.ensureMap which returns empty map) */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}

	// Schema keyword constants
	private static final AString K_TYPE = Strings.intern("type");
	private static final AString K_PROPERTIES = Strings.intern("properties");
	private static final AString K_REQUIRED = Strings.intern("required");
	private static final AString K_ADDITIONAL_PROPERTIES = Strings.intern("additionalProperties");
	private static final AString K_ITEMS = Strings.intern("items");
	private static final AString K_ENUM = Strings.intern("enum");
	private static final AString K_CONST = Strings.intern("const");
	private static final AString K_MINIMUM = Strings.intern("minimum");
	private static final AString K_MAXIMUM = Strings.intern("maximum");
	private static final AString K_MIN_LENGTH = Strings.intern("minLength");
	private static final AString K_MAX_LENGTH = Strings.intern("maxLength");
	private static final AString K_MIN_ITEMS = Strings.intern("minItems");
	private static final AString K_MAX_ITEMS = Strings.intern("maxItems");
	private static final AString K_PATTERN = Strings.intern("pattern");

	// Type name constants
	private static final AString T_OBJECT = Strings.intern("object");
	private static final AString T_ARRAY = Strings.intern("array");
	private static final AString T_STRING = Strings.intern("string");
	private static final AString T_NUMBER = Strings.intern("number");
	private static final AString T_INTEGER = Strings.intern("integer");
	private static final AString T_BOOLEAN = Strings.intern("boolean");
	private static final AString T_NULL = Strings.intern("null");
	private static final AString T_BLOB = Strings.intern("blob");
	private static final AString T_ADDRESS = Strings.intern("address");

	// ==================== validate ====================

	/**
	 * Validate a value against a schema. Returns null if valid, or a
	 * human-readable error string describing the first violation found.
	 */
	public static String validate(AMap<AString, ACell> schema, ACell value) {
		return validateAt(schema, value, "$");
	}

	/**
	 * Validate and return all violations (not just the first).
	 */
	public static AVector<AString> validateAll(AMap<AString, ACell> schema, ACell value) {
		AVector<AString> errors = Vectors.empty();
		return collectErrors(schema, value, "$", errors);
	}

	@SuppressWarnings("unchecked")
	private static String validateAt(AMap<AString, ACell> schema, ACell value, String path) {
		if (schema == null || schema.isEmpty()) return null; // empty schema accepts anything

		// const check
		ACell constVal = schema.get(K_CONST);
		if (constVal != null) {
			if (!constVal.equals(value)) return path + ": expected const " + constVal + ", got " + value;
		}

		// enum check
		AVector<ACell> enumVals = RT.ensureVector(schema.get(K_ENUM));
		if (enumVals != null) {
			boolean found = false;
			for (long i = 0; i < enumVals.count(); i++) {
				if (enumVals.get(i).equals(value)) { found = true; break; }
			}
			if (!found) return path + ": value not in enum";
		}

		// type check — if type is declared, value must match
		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type != null) {
			String err = checkType(type, value, path);
			if (err != null) return err;
		}

		// Per JSON Schema spec §7.6.1: type-specific keywords only apply when
		// the instance matches the targeted type. When the type doesn't match,
		// the keyword is considered satisfied (passes silently).

		// Object keywords: properties, required, additionalProperties
		// Apply when the value IS an object (regardless of whether type is declared)
		if (value instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) value;

			// required
			AVector<ACell> required = RT.ensureVector(schema.get(K_REQUIRED));
			if (required != null) {
				for (long i = 0; i < required.count(); i++) {
					AString key = RT.ensureString(required.get(i));
					if (key != null && !map.containsKey(key)) {
						return path + "." + key + ": required field missing";
					}
				}
			}

			// properties
			AMap<AString, ACell> properties = getMap(schema.get(K_PROPERTIES));
			if (properties != null) {
				long n = properties.count();
				for (long i = 0; i < n; i++) {
					var entry = properties.entryAt(i);
					AString key = (AString) entry.getKey();
					ACell propValue = map.get(key);
					if (propValue != null) {
						AMap<AString, ACell> propSchema = getMap(entry.getValue());
						if (propSchema != null) {
							String err = validateAt(propSchema, propValue, path + "." + key);
							if (err != null) return err;
						}
					}
				}
			}

			// additionalProperties
			ACell addlProps = schema.get(K_ADDITIONAL_PROPERTIES);
			if (CVMBool.FALSE.equals(addlProps) && properties != null) {
				long n = map.count();
				for (long i = 0; i < n; i++) {
					AString key = (AString) map.entryAt(i).getKey();
					if (!properties.containsKey(key)) {
						return path + "." + key + ": additional property not allowed";
					}
				}
			}
		}

		// Array keywords: items, minItems, maxItems
		if (value instanceof AVector) {
			@SuppressWarnings("unchecked")
			AVector<ACell> vec = (AVector<ACell>) value;

			String err = checkArrayBounds(schema, vec, path);
			if (err != null) return err;

			AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
			if (itemsSchema != null) {
				for (long i = 0; i < vec.count(); i++) {
					err = validateAt(itemsSchema, vec.get(i), path + "[" + i + "]");
					if (err != null) return err;
				}
			}
		}

		// String keywords: minLength, maxLength, pattern
		if (value instanceof AString str) {
			String err = checkStringBounds(schema, str, path);
			if (err != null) return err;
			err = checkPattern(schema, str, path);
			if (err != null) return err;
		}

		// Numeric keywords: minimum, maximum
		if (value instanceof CVMLong || value instanceof CVMDouble) {
			String err = checkNumericBounds(schema, value, path);
			if (err != null) return err;
		}

		return null;
	}

	private static String checkType(AString type, ACell value, String path) {
		boolean ok = switch (type.toString()) {
			case "object" -> value instanceof AMap;
			case "array" -> value instanceof AVector;
			case "string" -> value instanceof AString;
			case "number" -> value instanceof CVMLong || value instanceof CVMDouble;
			case "integer" -> value instanceof CVMLong;
			case "boolean" -> value instanceof CVMBool;
			case "null" -> value == null;
			case "blob" -> value instanceof ABlob;
			case "address" -> value instanceof Address;
			default -> true; // unknown type — accept
		};
		if (!ok) {
			String actual = (value == null) ? "null" : value.getClass().getSimpleName();
			return path + ": expected type " + type + ", got " + actual;
		}
		return null;
	}

	private static String checkNumericBounds(AMap<AString, ACell> schema, ACell value, String path) {
		double v;
		if (value instanceof CVMLong l) v = l.longValue();
		else if (value instanceof CVMDouble d) v = d.doubleValue();
		else return null;

		CVMDouble min = ensureDouble(schema.get(K_MINIMUM));
		if (min != null && v < min.doubleValue()) {
			return path + ": value " + v + " below minimum " + min.doubleValue();
		}
		CVMDouble max = ensureDouble(schema.get(K_MAXIMUM));
		if (max != null && v > max.doubleValue()) {
			return path + ": value " + v + " above maximum " + max.doubleValue();
		}
		return null;
	}

	private static String checkStringBounds(AMap<AString, ACell> schema, AString str, String path) {
		long len = str.count();
		CVMLong minLen = RT.ensureLong(schema.get(K_MIN_LENGTH));
		if (minLen != null && len < minLen.longValue()) {
			return path + ": string length " + len + " below minLength " + minLen.longValue();
		}
		CVMLong maxLen = RT.ensureLong(schema.get(K_MAX_LENGTH));
		if (maxLen != null && len > maxLen.longValue()) {
			return path + ": string length " + len + " above maxLength " + maxLen.longValue();
		}
		return null;
	}

	private static String checkPattern(AMap<AString, ACell> schema, AString str, String path) {
		AString pattern = RT.ensureString(schema.get(K_PATTERN));
		if (pattern == null) return null;
		try {
			if (!str.toString().matches(pattern.toString())) {
				return path + ": string does not match pattern " + pattern;
			}
		} catch (Exception e) {
			return path + ": invalid pattern: " + pattern;
		}
		return null;
	}

	private static String checkArrayBounds(AMap<AString, ACell> schema, AVector<ACell> vec, String path) {
		long count = vec.count();
		CVMLong minItems = RT.ensureLong(schema.get(K_MIN_ITEMS));
		if (minItems != null && count < minItems.longValue()) {
			return path + ": array length " + count + " below minItems " + minItems.longValue();
		}
		CVMLong maxItems = RT.ensureLong(schema.get(K_MAX_ITEMS));
		if (maxItems != null && count > maxItems.longValue()) {
			return path + ": array length " + count + " above maxItems " + maxItems.longValue();
		}
		return null;
	}

	private static CVMDouble ensureDouble(ACell cell) {
		if (cell instanceof CVMDouble d) return d;
		if (cell instanceof CVMLong l) return CVMDouble.create(l.longValue());
		return null;
	}

	// ==================== validateAll ====================

	@SuppressWarnings("unchecked")
	private static AVector<AString> collectErrors(AMap<AString, ACell> schema, ACell value,
			String path, AVector<AString> errors) {
		if (schema == null || schema.isEmpty()) return errors;

		// const
		ACell constVal = schema.get(K_CONST);
		if (constVal != null && !constVal.equals(value)) {
			errors = errors.conj(Strings.create(path + ": expected const " + constVal + ", got " + value));
		}

		// enum
		AVector<ACell> enumVals = RT.ensureVector(schema.get(K_ENUM));
		if (enumVals != null) {
			boolean found = false;
			for (long i = 0; i < enumVals.count(); i++) {
				if (enumVals.get(i).equals(value)) { found = true; break; }
			}
			if (!found) errors = errors.conj(Strings.create(path + ": value not in enum"));
		}

		// type check
		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type != null) {
			String typeErr = checkType(type, value, path);
			if (typeErr != null) {
				return errors.conj(Strings.create(typeErr));
			}
		}

		// Object keywords (apply when value is an object)
		if (value instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) value;

			AVector<ACell> required = RT.ensureVector(schema.get(K_REQUIRED));
			if (required != null) {
				for (long i = 0; i < required.count(); i++) {
					AString key = RT.ensureString(required.get(i));
					if (key != null && !map.containsKey(key)) {
						errors = errors.conj(Strings.create(path + "." + key + ": required field missing"));
					}
				}
			}

			AMap<AString, ACell> properties = getMap(schema.get(K_PROPERTIES));
			if (properties != null) {
				long n = properties.count();
				for (long i = 0; i < n; i++) {
					var entry = properties.entryAt(i);
					AString key = (AString) entry.getKey();
					ACell propValue = map.get(key);
					if (propValue != null) {
						AMap<AString, ACell> propSchema = getMap(entry.getValue());
						if (propSchema != null) {
							errors = collectErrors(propSchema, propValue, path + "." + key, errors);
						}
					}
				}
			}

			ACell addlProps = schema.get(K_ADDITIONAL_PROPERTIES);
			if (CVMBool.FALSE.equals(addlProps) && properties != null) {
				long n = map.count();
				for (long i = 0; i < n; i++) {
					AString key = (AString) map.entryAt(i).getKey();
					if (!properties.containsKey(key)) {
						errors = errors.conj(Strings.create(path + "." + key + ": additional property not allowed"));
					}
				}
			}
		}

		// Array keywords
		if (value instanceof AVector) {
			@SuppressWarnings("unchecked")
			AVector<ACell> vec = (AVector<ACell>) value;
			String boundsErr = checkArrayBounds(schema, vec, path);
			if (boundsErr != null) errors = errors.conj(Strings.create(boundsErr));
			AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
			if (itemsSchema != null) {
				for (long i = 0; i < vec.count(); i++) {
					errors = collectErrors(itemsSchema, vec.get(i), path + "[" + i + "]", errors);
				}
			}
		}

		// String keywords
		if (value instanceof AString str) {
			String boundsErr = checkStringBounds(schema, str, path);
			if (boundsErr != null) errors = errors.conj(Strings.create(boundsErr));
			String patErr = checkPattern(schema, str, path);
			if (patErr != null) errors = errors.conj(Strings.create(patErr));
		}

		// Numeric keywords
		if (value instanceof CVMLong || value instanceof CVMDouble) {
			String numErr = checkNumericBounds(schema, value, path);
			if (numErr != null) errors = errors.conj(Strings.create(numErr));
		}

		return errors;
	}

	// ==================== infer ====================

	/**
	 * Infer a JSON Schema from a CVM value.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> infer(ACell value) {
		if (value == null) return Maps.of(K_TYPE, T_NULL);
		if (value instanceof AMap) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) value;
			AMap<AString, ACell> properties = Maps.empty();
			AVector<ACell> required = Vectors.empty();
			long n = map.count();
			for (long i = 0; i < n; i++) {
				var entry = map.entryAt(i);
				AString key = (AString) entry.getKey();
				properties = properties.assoc(key, infer(entry.getValue()));
				required = required.conj(key);
			}
			return Maps.of(K_TYPE, T_OBJECT, K_PROPERTIES, properties, K_REQUIRED, required);
		}
		if (value instanceof AVector) {
			AVector<ACell> vec = (AVector<ACell>) value;
			if (vec.isEmpty()) return Maps.of(K_TYPE, T_ARRAY);
			// Infer items schema from first element (simple heuristic)
			AMap<AString, ACell> itemSchema = infer(vec.get(0));
			return Maps.of(K_TYPE, T_ARRAY, K_ITEMS, itemSchema);
		}
		if (value instanceof AString) return Maps.of(K_TYPE, T_STRING);
		if (value instanceof CVMLong) return Maps.of(K_TYPE, T_INTEGER);
		if (value instanceof CVMDouble) return Maps.of(K_TYPE, T_NUMBER);
		if (value instanceof CVMBool) return Maps.of(K_TYPE, T_BOOLEAN);
		if (value instanceof Address) return Maps.of(K_TYPE, T_ADDRESS);
		if (value instanceof ABlob) return Maps.of(K_TYPE, T_BLOB);
		return Maps.empty(); // unknown type — empty schema
	}

	// ==================== checkSchema ====================

	/**
	 * Check whether a schema is structurally valid (well-formed).
	 * Returns null if valid, error message if malformed.
	 */
	public static String checkSchema(AMap<AString, ACell> schema) {
		if (schema == null) return "schema is null";

		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type != null) {
			String t = type.toString();
			boolean valid = switch (t) {
				case "object", "array", "string", "number", "integer", "boolean", "null",
					 "blob", "address" -> true;
				default -> false;
			};
			if (!valid) return "invalid type: " + t;
		}

		AMap<AString, ACell> properties = getMap(schema.get(K_PROPERTIES));
		if (properties != null) {
			long n = properties.count();
			for (long i = 0; i < n; i++) {
				var entry = properties.entryAt(i);
				AMap<AString, ACell> propSchema = getMap(entry.getValue());
				if (propSchema == null) return "property " + entry.getKey() + " has non-map schema";
				String err = checkSchema(propSchema);
				if (err != null) return "property " + entry.getKey() + ": " + err;
			}
		}

		AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
		if (itemsSchema != null) {
			String err = checkSchema(itemsSchema);
			if (err != null) return "items: " + err;
		}

		return null;
	}

	// ==================== sanitise ====================

	/** Standard JSON Schema draft 2020-12 type values */
	private static final java.util.Set<String> STANDARD_TYPES = java.util.Set.of(
		"null", "boolean", "object", "array", "number", "string", "integer"
	);

	/** Schema keywords that are valid in JSON Schema draft 2020-12 */
	private static final java.util.Set<String> STANDARD_KEYWORDS = java.util.Set.of(
		"type", "properties", "required", "additionalProperties", "items",
		"enum", "const", "minimum", "maximum", "minLength", "maxLength",
		"minItems", "maxItems", "pattern", "description", "title", "default",
		"examples", "$schema", "$id", "$ref", "$defs", "definitions",
		"oneOf", "anyOf", "allOf", "not", "if", "then", "else",
		"format", "readOnly", "writeOnly", "deprecated"
	);

	/**
	 * Sanitise a schema to be compliant with standard JSON Schema draft 2020-12.
	 * Strips CVM type extensions, non-standard keywords, and invalid values.
	 * Use before exposing schemas to external systems (MCP clients, OpenAPI, etc.).
	 *
	 * <ul>
	 *   <li>CVM types ({@code blob}, {@code address}) replaced with {@code string}</li>
	 *   <li>Invalid type values (e.g. {@code any}) removed</li>
	 *   <li>Non-standard keywords stripped (configurable via {@code stripKeys})</li>
	 *   <li>Recursively sanitises properties and items</li>
	 * </ul>
	 *
	 * @param schema Schema to sanitise
	 * @param stripKeys Additional non-standard keys to remove (e.g. "secret", "secretFields")
	 * @return Sanitised schema (may be the same object if no changes needed)
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> sanitise(AMap<AString, ACell> schema, AString... stripKeys) {
		if (schema == null) return Maps.empty();

		AMap<AString, ACell> result = schema;

		// Fix non-standard type values; map CVM types to string
		ACell typeVal = result.get(K_TYPE);
		if (typeVal instanceof AString ts) {
			String t = ts.toString();
			if ("blob".equals(t) || "address".equals(t) || "hash".equals(t) || "accountKey".equals(t)) {
				result = result.assoc(K_TYPE, T_STRING);
			} else if (!STANDARD_TYPES.contains(t)) {
				// Invalid type — infer correct type from keywords present
				result = result.dissoc(K_TYPE);
				result = inferTypeFromKeywords(result);
			}
		}

		// If no type but has type-implying keywords, add the inferred type
		if (!result.containsKey(K_TYPE)) {
			result = inferTypeFromKeywords(result);
		}

		// Strip caller-specified non-standard keys
		for (AString key : stripKeys) {
			if (result.containsKey(key)) {
				result = result.dissoc(key);
			}
		}

		// Recurse into properties
		ACell propsCell = result.get(K_PROPERTIES);
		if (propsCell instanceof AMap<?,?> props) {
			AMap<AString, ACell> cleanProps = (AMap<AString, ACell>) props;
			long n = props.count();
			for (long i = 0; i < n; i++) {
				var entry = (MapEntry<AString, ACell>) props.entryAt(i);
				if (entry.getValue() instanceof AMap<?,?> propSchema) {
					AMap<AString, ACell> cleaned = sanitise((AMap<AString, ACell>) propSchema, stripKeys);
					if (cleaned != propSchema) {
						cleanProps = cleanProps.assoc(entry.getKey(), cleaned);
					}
				}
			}
			if (cleanProps != props) {
				result = result.assoc(K_PROPERTIES, cleanProps);
			}
		}

		// Recurse into items
		ACell itemsCell = result.get(K_ITEMS);
		if (itemsCell instanceof AMap<?,?> itemsMap) {
			AMap<AString, ACell> cleaned = sanitise((AMap<AString, ACell>) itemsMap, stripKeys);
			if (cleaned != itemsMap) {
				result = result.assoc(K_ITEMS, cleaned);
			}
		}

		return result;
	}

	/**
	 * Infer a type from the keywords present in a schema. If properties/required/
	 * additionalProperties are present, the schema must be for an object. If items/
	 * minItems/maxItems are present, it must be for an array.
	 */
	private static AMap<AString, ACell> inferTypeFromKeywords(AMap<AString, ACell> schema) {
		if (schema.containsKey(K_PROPERTIES) || schema.containsKey(K_REQUIRED)
				|| schema.containsKey(K_ADDITIONAL_PROPERTIES)) {
			return schema.assoc(K_TYPE, T_OBJECT);
		}
		if (schema.containsKey(K_ITEMS) || schema.containsKey(K_MIN_ITEMS)
				|| schema.containsKey(K_MAX_ITEMS)) {
			return schema.assoc(K_TYPE, T_ARRAY);
		}
		if (schema.containsKey(K_MIN_LENGTH) || schema.containsKey(K_MAX_LENGTH)
				|| schema.containsKey(K_PATTERN)) {
			return schema.assoc(K_TYPE, T_STRING);
		}
		if (schema.containsKey(K_MINIMUM) || schema.containsKey(K_MAXIMUM)) {
			return schema.assoc(K_TYPE, T_NUMBER);
		}
		return schema;
	}

	// ==================== coerce ====================

	/**
	 * Coerce a value to match a schema where possible.
	 * Returns the coerced value, or the original if no coercion needed/possible.
	 */
	@SuppressWarnings("unchecked")
	public static ACell coerce(AMap<AString, ACell> schema, ACell value) {
		if (schema == null || schema.isEmpty()) return value;

		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type == null) return value;

		// Already correct type — recurse for objects/arrays
		if (checkType(type, value, "") == null) {
			if (T_OBJECT.equals(type) && value instanceof AMap) {
				return coerceObject(schema, (AMap<AString, ACell>) value);
			}
			if (T_ARRAY.equals(type) && value instanceof AVector) {
				return coerceArray(schema, (AVector<ACell>) value);
			}
			return value;
		}

		// Attempt coercion
		return switch (type.toString()) {
			case "string" -> coerceToString(value);
			case "number" -> coerceToNumber(value);
			case "integer" -> coerceToInteger(value);
			case "boolean" -> coerceToBoolean(value);
			case "blob" -> coerceToBlob(value);
			case "address" -> coerceToAddress(value);
			default -> value;
		};
	}

	private static ACell coerceObject(AMap<AString, ACell> schema, AMap<AString, ACell> map) {
		AMap<AString, ACell> properties = getMap(schema.get(K_PROPERTIES));
		if (properties == null) return map;
		long n = properties.count();
		for (long i = 0; i < n; i++) {
			var entry = properties.entryAt(i);
			AString key = (AString) entry.getKey();
			ACell propValue = map.get(key);
			if (propValue != null) {
				AMap<AString, ACell> propSchema = getMap(entry.getValue());
				if (propSchema != null) {
					ACell coerced = coerce(propSchema, propValue);
					if (coerced != propValue) map = map.assoc(key, coerced);
				}
			}
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static ACell coerceArray(AMap<AString, ACell> schema, AVector<ACell> vec) {
		AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
		if (itemsSchema == null) return vec;
		boolean changed = false;
		AVector<ACell> result = vec;
		for (long i = 0; i < vec.count(); i++) {
			ACell original = vec.get(i);
			ACell coerced = coerce(itemsSchema, original);
			if (coerced != original) {
				result = result.assoc(i, coerced);
				changed = true;
			}
		}
		return changed ? result : vec;
	}

	private static ACell coerceToString(ACell value) {
		if (value == null) return Strings.create("null");
		return Strings.create(value.toString());
	}

	private static ACell coerceToNumber(ACell value) {
		if (value instanceof AString s) {
			try { return CVMDouble.create(Double.parseDouble(s.toString())); }
			catch (NumberFormatException e) { return value; }
		}
		if (value instanceof CVMBool b) return CVMDouble.create(b.booleanValue() ? 1.0 : 0.0);
		return value;
	}

	private static ACell coerceToInteger(ACell value) {
		if (value instanceof AString s) {
			try { return CVMLong.create(Long.parseLong(s.toString())); }
			catch (NumberFormatException e) { return value; }
		}
		if (value instanceof CVMDouble d) return CVMLong.create((long) d.doubleValue());
		if (value instanceof CVMBool b) return CVMLong.create(b.booleanValue() ? 1 : 0);
		return value;
	}

	private static ACell coerceToBoolean(ACell value) {
		if (value instanceof AString s) {
			String str = s.toString().toLowerCase();
			if ("true".equals(str)) return CVMBool.TRUE;
			if ("false".equals(str)) return CVMBool.FALSE;
		}
		return value;
	}

	private static ACell coerceToBlob(ACell value) {
		if (value instanceof AString s) {
			Blob b = Blob.parse(s);
			if (b != null) return b;
		}
		return value;
	}

	private static ACell coerceToAddress(ACell value) {
		if (value instanceof AString s) {
			try {
				String str = s.toString();
				if (str.startsWith("#")) str = str.substring(1);
				return Address.create(Long.parseLong(str));
			} catch (NumberFormatException e) { return value; }
		}
		if (value instanceof CVMLong l) return Address.create(l.longValue());
		return value;
	}

}
