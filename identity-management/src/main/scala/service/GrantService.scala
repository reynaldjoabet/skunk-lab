package idmgmt.service

import cats.effect.*
import cats.syntax.all.*

import idmgmt.domain.*
import idmgmt.repo.*
import idmgmt.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

trait GrantService[F[_]] {

  def findGrant(key: String): F[Option[PersistedGrant]]
  def storeGrant(cmd: CreatePersistedGrant): F[PersistedGrant]
  def revokeGrant(key: String): F[Unit]
  def cleanupExpiredGrants: F[Unit]
  def storeDeviceCode(cmd: CreateDeviceCode): F[DeviceCode]
  def lookupDeviceCode(deviceCode: String): F[Option[DeviceCode]]
  def cleanupExpiredDeviceCodes: F[Unit]

}

object GrantService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[GrantService[F]] =
    for {
      met    <- Instrumented.makeMetrics[F]("service.grant")
      grants <- PersistedGrantRepo.fromSession(s)
      codes  <- DeviceCodeRepo.fromSession(s)
    } yield new GrantService[F] {

      def findGrant(key: String): F[Option[PersistedGrant]] =
        Instrumented.trace("GrantService.findGrant", met)(
          grants.findByKey(key)
        )

      def storeGrant(cmd: CreatePersistedGrant): F[PersistedGrant] =
        Instrumented.trace("GrantService.storeGrant", met)(
          grants.create(cmd)
        )

      def revokeGrant(key: String): F[Unit] =
        Instrumented.trace("GrantService.revokeGrant", met)(
          grants.deleteByKey(key)
        )

      def cleanupExpiredGrants: F[Unit] =
        Instrumented.trace("GrantService.cleanupExpiredGrants", met)(
          grants.deleteExpired
        )

      def storeDeviceCode(cmd: CreateDeviceCode): F[DeviceCode] =
        Instrumented.trace("GrantService.storeDeviceCode", met)(
          codes.create(cmd)
        )

      def lookupDeviceCode(deviceCode: String): F[Option[DeviceCode]] =
        Instrumented.trace("GrantService.lookupDeviceCode", met)(
          codes.findByDeviceCode(deviceCode)
        )

      def cleanupExpiredDeviceCodes: F[Unit] =
        Instrumented.trace("GrantService.cleanupExpiredDeviceCodes", met)(
          codes.deleteExpired
        )

    }

}
