# skunk-lab
A financial ledger application (LedgerPay) built with Scala 3, Skunk v1.0.0, and Cats Effect. Demonstrates production patterns for Skunk's PostgreSQL wire protocol driver.

## Skunk Fundamentals
`Skunk` is a functional data access layer for Postgres. It speaks the Postgres wire protocol directly (no JDBC). It will not work with any other database backend.

**Statements** — Skunk recognizes two classes of statements: `Query`, for statements that return rows; and `Command`, for statements that do not return rows. These values are typically constructed via the `sql` interpolator.

**Session** — Skunk's central abstraction is the `Session`, which represents a connection to Postgres. From the `Session` we can produce prepared statements, cursors, and other resources that ultimately depend on their originating `Session`.

**Codecs** — When you construct a statement each parameter is specified via an `Encoder`, and row data is specified via a `Decoder`. In some cases encoders and decoders are symmetric and are defined together, as a `Codec`. This is closest in spirit to the strategy adopted by scodec.

### Twiddle Lists (v1.0.0 — replaces `~` HLists)
v1.0.0 standardizes on the `*:` twiddle-list syntax (from the Twiddles library) and deprecates the old `~` pair-based syntax:

```scala
// v1.0.0 — twiddle list via *:
val user: Decoder[User] =
  (uuid *: varchar *: varchar *: kycLevel *: timestamptz *: timestamptz).to[User]

// Deprecated — old ~ pair syntax
// (uuid ~ varchar ~ varchar ~ kycLevel ~ timestamptz ~ timestamptz).gimap[User]
```

### Simple Queries
A simple query has no parameters and generally isn't going to be reused. `Session` provides:
- `execute` returns `F[List[A]]`: All results, as a list.
- `option` returns `F[Option[A]]`: Zero or one result, otherwise an error is raised.
- `unique` returns `F[A]`: Exactly one result, otherwise an error is raised.

```scala
def execute[A](query: Query[Void, A]): F[List[A]]
def option[A](query: Query[Void, A]): F[Option[A]]
def unique[A](query: Query[Void, A]): F[A]
```

### Extended (Prepared) Queries
An extended query is a query with parameters, executed via the extended query protocol. Postgres provides prepared statements that can be reused with different arguments, and cursors for paging/streaming.

`PreparedQuery` provides:

| Method | Return Type | Description |
|--------|-------------|-------------|
| `stream` | `Stream[F, B]` | All results, as a stream |
| `option` | `F[Option[B]]` | Zero or one result |
| `unique` | `F[B]` | Exactly one result |
| `cursor` | `Resource[F, Cursor[F, B]]` | A cursor that returns pages of results |
| `pipe` | `Pipe[F, A, B]` | Executes the query for each input value |

Only `Query[A, B]` and `Command[A]` can be turned into prepared statements.

### When to Use Simple vs Extended
**Simple query protocol** (`Session#execute`) — slightly more efficient in message exchange. Use when:
- Your query has no parameters; and
- you are querying for a small number of rows; and
- you will be using the query only once per session.

**Extended query protocol** (`Session#prepare`) — more powerful and general. Use when:
- Your query has parameters; and/or
- you are querying for a large or unknown number of rows; and/or
- you intend to stream the results; and/or
- you will be using the query more than once per session.

## Key Patterns
### Custom Enum Codecs
The project uses Postgres custom enums with `TypingStrategy.SearchPath`:

```scala
val accountStatus: Codec[AccountStatus] =
  enum(_.label, AccountStatus.fromLabel, Type("account_status"))

val currencyCode: Codec[CurrencyCode] =
  enum(_.label, CurrencyCode.fromLabel, Type("currency_code"))
```
### Prepared Statement Construction via `mapN`
Repos prepare all statements upfront and compose them with `mapN`:

```scala
def fromSession[F[_]: Concurrent](s: Session[F]): F[AccountRepo[F]] =
  (
    s.prepare(SQL.insert),
    s.prepare(SQL.findById),
    s.prepare(SQL.updateBalance),
    s.prepare(SQL.freeze)
  ).mapN { (pInsert, pFind, pUpdateBal, pFreeze) =>
    new AccountRepo[F] { ... }
  }
```

### Serializable Transactions for Money Movement
Transfers use `Serializable` isolation with deterministic lock ordering to prevent deadlocks:

```scala
s.transaction(TransactionIsolationLevel.Serializable, TransactionAccessMode.ReadWrite)
  .use { xa =>
    val first  = if (req.fromAccountId.compareTo(req.toAccountId) < 0)
                   req.fromAccountId else req.toAccountId
    val second = if (req.fromAccountId.compareTo(req.toAccountId) < 0)
                   req.toAccountId else req.fromAccountId
    for {
      _ <- accounts.findByIdForUpdate(first)   // lock in UUID order
      _ <- accounts.findByIdForUpdate(second)
      // ... validate, debit, credit, record
    } yield tx
  }
```
### Dynamic SQL with `AppliedFragment`

`TransactionSearchRepo` builds filterable, paginated queries safely:

```scala
val conds: List[AppliedFragment] = List(
  f.accountId.map(id => sql"(source_acct_id = $uuid OR dest_acct_id = $uuid)"((id, id))),
  f.txType.map(t => sql"tx_type = $txType"(t)),
  f.minAmount.map(min => sql"amount >= $numeric"(min)),
).flatten

val where = if (conds.isEmpty) AppliedFragment.empty
            else conds.foldSmash(void" WHERE ", void" AND ", AppliedFragment.empty)
```

### LISTEN/NOTIFY for Real-Time Events
`NotificationService` uses a dedicated session for `LISTEN` (never shared with query traffic):

```scala
def make[F[_]: Concurrent](
  listenerSession: Session[F],  // dedicated for LISTEN
  writerSession:   Session[F]   // any session for NOTIFY
): NotificationService[F]
```

### JSONB Audit Logging
`AuditRepo` uses the circe `jsonb` codec for structured audit payloads:
```scala
import skunk.circe.codec.json.jsonb
private val auditEntry: Decoder[AuditEntry] =
  (int8 *: varchar *: uuid *: varchar *: uuid.opt *: jsonb.opt *: timestamptz).to[AuditEntry]
```

## Database Schema
The schema uses custom enum types, foreign keys, and indexes:

| Table | Purpose |
|-------|---------|
| `users` | User accounts with KYC levels |
| `accounts` | Multi-currency accounts per user (unique per user+currency) |
| `transactions` | Immutable ledger with idempotency keys |
| `audit_log` | Append-only audit trail with JSONB payloads |

Relationships:
- `One-to-Many` between Users and Accounts (one user can have multiple currency accounts)
- `One-to-Many` between Accounts and Transactions (via `source_acct_id` / `dest_acct_id`)

## Cats Effect Type Class Hierarchy
```
Async
├── Sync
│   ├── MonadCancel[F, Throwable]
│   │   ├── MonadError[F, Throwable]
│   │   │   ├── ApplicativeError
│   │   │   └── Monad
│   │   │       ├── FlatMap
│   │   │       └── Applicative
│   │   └── Unique
│   ├── Clock
│   └── Defer
└── Temporal
    ├── GenConcurrent
    │   └── GenSpawn
    └── Clock
```
## References
- [Skunk documentation](https://typelevel.org/skunk/)
- [Skunk on Baeldung](https://www.baeldung.com/scala/skunk-postgresql-driver)
- [Scala functional database libraries](https://medium.com/rahasak/scala-functional-database-libraries-31364b2cf7b2)
- [Tuples in Scala 3](https://www.scala-lang.org/2021/02/26/tuples-bring-generic-programming-to-scala-3.html)
- [From Shapeless to Scala 3](http://www.limansky.me/posts/2021-07-26-from-scala-2-shapeless-to-scala-3.html)


## Wallet
Double-entry bookkeeping means every money movement is recorded as two entries that sum to zero:
- A debit (money leaves one account)
- A credit (money enters another account)

For example, if `User A` sends $50 to `User B`, the ledger records:

| Entry | Wallet   | Amount  | Type   |
|-------|----------|---------|--------|
| 1     | `User A` | −$50.00 | Debit  |
| 2     | `User B` | +$50.00 | Credit |
|       | **Net**  | **$0.00** |      |

`ledger_entry_types` has just two values — `debit` and `credit`   

`every money movement = 2 entries that sum to zero`