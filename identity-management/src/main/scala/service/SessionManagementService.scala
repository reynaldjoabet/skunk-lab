package idmgmt.service

import cats.effect.*
import cats.syntax.all.*

import idmgmt.domain.*
import idmgmt.repo.*
import idmgmt.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

trait SessionManagementService[F[_]] {

  def createSession(cmd: CreateServerSideSession): F[ServerSideSession]
  def findSession(key: String): F[Option[ServerSideSession]]
  def findSessionsBySubject(subjectId: String): F[List[ServerSideSession]]
  def revokeSession(key: String): F[Unit]
  def touchSession(key: String): F[Unit]
  def cleanupExpired: F[Unit]

}

object SessionManagementService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[SessionManagementService[F]] =
    for {
      met      <- Instrumented.makeMetrics[F]("service.session_mgmt")
      sessions <- ServerSideSessionRepo.fromSession(s)
    } yield new SessionManagementService[F] {

      def createSession(cmd: CreateServerSideSession): F[ServerSideSession] =
        Instrumented.trace("SessionManagementService.createSession", met)(
          sessions.create(cmd)
        )

      def findSession(key: String): F[Option[ServerSideSession]] =
        Instrumented.trace("SessionManagementService.findSession", met)(
          sessions.findByKey(key)
        )

      def findSessionsBySubject(subjectId: String): F[List[ServerSideSession]] =
        Instrumented.trace("SessionManagementService.findSessionsBySubject", met)(
          sessions.findBySubjectId(subjectId)
        )

      def revokeSession(key: String): F[Unit] =
        Instrumented.trace("SessionManagementService.revokeSession", met)(
          sessions.deleteByKey(key)
        )

      def touchSession(key: String): F[Unit] =
        Instrumented.trace("SessionManagementService.touchSession", met)(
          sessions.renewSession(key)
        )

      def cleanupExpired: F[Unit] =
        Instrumented.trace("SessionManagementService.cleanupExpired", met)(
          sessions.deleteExpired
        )

    }

}
