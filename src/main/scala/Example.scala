import java.time.LocalDate

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream

import skunk.codec.all.*
import skunk.codec.text.bpchar
import skunk.data.TransactionAccessMode as Mode
import skunk.data.TransactionIsolationLevel as Iso
import skunk.implicits.*
import skunk.Channel
import skunk.Codec
import skunk.Command
import skunk.Query
import skunk.Session
import skunk.Void

object Example {

  case class Row(id: Long, email: String, country: String)

  val rowCodec: Codec[Row] = (int8 *: varchar *: bpchar(2)).to[Row]

  // Bulk insert in one round trip — values.list(n)
  def insertMany(n: Int): Command[List[Row]] =
    sql"INSERT INTO users (id, email, country) VALUES ${rowCodec.values.list(n)}".command

  // Provided by the surrounding app; stubbed here so the file compiles standalone.
  val sessions: Resource[IO, Session[IO]] = ???

  def bulkInsert(rows: List[Row]): IO[Unit] =
    sessions.use { s =>
      rows
        .grouped(1000)
        .toList
        .traverse_ { batch =>
          s.prepare(insertMany(batch.length)).flatMap(_.execute(batch).void)
        }
    }

  // pipe — streaming a producer of writes
  def ingest(src: Stream[IO, Row]): IO[Unit] =
    sessions.use { s =>
      src
        .chunkN(1000) // pack 1k at a time
        .map(_.toList)
        .evalMap { batch =>
          s.prepare(insertMany(batch.length)).flatMap(_.execute(batch))
        }
        .compile
        .drain
    }

  // stream — never load a big result set into memory

  final case class User(id: Long, email: String)

  val allUsers: Query[Void, User] =
    sql"SELECT id, email FROM users ORDER BY id".query(int8 *: varchar).to[User]

  def exportUsers(sink: fs2.Pipe[IO, User, Unit]): IO[Unit] =
    sessions.use { s =>
      Stream
        .eval(s.prepare(allUsers))
        .flatMap(_.stream(Void, chunkSize = 4096))
        .through(sink)
        .compile
        .drain
    }
  // Skunk pages with a portal cursor under the hood. chunkSize is the sweet spot:
  // too small = many Execute round trips, too big = head-of-line latency.
  // 4096 is a good default for narrow rows; 256–1024 for wide rows.

  // Read-only repeatable-read for analytics endpoints.
  // Postgres optimises for read-only snapshots. If your endpoint just queries:

  final case class Report(totalUsers: Long, totalRevenue: BigDecimal, topCountries: List[String])

  val totalUsers: Query[LocalDate, Long]         = ???
  val totalRevenue: Query[LocalDate, BigDecimal] = ???
  val topCountries: Query[LocalDate, String]     = ???

  def report(month: LocalDate): IO[Report] =
    sessions.use { s =>
      s.transaction(Iso.RepeatableRead, Mode.ReadOnly)
        .use { _ =>
          for {
            pUsers   <- s.prepare(totalUsers)
            pRevenue <- s.prepare(totalRevenue)
            pTop     <- s.prepare(topCountries)
            a        <- pUsers.unique(month)
            b        <- pRevenue.unique(month)
            c        <- pTop.stream(month, 64).compile.toList
          } yield Report(a, b, c)
        }
    }
  // READ ONLY lets PG skip lock bookkeeping; REPEATABLE READ gives you a consistent
  // snapshot across all three queries — no torn reads if a write commits between them.

  // LISTEN / NOTIFY — replace polling with push.
  // If you currently SELECT * FROM jobs WHERE state = 'pending' every 500ms, stop:

  def processJob(value: String): IO[Unit] = ???

  // producer side — open a session, send NOTIFY jobs_changed, 'queued'
  def enqueueJob: IO[Unit] =
    sessions.use { s =>
      val jobs: Channel[IO, String, String] = s.channel(id"jobs_changed")
      jobs.notify("queued")
    }

  // consumer side — one long-lived session
  def runJobConsumer: IO[Unit] =
    sessions.use { s =>
      s.channel(id"jobs_changed")
        .listen(maxQueued = 1024) // Stream[IO, Notification[String]]
        .evalMap(n => processJob(n.value)).compile.drain
    }

}
