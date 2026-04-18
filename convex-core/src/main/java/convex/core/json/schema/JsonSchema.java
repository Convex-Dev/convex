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
 * <p>Implements a subset of JSON Schema draft 2020-12 validation, plus CVM type
 * extensions ({@code blob}, {@code address}). Operates entirely on CVM data
 * types with no external library dependencies.</p>
 *
 * <h3>Spec conformance</h3>
 * <ul>
 *   <li>Validation keywords: {@code type}, {@code properties}, {@code required},
 *       {@code additionalProperties}, {@code items}, {@code enum}, {@code const},
 *       {@code minimum}, {@code maximum}, {@code minLength}, {@code maxLength},
 *       {@code minItems}, {@code maxItems}, {@code pattern}</li>
 *   <li>Empty schema {@code {}} accepts any value (spec §4.3.1: "true" schema)</li>
 *   <li>Type-specific keywords only apply when the instance type matches
 *       (spec §7.6.1: non-matching types pass silently)</li>
 *   <li>Unknown keywords are ignored as annotations
 *       (spec: "Unknown keywords SHOULD be treated as annotations")</li>
 *   <li>Not yet supported: {@code $ref}, {@code $defs}, combinators
 *       ({@code anyOf}, {@code oneOf}, {@code allOf}, {@code not}),
 *       conditional ({@code if/then/else}), {@code format}</li>
 * </ul>
 *
 * <h3>CVM extensions</h3>
 * <p>The types {@code "blob"} and {@code "address"} are Convex extensions not
 * part of JSON Schema. They are accepted by {@code validate}, {@code infer},
 * and {@code coerce} but will be rejected by standard JSON Schema validators.
 * Use {@link #sanitise} to convert them to {@code "string"} for external use.</p>
 *
 * @see <a href="https://json-schema.org/draft/2020-12/json-schema-core">JSON Schema Core 2020-12</a>
 * @see <a href="https://json-schema.org/draft/2020-12/json-schema-validation">JSON Schema Validation 2020-12</a>
 */
public class JsonSchema {

	/**
	 * Safe map extraction — returns null for null/non-map cells.
	 * Note: unlike {@code RT.ensureMap()} which returns {@code Maps.empty()} for null,
	 * this returns null. This distinction is critical to avoid infinite recursion
	 * when checking for absent schema keywords.
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}

	// ===== Schema keyword constants (JSON Schema draft 2020-12) =====

	// Validation vocabulary — §6 of json-schema-validation
	private static final AString K_TYPE = Strings.intern("type");
	private static final AString K_ENUM = Strings.intern("enum");
	private static final AString K_CONST = Strings.intern("const");

	// Numeric validation — §6.2
	private static final AString K_MINIMUM = Strings.intern("minimum");
	private static final AString K_MAXIMUM = Strings.intern("maximum");

	// String validation — §6.3
	private static final AString K_MIN_LENGTH = Strings.intern("minLength");
	private static final AString K_MAX_LENGTH = Strings.intern("maxLength");
	private static final AString K_PATTERN = Strings.intern("pattern");

	// Array validation — §6.4
	private static final AString K_MIN_ITEMS = Strings.intern("minItems");
	private static final AString K_MAX_ITEMS = Strings.intern("maxItems");

	// Object validation — §6.5
	private static final AString K_REQUIRED = Strings.intern("required");

	// Applicator vocabulary — §9-10 of json-schema-core
	private static final AString K_PROPERTIES = Strings.intern("properties");
	private static final AString K_ADDITIONAL_PROPERTIES = Strings.intern("additionalProperties");
	private static final AString K_ITEMS = Strings.intern("items");

	// ===== Type name constants =====

	// Standard JSON Schema types — §6.1.1
	private static final AString T_OBJECT = Strings.intern("object");
	private static final AString T_ARRAY = Strings.intern("array");
	private static final AString T_STRING = Strings.intern("string");
	private static final AString T_NUMBER = Strings.intern("number");
	private static final AString T_INTEGER = Strings.intern("integer");
	private static final AString T_BOOLEAN = Strings.intern("boolean");
	private static final AString T_NULL = Strings.intern("null");

	// CVM extension types (not part of JSON Schema standard)
	private static final AString T_BLOB = Strings.intern("blob");
	private static final AString T_ADDRESS = Strings.intern("address");

	// ==================== validate ====================

	/**
	 * Validate a value against a schema. Returns null if valid, or a
	 * human-readable error string describing the first violation found.
	 *
	 * <p>Conforms to JSON Schema draft 2020-12 validation semantics:
	 * type-specific keywords only apply when the instance matches the
	 * targeted type (§7.6.1).</p>
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
		// §4.3.1: empty schema ({} or true) accepts any value
		if (schema == null || schema.isEmpty()) return null;

		// §6.1.3: const — value must equal the const value exactly
		ACell constVal = schema.get(K_CONST);
		if (constVal != null) {
			if (!constVal.equals(value)) return path + ": expected const " + constVal + ", got " + value;
		}

		// §6.1.2: enum — value must be one of the listed values
		AVector<ACell> enumVals = RT.ensureVector(schema.get(K_ENUM));
		if (enumVals != null) {
			boolean found = false;
			for (long i = 0; i < enumVals.count(); i++) {
				if (enumVals.get(i).equals(value)) { found = true; break; }
			}
			if (!found) return path + ": value not in enum";
		}

		// §6.1.1: type — if declared, the instance must match
		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type != null) {
			String err = checkType(type, value, path);
			if (err != null) return err;
		}

		// §7.6.1: "When the type of the instance is not of the type targeted
		// by the keyword, the instance is considered to conform to the assertion."
		// Therefore, type-specific keywords only fire when the instance matches.

		// §6.5 + §10.3.2: Object keywords — apply only when value is an object
		if (value instanceof AMap map) {
			// §6.5.3: required — each listed key must be present
			AVector<ACell> required = RT.ensureVector(schema.get(K_REQUIRED));
			if (required != null) {
				for (long i = 0; i < required.count(); i++) {
					AString key = RT.ensureString(required.get(i));
					if (key != null && !map.containsKey(key)) {
						return path + "." + key + ": required field missing";
					}
				}
			}

			// §10.3.2.1: properties — validate each present property against its sub-schema
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

			// §10.3.2.3: additionalProperties — when false, no keys beyond properties allowed
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

		// §6.4 + §10.3.1: Array keywords — apply only when value is an array
		if (value instanceof AVector vec) {
			// §6.4.2/§6.4.3: minItems/maxItems
			String err = checkArrayBounds(schema, vec, path);
			if (err != null) return err;

			// §10.3.1.2: items — validate each element against the items sub-schema
			AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
			if (itemsSchema != null) {
				for (long i = 0; i < vec.count(); i++) {
					err = validateAt(itemsSchema, vec.get(i), path + "[" + i + "]");
					if (err != null) return err;
				}
			}
		}

		// §6.3: String keywords — apply only when value is a string
		if (value instanceof AString str) {
			// §6.3.1/§6.3.2: minLength/maxLength
			String err = checkStringBounds(schema, str, path);
			if (err != null) return err;
			// §6.3.3: pattern
			err = checkPattern(schema, str, path);
			if (err != null) return err;
		}

		// §6.2: Numeric keywords — apply only when value is a number
		if (value instanceof CVMLong || value instanceof CVMDouble) {
			// §6.2.4/§6.2.2: minimum/maximum
			String err = checkNumericBounds(schema, value, path);
			if (err != null) return err;
		}

		return null;
	}

	/**
	 * §6.1.1: type — maps schema type strings to CVM instance types.
	 * Standard types per JSON Schema draft 2020-12, plus CVM extensions.
	 */
	private static String checkType(AString type, ACell value, String path) {
		boolean ok = switch (type.toString()) {
			// Standard JSON Schema types (§6.1.1)
			case "object" -> value instanceof AMap;
			case "array" -> value instanceof AVector;
			case "string" -> value instanceof AString;
			case "number" -> value instanceof CVMLong || value instanceof CVMDouble;
			case "integer" -> value instanceof CVMLong;
			case "boolean" -> value instanceof CVMBool;
			case "null" -> value == null;
			// CVM extensions (not part of JSON Schema standard)
			case "blob" -> value instanceof ABlob;
			case "address" -> value instanceof Address;
			// Unknown types: per spec, unknown keywords are annotations — accept
			default -> true;
		};
		if (!ok) {
			String actual = (value == null) ? "null" : value.getClass().getSimpleName();
			return path + ": expected type " + type + ", got " + actual;
		}
		return null;
	}

	/** §6.2.4/§6.2.2: minimum/maximum — only applies to numeric instances */
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

	/** §6.3.1/§6.3.2: minLength/maxLength — only applies to string instances */
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

	/** §6.3.3: pattern — ECMA-262 regex, only applies to string instances */
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

	/** §6.4.2/§6.4.3: minItems/maxItems — only applies to array instances */
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

	/** Same semantics as {@link #validate} but collects all errors instead of short-circuiting. */
	@SuppressWarnings("unchecked")
	private static AVector<AString> collectErrors(AMap<AString, ACell> schema, ACell value,
			String path, AVector<AString> errors) {
		if (schema == null || schema.isEmpty()) return errors;

		// §6.1.3: const
		ACell constVal = schema.get(K_CONST);
		if (constVal != null && !constVal.equals(value)) {
			errors = errors.conj(Strings.create(path + ": expected const " + constVal + ", got " + value));
		}

		// §6.1.2: enum
		AVector<ACell> enumVals = RT.ensureVector(schema.get(K_ENUM));
		if (enumVals != null) {
			boolean found = false;
			for (long i = 0; i < enumVals.count(); i++) {
				if (enumVals.get(i).equals(value)) { found = true; break; }
			}
			if (!found) errors = errors.conj(Strings.create(path + ": value not in enum"));
		}

		// §6.1.1: type
		AString type = RT.ensureString(schema.get(K_TYPE));
		if (type != null) {
			String typeErr = checkType(type, value, path);
			if (typeErr != null) {
				return errors.conj(Strings.create(typeErr));
			}
		}

		// §7.6.1: type-specific keywords pass silently for non-matching types

		// §6.5 + §10.3.2: Object keywords
		if (value instanceof AMap map) {
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

		// §6.4 + §10.3.1: Array keywords
		if (value instanceof AVector vec) {
			String boundsErr = checkArrayBounds(schema, vec, path);
			if (boundsErr != null) errors = errors.conj(Strings.create(boundsErr));
			AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
			if (itemsSchema != null) {
				for (long i = 0; i < vec.count(); i++) {
					errors = collectErrors(itemsSchema, vec.get(i), path + "[" + i + "]", errors);
				}
			}
		}

		// §6.3: String keywords
		if (value instanceof AString str) {
			String boundsErr = checkStringBounds(schema, str, path);
			if (boundsErr != null) errors = errors.conj(Strings.create(boundsErr));
			String patErr = checkPattern(schema, str, path);
			if (patErr != null) errors = errors.conj(Strings.create(patErr));
		}

		// §6.2: Numeric keywords
		if (value instanceof CVMLong || value instanceof CVMDouble) {
			String numErr = checkNumericBounds(schema, value, path);
			if (numErr != null) errors = errors.conj(Strings.create(numErr));
		}

		return errors;
	}

	// ==================== infer ====================

	/**
	 * Infer a JSON Schema from a CVM value. Produces the tightest schema that
	 * accepts the given value. Not part of the JSON Schema spec — a Convex utility.
	 *
	 * <p>For objects: all observed keys become required, each with an inferred property schema.
	 * For arrays: items schema inferred from first element (simple heuristic).</p>
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
			AMap<AString, ACell> itemSchema = infer(vec.get(0));
			return Maps.of(K_TYPE, T_ARRAY, K_ITEMS, itemSchema);
		}
		if (value instanceof AString) return Maps.of(K_TYPE, T_STRING);
		if (value instanceof CVMLong) return Maps.of(K_TYPE, T_INTEGER);
		if (value instanceof CVMDouble) return Maps.of(K_TYPE, T_NUMBER);
		if (value instanceof CVMBool) return Maps.of(K_TYPE, T_BOOLEAN);
		if (value instanceof Address) return Maps.of(K_TYPE, T_ADDRESS);
		if (value instanceof ABlob) return Maps.of(K_TYPE, T_BLOB);
		return Maps.empty();
	}

	// ==================== checkSchema ====================

	/**
	 * Check whether a schema is structurally valid (well-formed).
	 * Returns null if valid, error message if malformed.
	 *
	 * <p>Validates: type values are recognised, properties values are maps,
	 * items value is a map. Does not reject unknown keywords (per spec,
	 * unknown keywords are valid annotations).</p>
	 */
	public static String checkSchema(AMap<AString, ACell> schema) {
		if (schema == null) return "schema is null";

		// §6.1.1: type must be one of the standard values (or CVM extensions)
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

		// §10.3.2.1: properties values must be valid schemas (maps)
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

		// §10.3.1.2: items value must be a valid schema (map)
		AMap<AString, ACell> itemsSchema = getMap(schema.get(K_ITEMS));
		if (itemsSchema != null) {
			String err = checkSchema(itemsSchema);
			if (err != null) return "items: " + err;
		}

		return null;
	}

	// ==================== sanitise ====================

	/** Standard JSON Schema draft 2020-12 type values (for sanitise) */
	private static final java.util.Set<String> STANDARD_TYPES = java.util.Set.of(
		"null", "boolean", "object", "array", "number", "string", "integer"
	);

	/**
	 * Sanitise a schema for external consumption (MCP clients, OpenAPI, etc.).
	 * Not part of JSON Schema spec — a Convex utility for interoperability.
	 *
	 * <p>Converts CVM extension types to standard equivalents and optionally
	 * strips application-specific annotation keys. When an invalid type is
	 * removed, infers the correct standard type from keywords present
	 * (e.g. {@code properties} implies {@code "type": "object"}).</p>
	 *
	 * <p>This is a lossy, best-effort transformation. Prefer fixing schemas
	 * at source rather than relying on sanitise in core code paths.</p>
	 *
	 * @param schema Schema to sanitise
	 * @param stripKeys Application-specific annotation keys to remove
	 * @return Sanitised schema (may be the same object if no changes needed)
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> sanitise(AMap<AString, ACell> schema, AString... stripKeys) {
		if (schema == null) return Maps.empty();

		AMap<AString, ACell> result = schema;

		// Map CVM extension types to standard JSON Schema equivalents
		ACell typeVal = result.get(K_TYPE);
		if (typeVal instanceof AString ts) {
			String t = ts.toString();
			if ("blob".equals(t) || "address".equals(t) || "hash".equals(t) || "accountKey".equals(t)) {
				result = result.assoc(K_TYPE, T_STRING);
			} else if (!STANDARD_TYPES.contains(t)) {
				// Invalid/unknown type — remove and infer from keywords
				result = result.dissoc(K_TYPE);
				result = inferTypeFromKeywords(result);
			}
		}

		// If no type but keywords imply one, add it for clarity
		if (!result.containsKey(K_TYPE)) {
			result = inferTypeFromKeywords(result);
		}

		// Strip caller-specified annotation keys (e.g. "secret", "secretFields")
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
	 * Infer a standard type from keywords present in a schema.
	 * Used by sanitise when an invalid type is removed.
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
	 * Coerce a value to match a schema where possible. Not part of the JSON
	 * Schema spec — a utility for handling type mismatches at system
	 * boundaries (LLM outputs, API inputs, cross-system data exchange).
	 *
	 * <p>Returns the original value unchanged if no coercion is possible or needed.
	 * Coercion is explicit and opt-in — never called implicitly by validate.</p>
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

		// Attempt type coercion
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
