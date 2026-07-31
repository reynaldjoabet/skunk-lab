import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.Date

import cats.effect.kernel.Resource
import cats.effect.IO

import skunk.{Session, _}
import skunk.codec.all._
import skunk.implicits._

/**
  * implement toggles repository functions
  * @param connectionPool
  *   skunk postgres session pool
  */
class ToggleRepoLiveSkunk(connectionPool: Resource[IO, Resource[IO, Session[IO]]])
    extends ToggleRepo {

  /**
    * toggles timestamp filed in database stored as java.time.LocalDateTime, toggles case class
    * timestamp field defined as java.util.Date so need function to convert java.util.Date into
    * java.time.LocalDateTime when saving data
    * @param date
    *   util date
    * @return
    *   local date
    */
  def toLocalDateTime(date: Date): LocalDateTime = {
    val instant = Instant.ofEpochMilli(date.getTime)
    LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
  }

  /**
    * toggles timestamp filed in database stored as java.time.LocalDateTime, toggles case class
    * timestamp field defined as java.util.Data so need function to convert java.time.LocalDateTime
    * into java.util.Date when quering the data
    * @param ldt
    *   local date
    * @return
    *   util date
    */
  def toDate(ldt: LocalDateTime): Date = {
    val instant = ldt.toInstant(ZoneOffset.UTC)
    Date.from(instant)
  }

  /**
    * decode postgres data types into toggles case class convert postgres timestamp into util.date
    */
  val toggleDecoder: Decoder[Toggle] =
    (uuid ~ varchar(50) ~ text ~ text ~ timestamp).map { case i ~ n ~ s ~ v ~ t =>
      Toggle(Option(i), n, s, v, toDate(t))
    }

  /**
    * encode toggles case class data into postgres data types convert toggle case class util.Date
    * into postgres timestamp
    */
  val toggleEncoder: Encoder[Toggle] = (uuid.opt ~ varchar(50) ~ text ~ text ~ timestamp).values
    .contramap[Toggle](t => t.id ~ t.name ~ t.service ~ t.value ~ toLocalDateTime(t.timestamp))

  def init() = {
    // define query as skunk command
    // query parameters passed as postgres types/codecs
    val q: Command[Void] =
      sql"""
         CREATE TABLE IF NOT EXISTS toggles(
            id UUID PRIMARY KEY,
            name VARCHAR(50),
            service TEXT,
            value TEXT,
            timestamp TIMESTAMP
         );
      """.command

    // execute command directly since nothing returned
    connectionPool.use { session =>
      session.use(s => s.execute(q))
    }
  }

  override def createToggle(toggle: Toggle): IO[Unit] = {
    // define query as skunk command
    // use toggle encoder to map toggle case class fields into postgres types
    val q: Command[Toggle] =
      sql"""
         INSERT INTO toggles(id, name, service, value, timestamp)
         VALUES $toggleEncoder
       """.command

    // execute command directly since nothing returned
    connectionPool.use { session =>
      session.use(_.prepare(q).flatMap(_.execute(toggle)).void)
    }
  }

  override def updateToggle(name: String, value: String): IO[Unit] = {
    // define query as skunk command
    // query parameters passed as postgres types/codecs
    val q: Command[String *: String *: EmptyTuple] =
      sql"""
        UPDATE toggles
        SET value = $text
        WHERE name = $text
      """.command

    // execute command directly since nothing returned
    connectionPool.use { session =>
      session.use(_.prepare(q).flatMap(_.execute(value ~ name)).void)
    }
  }

  override def deleteToggle(name: String): IO[Unit] = {
    // define query as skunk command
    // query parameters passed as postgres types/codecs
    val q: Command[String] =
      sql"""
        DELETE FROM toggles
        WHERE name = $text
      """.command

    // execute command directly since nothing returned
    connectionPool.use { session =>
      session.use(_.prepare(q).flatMap(_.execute(name)).void)
    }
  }

  override def getToggle(name: String): IO[Option[Toggle]] = {
    val q: Query[String, Toggle] = {
      // define query as skunk query
      // used toggle decoder to decode postgres types into toggle case class
      sql"""
        SELECT id, name, service, value, timestamp
        FROM toggles
        WHERE name = $text
        LIMIT 1
      """.query(toggleDecoder)
    }

    // create prepared statement with binding query parameters
    // execute query as fs2 stream
    // chunk size defines number of rows need to be fetched at once
    // getting a single row or throw error if not exists
    connectionPool.use { session =>
      session.use(
        _.prepare(q)
          .flatMap { ps =>
            ps.stream(name, 64).compile.last
          }
      )
    }
  }

  override def getServiceToggles(service: String): IO[List[Toggle]] = {
    // define query as skunk query
    // used toggle decoder to decode postgres types into toggle case class
    val q: Query[String, Toggle] =
      sql"""
        SELECT id, name, service, value, timestamp
        FROM toggles
        WHERE service = $text
      """.query(toggleDecoder)

    // create prepared statement with binding query parameters
    // execute query as fs2 stream
    // chunk size defines number of rows need to be fetched at once
    // getting list rows
    connectionPool.use { session =>
      session.use(
        _.prepare(q)
          .flatMap { ps =>
            ps.stream(service, 32).compile.toList
          }
      )
    }
  }

  override def getAllToggles: IO[List[Toggle]] = {
    // define query as skunk query
    // used toggle decoder to decode postgres types into toggle case class
    val q: Query[Void, Toggle] =
      sql"""
        SELECT id, name, service, value, timestamp
        FROM toggles
      """.query(toggleDecoder)

    // execute query directly with session
    connectionPool.use { session =>
      session.use(_.execute(q))
    }
  }

}
