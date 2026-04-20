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

trait ResourceConfigService[F[_]] {

  def createApiResource(cmd: CreateApiResource): F[ApiResource]
  def deleteApiResource(id: Int): F[Unit]
  def createApiScope(cmd: CreateApiScope): F[ApiScope]
  def deleteApiScope(id: Int): F[Unit]
  def createIdentityResource(cmd: CreateIdentityResource): F[IdentityResource]
  def deleteIdentityResource(id: Int): F[Unit]
  def createIdentityProvider(cmd: CreateIdentityProvider): F[IdentityProvider]
  def deleteIdentityProvider(id: Int): F[Unit]

}

object ResourceConfigService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ResourceConfigService[F]] =
    for {
      met    <- Instrumented.makeMetrics[F]("service.resource_config")
      apiRes <- ApiResourceRepo.fromSession(s)
      scopes <- ApiScopeRepo.fromSession(s)
      idRes  <- IdentityResourceRepo.fromSession(s)
      idps   <- IdentityProviderRepo.fromSession(s)
    } yield new ResourceConfigService[F] {

      def createApiResource(cmd: CreateApiResource): F[ApiResource] =
        Instrumented.trace("ResourceConfigService.createApiResource", met)(
          apiRes.create(cmd)
        )

      def deleteApiResource(id: Int): F[Unit] =
        Instrumented.trace(
          "ResourceConfigService.deleteApiResource",
          met,
          Attribute("api_resource.id", id.toLong)
        )(
          apiRes.delete(id)
        )

      def createApiScope(cmd: CreateApiScope): F[ApiScope] =
        Instrumented.trace("ResourceConfigService.createApiScope", met)(
          scopes.create(cmd)
        )

      def deleteApiScope(id: Int): F[Unit] =
        Instrumented.trace(
          "ResourceConfigService.deleteApiScope",
          met,
          Attribute("api_scope.id", id.toLong)
        )(
          scopes.delete(id)
        )

      def createIdentityResource(cmd: CreateIdentityResource): F[IdentityResource] =
        Instrumented.trace("ResourceConfigService.createIdentityResource", met)(
          idRes.create(cmd)
        )

      def deleteIdentityResource(id: Int): F[Unit] =
        Instrumented.trace(
          "ResourceConfigService.deleteIdentityResource",
          met,
          Attribute("identity_resource.id", id.toLong)
        )(
          idRes.delete(id)
        )

      def createIdentityProvider(cmd: CreateIdentityProvider): F[IdentityProvider] =
        Instrumented.trace("ResourceConfigService.createIdentityProvider", met)(
          idps.create(cmd)
        )

      def deleteIdentityProvider(id: Int): F[Unit] =
        Instrumented.trace(
          "ResourceConfigService.deleteIdentityProvider",
          met,
          Attribute("idp.id", id.toLong)
        )(
          idps.delete(id)
        )

    }

}
