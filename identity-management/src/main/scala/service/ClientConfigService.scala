package idmgmt.service

import cats.effect.*
import cats.syntax.all.*

import idmgmt.domain.*
import idmgmt.repo.*
import idmgmt.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait ClientConfigService[F[_]] {

  def getClient(clientId: String): F[Option[Client]]

  def registerClient(
    cmd: CreateClient,
    scopes: List[String],
    grantTypes: List[String],
    redirectUris: List[String]
  ): F[Client]

  def deleteClient(id: Int): F[Unit]
  def setClientEnabled(id: Int, enabled: Boolean): F[Unit]

}

object ClientConfigService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ClientConfigService[F]] =
    for {
      met     <- Instrumented.makeMetrics[F]("service.client_config")
      clients <- ClientRepo.fromSession(s)
    } yield new ClientConfigService[F] {

      def getClient(clientId: String): F[Option[Client]] =
        Instrumented.trace("ClientConfigService.getClient", met)(
          clients.findByClientId(clientId)
        )

      def registerClient(
        cmd: CreateClient,
        scopes: List[String],
        grantTypes: List[String],
        redirectUris: List[String]
      ): F[Client] =
        Instrumented.trace("ClientConfigService.registerClient", met) {
          for {
            c <- clients.create(cmd)
            _ <- scopes.traverse_(scope => clients.addScope(c.id, scope))
            _ <- grantTypes.traverse_(gt => clients.addGrantType(c.id, gt))
            _ <- redirectUris.traverse_(uri => clients.addRedirectUri(c.id, uri))
          } yield c
        }

      def deleteClient(id: Int): F[Unit] =
        Instrumented.trace(
          "ClientConfigService.deleteClient",
          met,
          Attribute("client.db_id", id.toLong)
        )(
          clients.delete(id)
        )

      def setClientEnabled(id: Int, enabled: Boolean): F[Unit] =
        Instrumented.trace(
          "ClientConfigService.setClientEnabled",
          met,
          Attribute("client.db_id", id.toLong)
        )(
          clients.setEnabled(id, enabled)
        )

    }

}
