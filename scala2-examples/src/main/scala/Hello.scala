import cats.effect._

import org.typelevel.otel4s.metrics.Meter.Implicits.{noop => noopMeter}
import org.typelevel.otel4s.trace.Tracer.Implicits.{noop => noopTracer} // (1)
import skunk._
import skunk.codec.all._
import skunk.implicits._

object Hello extends IOApp {

// Command->SQL and parameter encoder for a statement that returns no rows
  val commad: Command[Short *: String *: EmptyTuple] =
    sql"INSERT INTO foo VALUES ($int2, $varchar)".command // Command[(Short, String)]
// Query-> SQL, parameter encoder, and row decoder for a statement that returns rows

  val query: Query[Short, String *: Short *: EmptyTuple] =
    sql"SELECT name, age FROM person WHERE age > $int2".query(varchar *: int2) // Query[Short, (String, Short)]

  val q = sql"SELECT id, name FROM  employee WHERE  age > $int2 ".query(int4 ~ varchar) // Query[Short, Long ~ String]

//A simple query is a query with no parameters.
//Postgres provides a protocol for execution of simple queries, returning all rows at once (Skunk returns them as a list). Such queries can be passed directly to Session.execute.

//s.execute(a) // IO[List[String]]

  /**
    * In the above example note that query parameters are specified by interpolated `Encoder`s and
    * column types are specified by a `Decoder` passed to `.query`. The `~` syntax constructs
    * left-associated HLists of types and values via nested pairs. These are all described in more
    * detail a bit further down. Commands are constructed in a similar way but have no output
    * columns and thus no `Decoder` is needed.
    */

  val c = sql"""UPDATE employee SET    salary = salary * 1.05 WHERE  id = $int8""".command // Command[Long]

  val session: Resource[IO, Session[IO]] =
    Session.single( // (2)
      host = "localhost",
      port = 5432,
      user = "jimmy",
      database = "world",
      password = Some("banana")
    )
//session.map(_.)

//construct a Query that selects a single column of schema type date (yielding d, a value of type java.time.LocalDate), then we ask the session to execute it, expecting a unique value back;
  def run(args: List[String]): IO[ExitCode] =
    session.use { s => // (3)
      for {
        d <- s.unique(sql"select current_date".query(date)) // (4)
        _ <- IO.println(s"The current date is $d.")
      } yield ExitCode.Success
    }

}
