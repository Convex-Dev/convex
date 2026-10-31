# Convex DB: SQL Column Types

**Status: proposed design, partly implemented.** The table entry's format
version (see [Format Version](#format-version)), the [Calcite type system](#calcite-type-system)
and the presentation of version 0 integer columns are in place; tables are still written as
version 0. The rest, including types not yet supported, is tracked in
[#793](https://github.com/Convex-Dev/convex/issues/793).

How Convex DB binds the SQL types users declare to the values stored in the lattice, and
how it presents them through JDBC and the PostgreSQL wire protocol.

The table entry format, its version, the type identifiers, the stored CVM value of each type
and the primary key encoding are specified in
[CAD039](https://docs.convex.world/docs/cad/convex_sql). This document covers the
implementation: where the binding lives in code, how each type maps to Calcite, Java and
PostgreSQL, and which errors clients see.

## Principle

Users choose their tables' types; Convex DB records each declared type exactly and honours
it. Every CVM integer is arbitrary precision, so the declared type decides the range a
column accepts and the SQL type clients see, not how the value is stored. A `NUMERIC(40,0)`
column holds 40-digit integers; a `BIGINT` column holds 64-bit ones, as in any SQL database.

The binding is the table entry: its schema lists each column's declared type, and its
version says how to read the schema, the row values and the keys. Every node reads a table
through that entry, so the code reads it through one place too.

## Format Version

`SQLTable` reads the entry's version from index 4; an entry without that field is version 0.
`SQLTable.SUPPORTED_VERSION` is the highest version this implementation reads and writes.

- Version 0 entries keep exactly their four fields: creation, row updates and merges of
  version 0 entries add no version field, so their encoding is unchanged and older nodes
  read them as before.
- Row updates and merges carry a version field when one is present. A merge takes the schema
  winner's version, and on a tie of timestamp and schema keeps the higher one, restoring a
  version that an older node dropped.
- `SQLSchema.getLiveTable`, through which every SQL and direct access goes, refuses a table
  of an unsupported version with `IllegalStateException`, and `getTableNames` leaves such
  tables out so that listings and catalog queries keep working. Merge never refuses.

## Column Codec

`ConvexColumnType` is the codec for one column. It is built from the column's definition in
the schema together with the entry's version, and every path that touches a value uses it:

| Path | Use |
|------|-----|
| DDL | Normalises the declared SQL type to its identifier, precision and scale |
| Planning | Gives the column's Calcite type (`toRelDataType`) |
| Writes | Converts a Java value to its stored CVM value, enforcing the type's constraint (`toCell`) |
| Reads | Converts a stored CVM value to the Java value Calcite expects for the declared type (`toJava`) |
| Filters | Converts parameters and literals compared with the column, before a key lookup |
| Keys | Supplies the value that `SQLSchema` encodes as a primary key |

Public API:

```java
ConvexColumnType.fromRelDataType(RelDataType declared) // DDL: the declared SQL type
ConvexColumnType.fromSchema(AVector<ACell> column, int version) // a stored column definition
RelDataType toRelDataType(RelDataTypeFactory factory)  // Calcite type for the column
ACell toCell(Object value)    // Java value to stored CVM value; SQLException if it does not conform
Object toJava(ACell cell)     // stored CVM value to Java value; SQLException if it does not conform
```

Conversions keep integers canonical (`AInteger.create`), and compare and key them by value.

## Type Mapping

For version 1 tables. Identifiers, stored values and constraints are as in CAD039.

| Identifier | Calcite type | Java value | PostgreSQL type (OID) |
|------------|--------------|------------|-----------------------|
| `SMALLINT` | `SMALLINT` | `Short` | `int2` (21) |
| `INTEGER` | `INTEGER` | `Integer` | `int4` (23) |
| `BIGINT` | `BIGINT` | `Long` | `int8` (20) |
| `NUMERIC(p,s)` | `DECIMAL(p,s)` | `BigDecimal` | `numeric` (1700) |
| `NUMERIC` | `DECIMAL(max,0)` | `BigDecimal` | `numeric` (1700), unconstrained |
| `REAL` | `REAL` | `Float` | `float4` (700) |
| `DOUBLE PRECISION` | `DOUBLE` | `Double` | `float8` (701) |
| `BOOLEAN` | `BOOLEAN` | `Boolean` | `bool` (16) |
| `CHAR(n)` | `CHAR(n)` | `String` | `bpchar` (1042) |
| `VARCHAR(n)` | `VARCHAR(n)` | `String` | `varchar` (1043) |
| `VARBINARY(n)` | `VARBINARY(n)` | `byte[]` | `bytea` (17) |
| `TIMESTAMP(p)` | `TIMESTAMP(p)` | `Timestamp` | `timestamp` (1114) |
| `JSON` | `OTHER` | `String` (JSON text) | `json` (114) |
| nil (any) | `ANY` | `RT.jvm` of the value | `unknown` (705) |

A `NUMERIC` value is stored as an integer scaled by 10^*s*; the codec scales by the column's
declared scale in both directions, so a `BigDecimal` never leaves the codec rounded.

Version 0 tables keep their stored values. Their integer columns are presented as the type
those values actually have. Other columns are presented as declared: Calcite converts the
values that SQL writes to the presented type, and that conversion is what keeps those writes
within the declared length, scale and precision until version 1 enforces them.

| Version 0 identifier | Presented as |
|----------------------|--------------|
| `BIGINT` | `NUMERIC` with scale 0 (`DECIMAL(max,0)`) |
| `INTEGER` | `BIGINT` |
| `DOUBLE` | `DOUBLE PRECISION` |
| `DECIMAL(p,s)` | `DECIMAL(p,s)`, holding doubles |
| `CHAR(n)`, `VARCHAR(n)` | as declared |
| `TIMESTAMP(p)` | as declared, stored in milliseconds; DDL records `TIMESTAMP` without precision as precision 0 |
| others | as for version 1 |

A version 0 table becomes version 1 only when its schema changes, when the writer rewrites
every row in the version 1 representation.

## Calcite Type System

Calcite's default type system caps `DECIMAL` precision at 19 digits. Convex DB installs its
own `RelDataTypeSystem`, `ConvexTypeSystem`, on every connection, with a maximum `DECIMAL`
precision equal to the largest CVM integer (`Constants.MAX_BIG_INTEGER_LENGTH` bytes, 9,864
digits), so that `max` above is that limit. A `DECIMAL` declared without precision keeps
Calcite's default precision of 19, which version 0 DDL records.

Result types follow PostgreSQL. The type system's `deriveSumType` makes `SUM` over
`SMALLINT` and `INTEGER` a `BIGINT`, and over `BIGINT` and `NUMERIC` a `NUMERIC`, so a sum
cannot overflow. `AVG` over `REAL` and `DOUBLE PRECISION` is `DOUBLE PRECISION`; over the
exact types it is `NUMERIC`. `ConvexAggregate` already computes sums with CVM arithmetic;
the result converter hands them to Calcite through the result column's codec.

## Errors

The codec and the operators raise `SQLException`s with PostgreSQL's SQLSTATE codes, which reach
JDBC callers unchanged and pgwire clients through `ErrorResponse.fromException`. When Calcite wraps the
exception, `fromException` takes the SQLSTATE from the first `SQLException` in the cause
chain.

| Condition | SQLSTATE |
|-----------|----------|
| Value out of the column's range or precision | `22003` |
| String or blob longer than the declared length | `22001` |
| Text that is not valid input for the type (pgwire parameters) | `22P02` |
| Stored value that does not conform to its column (from a merge) | `22000` |
| Table definition with an unsupported SQL type | `0A000` |
| Connection to a database whose identifier policy is unknown | `0A000` |
| Scalar subquery returning more than one row | `21000` |
| `INSERT` or key-changing `UPDATE` of a primary key the table already holds | `23505` |

## PostgreSQL Wire Protocol

`RowDescription` takes each column's OID and type modifier from the codec rather than from
the JDBC type name. Results are sent in text format: `BigDecimal` with `toPlainString`,
timestamps in PostgreSQL's text form (`YYYY-MM-DD HH:MM:SS[.ffffff]`), booleans as `t` and `f`,
and blobs as `\x` hex.

Text parameters are parsed by their declared type, or by the type the query implies when
none is declared ([#772](https://github.com/Convex-Dev/convex/issues/772)); `numeric`
parameters are parsed to `BigDecimal` for the codec.

## Primary Keys

`SQLSchema.toKey` encodes the codec's value: an integer in the 64-bit range as 8 bytes, a
larger one as its minimal two's-complement bytes, a string as UTF-8 and a blob as itself.
Integer keys come from the value, so a small integer held as a `CVMBigInteger` gives the same
key as the equal `CVMLong`. Index order is byte order, so integer keys do not sort
numerically when negative or longer than 8 bytes.

## Testing

- Round trips of every type through JDBC and pgwire, at the boundaries of each range and length.
- Writes outside a column's constraint, and an unsupported type at `CREATE TABLE`, with their SQLSTATEs.
- [#757](https://github.com/Convex-Dev/convex/issues/757): a stored integer beyond 64 bits in a
  `NUMERIC(p,0)` column, and a `SUM` over `BIGINT` that exceeds 64 bits.
- Version 0 tables: existing schemas read with their original meanings and presented as above.
- Primary keys beyond 64 bits, and equal integers held in different representations.
- A non-conforming value delivered by merge: kept intact, and reported as `22000` when read.
