# JSON Schema Validation — Design

Lightweight, ACell-native JSON Schema validation for Convex data structures.

**Status:** Draft — April 2026

---

## Motivation

Schemas are pervasive in Convex and Covia: operation input/output definitions, MCP tool
parameters, structured LLM output, agent hand-offs in orchestrations. But validation is
never actually performed — schemas are stored, converted to other formats (LangChain4j,
MCP), and used as documentation, but data is never checked against them at runtime.

This means:
- Malformed inputs fail deep in adapter logic with unclear errors
- Agent pipeline hand-offs silently pass corrupt data
- Orchestration steps receive wrong-shaped data from prior steps
- No enforcement of `required` fields, type constraints, or enum values

A lightweight validator in convex-core would give all layers a single, ACell-native
validation function — no external library, no serialisation overhead, pure CVM data in
and out.

## Scope

Support the subset of JSON Schema (draft 2020-12) that's actually used in Convex/Covia
operation metadata. Not a full JSON Schema implementation — just the features that appear
in practice.

### In scope

| Keyword | Purpose |
|---------|---------|
| `type` | `"object"`, `"array"`, `"string"`, `"number"`, `"integer"`, `"boolean"`, `"null"` |
| `properties` | Named properties of an object, each with its own schema |
| `required` | Array of required property names |
| `items` | Schema for array elements |
| `enum` | Fixed set of allowed values |
| `additionalProperties` | `false` = no extra properties allowed |
| `description` | Ignored (documentation only) |
| `minimum`, `maximum` | Numeric range bounds |
| `minLength`, `maxLength` | String length bounds |
| `minItems`, `maxItems` | Array length bounds |
| `const` | Exact value match |
| `pattern` | Regex pattern for strings (optional — include if low-cost) |

### Out of scope (for now)

| Keyword | Why |
|---------|-----|
| `$ref`, `$defs` | No operation schema currently uses references |
| `anyOf`, `oneOf`, `allOf`, `not` | Combinators — complex, rarely used in tool schemas |
| `if/then/else` | Conditional schemas — not seen in practice |
| `format` | Semantic formats (date, email, uri) — would need per-format validators |
| `dependentRequired` | Conditional requirements — not used |
| `patternProperties` | Regex property matching — not used |

## API

### Package

`convex.core.data.schema` in `convex-core`. No external dependencies — pure ACell operations.

### Core Class: `JsonSchema`

```java
public class JsonSchema {

    /**
     * Validate a value against a schema. Returns null if valid, or a
     * human-readable error string describing the first violation found.
     *
     * @param schema JSON Schema as AMap (e.g. {"type":"object","properties":{...}})
     * @param value  The value to validate (ACell — may be AMap, AVector, AString, etc.)
     * @return null if valid, error message string if invalid
     */
    public static String validate(AMap<AString, ACell> schema, ACell value);

    /**
     * Validate and return all violations (not just the first).
     *
     * @return Empty vector if valid, vector of error strings if invalid
     */
    public static AVector<AString> validateAll(AMap<AString, ACell> schema, ACell value);

    /**
     * Infer a JSON Schema from a CVM value. Useful for generating schemas
     * from example data.
     *
     * @param value The value to infer a schema from
     * @return Inferred schema as AMap
     */
    public static AMap<AString, ACell> infer(ACell value);

    /**
     * Check whether a schema is structurally valid (well-formed).
     * Does not validate data — validates the schema itself.
     *
     * @param schema The schema to check
     * @return null if valid schema, error message if malformed
     */
    public static String checkSchema(AMap<AString, ACell> schema);

    /**
     * Coerce a value to match a schema where possible. For example:
     * string "42" → number 42 when schema says type=number.
     * Returns the coerced value, or the original if no coercion needed/possible.
     *
     * @param schema Target schema
     * @param value  Value to coerce
     * @return Coerced value (may be unchanged)
     */
    public static ACell coerce(AMap<AString, ACell> schema, ACell value);
}
```

### Operations

| Method | Purpose | When to use |
|--------|---------|-------------|
| `validate` | Check a value against a schema | Orchestrator step validation, adapter input checks, API request validation |
| `validateAll` | Get all violations | Debugging, detailed error reporting |
| `infer` | Generate a schema from example data | Agent tooling, schema discovery, documentation generation |
| `checkSchema` | Validate the schema itself | Asset storage, MCP schema sanitisation |
| `coerce` | Best-effort type conversion | LLM outputs (strings that should be numbers), API inputs |

## Implementation

### Type checking

The core dispatch — match `type` keyword against the CVM value's actual type:

| Schema type | CVM type(s) | Standard? |
|-------------|-------------|-----------|
| `"object"` | `AMap` | JSON Schema |
| `"array"` | `AVector` | JSON Schema |
| `"string"` | `AString` | JSON Schema |
| `"number"` | `CVMDouble`, `CVMLong` | JSON Schema |
| `"integer"` | `CVMLong` | JSON Schema |
| `"boolean"` | `CVMBool` | JSON Schema |
| `"null"` | `null` | JSON Schema |
| `"blob"` | `ABlob` | CVM extension |
| `"address"` | `Address` | CVM extension |
| `"hash"` | `Hash` | CVM extension |
| `"accountKey"` | `AccountKey` | CVM extension |

Missing `type` keyword means any type is accepted (schema acts as documentation only).

### Object validation

For `type: "object"`:

1. Check value is `AMap`
2. For each entry in `required`: check the key exists in the map
3. For each entry in `properties`: if the key exists in the map, recursively validate
   the value against the property's schema
4. If `additionalProperties: false`: check no keys exist that aren't in `properties`

### Array validation

For `type: "array"`:

1. Check value is `AVector`
2. If `items` present: validate each element against the items schema
3. If `minItems`/`maxItems`: check count bounds

### Enum validation

If `enum` is present: check value is one of the enum values (using `.equals()`).

### Numeric validation

If `minimum`/`maximum`: check value is within range. Works for both `CVMLong` and
`CVMDouble`.

### String validation

If `minLength`/`maxLength`: check string length.
If `pattern`: compile regex and match (cache compiled patterns for performance).

### Error messages

Errors include the path to the violating field for easy debugging:

```
"$.vendor_validation.status: expected type string, got null"
"$.line_items[2].amount: expected type number, got string"
"$.po_number: required field missing"
"$.extra_field: additional property not allowed"
```

Path uses JSON Pointer-like notation with `$.` prefix for the root.

### Schema inference

`infer(value)` walks the CVM value and produces the tightest schema:

- `AMap` → `{type: "object", properties: {<key>: infer(val), ...}, required: [<all keys>]}`
- `AVector` → `{type: "array", items: <merged schema of all elements>}`
- `AString` → `{type: "string"}`
- `CVMLong` → `{type: "integer"}`
- `CVMDouble` → `{type: "number"}`
- `CVMBool` → `{type: "boolean"}`
- `null` → `{type: "null"}`

For arrays, merge element schemas by unioning properties (produces a schema that accepts
all observed elements). This is useful but imprecise — review before using as a contract.

### Coercion

`coerce(schema, value)` performs best-effort type conversion. Useful at system boundaries
where data arrives in a different representation than the schema expects — LLM outputs,
API inputs, or cross-system data exchange.

Coercion is optional and explicit — callers choose whether to validate strictly or coerce
leniently. It never runs implicitly during validation.

#### Standard JSON type coercions

| Schema type | Input | Output |
|-------------|-------|--------|
| `number` | `AString "42.5"` | `CVMDouble 42.5` |
| `integer` | `AString "42"` | `CVMLong 42` |
| `boolean` | `AString "true"/"false"` | `CVMBool` |
| `string` | `CVMLong 42` | `AString "42"` |
| `string` | `CVMDouble 3.14` | `AString "3.14"` |
| `string` | `CVMBool true` | `AString "true"` |
| `object` | properties with coercible values | recursively coerced |
| `array` | elements with coercible values | recursively coerced |

#### CVM type extensions

Convex extends JSON Schema with CVM-native types. These are not part of the JSON Schema
standard but are useful for Convex-native data:

| Schema type | CVM type | Coercion from string |
|-------------|----------|---------------------|
| `"blob"` | `ABlob` | Hex string → `Blob.parse()` |
| `"address"` | `Address` | `"#42"` or `"42"` → `Address.create()` |
| `"hash"` | `Hash` | 64-char hex → `Hash.parse()` |
| `"accountKey"` | `AccountKey` | 64-char hex → `AccountKey.fromHex()` |

These are recognised by `validate`, `coerce`, and `infer`. Standard JSON Schema validators
would treat them as unknown type values — Convex code should use them only for
Convex-native schemas, not for interop with external systems.

Returns the original value unchanged if coercion isn't possible or isn't needed.

## Venue Integration

### Orchestrator step validation

After each step completes, validate the output against the next step's expected input
schema (from the operation metadata). Fail the orchestration with a clear error if
validation fails.

### Adapter input validation

`JobManager.invokeOperation()` can optionally validate input against
`meta.operation.input` before dispatching to the adapter. Off by default (not all
operations have schemas), enabled per-operation or venue-wide.

### Venue operations

Expose as Covia adapter operations for agent use:

| Operation | Purpose |
|-----------|---------|
| `schema:validate` | Validate a value against a schema, return errors |
| `schema:infer` | Infer a schema from a value |
| `schema:coerce` | Coerce a value to match a schema |

These would be lightweight JVM operations (no LLM, no IO) — fast enough for inline
use in orchestrations or agent tool calls.

### MCP schema sanitisation

`MCP.sanitiseSchema()` currently does ad-hoc validation. Replace with
`JsonSchema.checkSchema()` for consistency.

## Performance Considerations

- **No compilation step**: Schemas are walked on every validation call. For hot paths,
  callers can cache a "compiled" representation (pre-extracted required set, pre-resolved
  property schemas). But for orchestration hand-offs (millisecond-scale operations between
  second-scale agent calls), raw validation is more than fast enough.
- **No regex compilation**: If `pattern` is supported, cache compiled `Pattern` objects
  in a small LRU cache keyed by the pattern string.
- **Short-circuit**: `validate()` returns on first error. `validateAll()` collects all.

## Testing

Minimum test coverage:

- Type checking for each of the 7 types
- Required field enforcement
- additionalProperties: false
- Nested object validation (2+ levels)
- Array element validation
- Enum validation
- Numeric range bounds
- String length bounds
- Error message format (path included)
- Inference round-trip (infer → validate → pass)
- Coercion for string↔number, string↔boolean
- Real-world schemas from existing operation metadata (AP demo agents)
- Edge cases: null value, empty schema, empty object, empty array

## Related Work

- `convex.auth.ucan.Capability` — uses similar AMap-based matching patterns
- `covia.venue.api.MCP.sanitiseSchema()` — ad-hoc schema cleaning, to be replaced
- `covia.adapter.LangChainAdapter.toSchemaElement()` — CVM→LangChain4j schema conversion
- JSON Schema draft 2020-12: https://json-schema.org/draft/2020-12/json-schema-core
