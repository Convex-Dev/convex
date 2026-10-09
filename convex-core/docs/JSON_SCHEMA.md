# JSON Schema Validation

`convex.core.json.schema.JsonSchema` validates, infers, checks, sanitises and coerces
CVM data against JSON Schema documents that are themselves CVM maps. Schemas are
everywhere in Convex and Covia (operation input and output, MCP tool parameters,
structured LLM output, agent hand-offs), and this class gives every layer one
ACell-native validator with no external library and no serialisation step. How JSON
maps onto CVM values in general is specified in
[CAD044](https://docs.convex.world/docs/cad/json).

## Key points

- Schemas are `AMap<AString, ACell>` and instances are any `ACell`; nothing is
  converted to text on the way in or out.
- A pragmatic subset of JSON Schema draft 2020-12 is implemented: the keywords that
  appear in real operation metadata, not the full specification.
- Unknown keywords are ignored as annotations, the empty schema accepts anything, and
  type-specific keywords apply only when the instance has that type, all as the
  specification requires.
- Two CVM extension types, `blob` and `address`, are accepted by validation, inference
  and coercion; `sanitise` rewrites them for external consumers that only know
  standard types.
- Coercion is explicit. `validate` never coerces; callers choose strict validation or
  lenient coercion at system boundaries.
- Errors carry a `$.path` to the offending field; `validate` stops at the first,
  `validateAll` collects every violation.
- There is no compile step. Schemas are walked on each call, which is fast enough for
  orchestration hand-offs; compiled regex patterns are cached.

## Supported keywords

| Keyword | Meaning |
|---|---|
| `type` | `object`, `array`, `string`, `number`, `integer`, `boolean`, `null`, plus `blob` and `address` |
| `properties`, `required`, `additionalProperties` | Object shape; `additionalProperties: false` forbids unlisted keys |
| `items`, `minItems`, `maxItems` | Array element schema and length bounds |
| `enum`, `const` | Fixed value sets and exact matches, compared with `equals` |
| `minimum`, `maximum` | Numeric bounds for `CVMLong` and `CVMDouble` |
| `minLength`, `maxLength`, `pattern` | String length bounds and regex match |

Not supported: `$ref` and `$defs`, the combinators `anyOf`, `oneOf`, `allOf` and
`not`, `if`/`then`/`else`, `format`, `dependentRequired` and `patternProperties`. None
of these appear in current operation schemas; add them when a real schema needs them.

## Type mapping

| Schema type | CVM types | Origin |
|---|---|---|
| `object` | `AMap` | JSON Schema |
| `array` | `AVector` | JSON Schema |
| `string` | `AString` | JSON Schema |
| `number` | `CVMLong`, `CVMDouble` | JSON Schema |
| `integer` | `CVMLong` | JSON Schema |
| `boolean` | `CVMBool` | JSON Schema |
| `null` | `null` | JSON Schema |
| `blob` | `ABlob` | CVM extension |
| `address` | `Address` | CVM extension |

A schema without `type` accepts any type and acts as documentation only.

## API

| Method | Purpose | Typical use |
|---|---|---|
| `validate(schema, value)` | First violation as a string, or `null` if valid | Orchestrator step validation, adapter input checks, API requests |
| `validateAll(schema, value)` | Vector of every violation, empty if valid | Debugging, detailed error reports |
| `infer(value)` | Tightest schema describing a value | Schema discovery, documentation, agent tooling |
| `checkSchema(schema)` | Structural check of the schema itself, `null` if well formed | Asset storage, schema sanitisation |
| `sanitise(schema, stripKeys...)` | Standard-types-only copy with annotation keys removed | MCP and OpenAPI export |
| `coerce(schema, value)` | Best-effort conversion towards the schema | LLM output, API input |

Error strings use a JSON-pointer-like path rooted at `$`:

```
$.vendor_validation.status: expected type string, got null
$.line_items[2].amount: expected type number, got string
$.po_number: required field missing
$.extra_field: additional property not allowed
```

## Inference

`infer` walks the value and emits the tightest matching schema: maps become objects
with every key required, vectors become arrays whose `items` schema is the union of the
element schemas, and leaves map to their type. Array unions are useful but imprecise;
review an inferred schema before adopting it as a contract.

## Coercion

`coerce` returns the converted value, or the original when no conversion applies.

| Schema type | Input | Output |
|---|---|---|
| `number` | `"42.5"` | `CVMDouble 42.5` |
| `integer` | `"42"` | `CVMLong 42` |
| `boolean` | `"true"` / `"false"` | `CVMBool` |
| `string` | `CVMLong`, `CVMDouble`, `CVMBool` | their string form |
| `blob` | hex string | `Blob` |
| `address` | `"#42"` or `"42"` | `Address` |
| `object`, `array` | containers | recursively coerced |

## Sanitisation

`sanitise` is a Convex utility, not part of JSON Schema. It maps the extension types
(and the legacy names `hash` and `accountKey`) to `string`, infers a standard `type`
where an invalid one was removed (`properties` implies `object`), and strips the
application annotation keys the caller names, such as secret markers. It is lossy and
best-effort: prefer fixing schemas at source over relying on it in core paths.

## Where the code lives

- `convex.core.json.schema.JsonSchema` — the implementation, one static method per
  operation.
- `convex.core.json.schema.JsonSchemaTest` — type checks for every type, required and
  additional-property enforcement, nesting, enums, bounds, error paths, inference
  round-trips, coercion and real operation schemas.
- `convex.core.util.JSON` — JSON and JSON5 text conversion used at the edges.

## Related

- [CAD044 JSON on the Lattice](https://docs.convex.world/docs/cad/json) — JSON as a subset of CVM types.
- [CAD041 MCP](https://docs.convex.world/docs/cad/mcp) — tool schemas that pass through `sanitise`.
- [JSON Schema draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core) — the specification this subset follows.
