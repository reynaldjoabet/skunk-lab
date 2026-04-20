package idmgmt.repo

import cats.effect.*
import cats.syntax.all.*

import idmgmt.db.Codecs.*
import idmgmt.domain.*
import idmgmt.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait DeviceCodeRepo[F[_]] {

  def findByDeviceCode(deviceCode: String): F[Option[DeviceCode]]
  def findByUserCode(userCode: String): F[Option[DeviceCode]]
  def create(cmd: CreateDeviceCode): F[DeviceCode]
  def deleteByUserCode(userCode: String): F[Unit]
  def deleteExpired: F[Unit]

}

object DeviceCodeRepo {

  private val cols =
    "user_code, device_code, subject_id, session_id, client_id, description, creation_time, expiration, data"

  private object SQL {

    val findByDeviceCode: Query[String, DeviceCode] =
      sql"SELECT #$cols FROM idmgmt.device_codes WHERE device_code = $text".query(deviceCode)

    val findByUserCode: Query[String, DeviceCode] =
      sql"SELECT #$cols FROM idmgmt.device_codes WHERE user_code = $text".query(deviceCode)

    val insert: Query[
      String *: String *: String *: Option[String] *: java.time.OffsetDateTime *:
        java.time.OffsetDateTime *: String *: EmptyTuple,
      DeviceCode
    ] =
      sql"""
        INSERT INTO idmgmt.device_codes (user_code, device_code, client_id, description, creation_time, expiration, data)
        VALUES ($text, $text, $text, ${text.opt}, $timestamptz, $timestamptz, $text)
        RETURNING #$cols
      """.query(deviceCode)

    val deleteByUserCode: Command[String] =
      sql"DELETE FROM idmgmt.device_codes WHERE user_code = $text".command

    val deleteExpired: Command[Void] =
      sql"DELETE FROM idmgmt.device_codes WHERE expiration < now()".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[DeviceCodeRepo[F]] =
    for {
      m                 <- Instrumented.makeMetrics[F]("repo.device_code")
      pFindByDeviceCode <- s.prepare(SQL.findByDeviceCode)
      pFindByUserCode   <- s.prepare(SQL.findByUserCode)
      pInsert           <- s.prepare(SQL.insert)
      pDeleteByUserCode <- s.prepare(SQL.deleteByUserCode)
      pDeleteExpired    <- s.prepare(SQL.deleteExpired)
    } yield new DeviceCodeRepo[F] {

      def findByDeviceCode(dc: String): F[Option[DeviceCode]] =
        Instrumented.trace("DeviceCodeRepo.findByDeviceCode", m)(
          pFindByDeviceCode.option(dc)
        )

      def findByUserCode(uc: String): F[Option[DeviceCode]] =
        Instrumented.trace("DeviceCodeRepo.findByUserCode", m)(
          pFindByUserCode.option(uc)
        )

      def create(cmd: CreateDeviceCode): F[DeviceCode] =
        Instrumented.trace("DeviceCodeRepo.create", m)(
          pInsert.unique(
            (
              cmd.userCode,
              cmd.deviceCode,
              cmd.clientId,
              cmd.description,
              cmd.creationTime,
              cmd.expiration,
              cmd.data
            )
          )
        )

      def deleteByUserCode(uc: String): F[Unit] =
        Instrumented.trace("DeviceCodeRepo.deleteByUserCode", m)(
          pDeleteByUserCode.execute(uc).void
        )

      def deleteExpired: F[Unit] =
        Instrumented.trace("DeviceCodeRepo.deleteExpired", m)(
          pDeleteExpired.execute(Void).void
        )

    }

}
