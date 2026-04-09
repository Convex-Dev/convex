---
name: convex-db
description: Use Convex DB — a lattice-backed SQL database. Use when helping users write queries, connect via JDBC or PostgreSQL clients, create tables, insert/query data, or use the direct lattice API.
argument-hint: "[query|connect|create|example]"
---

# Using Convex DB

Convex DB provides SQL access over lattice data. Connect via JDBC, PostgreSQL wire protocol, or the direct lattice API.

**Reference:** `convex-db/README.md` for full documentation including replication, PostgreSQL server setup, and architecture details.

## Connecting

### JDBC (Java)

```java
// In-memory
Connection conn = DriverManager.getConnection("jdbc:convex:mydb");

// Persistent (Etch-backed, survives restarts)
Connection conn = DriverManager.getConnection("jdbc:convex:file:/data/mydb.etch");
```

Driver auto-registers via ServiceLoader. Class: `convex.db.jdbc.ConvexDriver`

### PostgreSQL Clients (psql, DBeaver, DataGrip, Python, etc.)

```bash
# Start the PG server
java -cp convex-db.jar convex.db.psql.PgServer -p 5432 -d mydb

# Then connect with any PG client
psql -h localhost -p 5432 -d mydb
```

```python
import psycopg2
conn = psycopg2.connect(host="localhost", port=5432, dbname="mydb")
```

## Creating Tables

```sql
CREATE TABLE users (id, name, email)
```

Column 0 (first column) is always the primary key. Types are inferred from inserted data.

## Inserting Data

```sql
INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')
```

For bulk loading, use prepared statements with batch:

```java
PreparedStatement ps = conn.prepareStatement("INSERT INTO users VALUES (?, ?, ?)");
for (int i = 0; i < 10000; i++) {
    ps.setLong(1, i);
    ps.setString(2, "Name-" + i);
    ps.setString(3, "email-" + i + "@example.com");
    ps.addBatch();
}
ps.executeBatch();
```

## Querying

```sql
-- Point lookup (fast — O(log n) via PK index pushdown)
SELECT * FROM users WHERE id = 1

-- Filtering, sorting, pagination
SELECT name, email FROM users WHERE name LIKE 'A%' ORDER BY name LIMIT 10

-- Joins
SELECT c.name, o.amount
FROM customers c INNER JOIN orders o ON c.id = o.customer_id

-- Aggregations
SELECT department, COUNT(*), AVG(salary)
FROM employees GROUP BY department HAVING COUNT(*) > 5
```

### Supported SQL

- **DDL:** `CREATE TABLE`, `DROP TABLE`
- **DML:** `INSERT`, `UPDATE`, `DELETE`
- **Queries:** `SELECT`, `WHERE`, `ORDER BY`, `LIMIT`, `OFFSET`
- **Joins:** `INNER JOIN`, `LEFT JOIN`, `RIGHT JOIN`, `CROSS JOIN`
- **Aggregations:** `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- **Expressions:** `CASE WHEN`, `COALESCE`, `CAST`, `BETWEEN`, `IN`, `LIKE`, `IS NULL`
- **Functions:** `ABS`, `FLOOR`, `CEIL`, `SQRT`, `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`, `LENGTH`, `CONCAT`

## Transactions

```java
conn.setAutoCommit(false);
stmt.execute("INSERT INTO users VALUES (2, 'Bob', 'bob@example.com')");
stmt.execute("UPDATE users SET email = 'new@example.com' WHERE id = 1");
conn.commit();    // atomic merge — all changes become visible
// or conn.rollback() to discard
```

## Column Types

| SQL Type | CVM Type | Notes |
|----------|----------|-------|
| BIGINT / INTEGER | CVMLong | 64-bit signed integer |
| DOUBLE | CVMDouble | 64-bit float |
| VARCHAR | AString | Unicode string |
| BOOLEAN | CVMBool | true/false |
| VARBINARY / BLOB | ABlob | Binary data |
| TIMESTAMP | CVMLong | Milliseconds since epoch |
| ANY | ACell | Dynamic type |

## Direct Lattice API

For programmatic access without SQL overhead:

```java
ConvexDB cdb = ConvexDB.create();
SQLDatabase db = cdb.database("mydb");

// Create table
db.tables().createTable("users", new String[]{"id", "name", "email"});

// Insert
db.tables().insert("users", 1, "Alice", "alice@example.com");

// Point lookup
AVector<ACell> row = db.tables().selectByKey("users", 1);

// Scan all
Index<ABlob, AVector<ACell>> all = db.tables().selectAll("users");

// Delete
db.tables().deleteByKey("users", 1);
```

## Performance Tips

- **Use PK lookups** (`WHERE id = ?`) for point queries — O(log n) via index pushdown
- **Use PreparedStatements** — plans compile once, reuse across executions
- **Use batch inserts** for bulk loading — significantly faster than individual statements
- **Full scans** are O(n) — filter on PK when possible

## Building and Testing

```bash
# Build (requires Convex core installed first)
cd convex && mvn clean install -DskipTests -pl convex-db -am

# Run tests
cd convex && mvn test -pl convex-db
```
