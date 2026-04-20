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

trait ClientRepo[F[_]] {

  def findById(id: Int): F[Option[Client]]
  def findByClientId(clientId: String): F[Option[Client]]
  def create(cmd: CreateClient): F[Client]
  def delete(id: Int): F[Unit]
  def setEnabled(id: Int, enabled: Boolean): F[Unit]
  def listScopes(clientDbId: Int): F[List[ClientScope]]
  def addScope(clientDbId: Int, scope: String): F[ClientScope]
  def removeScope(clientDbId: Int, scope: String): F[Unit]
  def listRedirectUris(clientDbId: Int): F[List[ClientRedirectUri]]
  def addRedirectUri(clientDbId: Int, uri: String): F[ClientRedirectUri]
  def removeRedirectUri(clientDbId: Int, uri: String): F[Unit]
  def listGrantTypes(clientDbId: Int): F[List[ClientGrantType]]
  def addGrantType(clientDbId: Int, grantType: String): F[ClientGrantType]
  def removeGrantType(clientDbId: Int, grantType: String): F[Unit]

}

object ClientRepo {

  private val cols =
    "id, client_id, client_name, protocol_type, enabled, description, client_uri, logo_uri, " +
      "require_client_secret, require_pkce, allow_plain_text_pkce, require_request_object, allow_offline_access, " +
      "access_token_lifetime, authorization_code_lifetime, identity_token_lifetime, " +
      "refresh_token_usage, refresh_token_expiration, absolute_refresh_token_lifetime, sliding_refresh_token_lifetime, " +
      "ciba_lifetime, polling_interval, coordinate_lifetime_with_user_session, " +
      "require_dpop, dpop_validation_mode, dpop_clock_skew, " +
      "initiate_login_uri, pushed_authorization_lifetime, require_pushed_authorization, " +
      "created, updated, last_accessed, non_editable"

  private object SQL {

    val findById: Query[Int, Client] =
      sql"SELECT #$cols FROM clients WHERE id = $int4".query(client)

    val findByClientId: Query[String, Client] =
      sql"SELECT #$cols FROM clients WHERE client_id = $text".query(client)

    val insert: Query[
      String *: Option[String] *: String *: Option[String] *: Boolean *: Boolean *: Boolean *:
        EmptyTuple,
      Client
    ] =
      sql"""
        INSERT INTO clients (client_id, client_name, protocol_type, description, require_client_secret, require_pkce, allow_offline_access)
        VALUES ($text, ${text.opt}, $text, ${text.opt}, $bool, $bool, $bool)
        RETURNING #$cols
      """.query(client)

    val delete: Command[Int] =
      sql"DELETE FROM clients WHERE id = $int4".command

    val setEnabled: Command[Boolean *: Int *: EmptyTuple] =
      sql"UPDATE clients SET enabled = $bool, updated = now() WHERE id = $int4".command

    // Scopes
    val listScopes: Query[Int, ClientScope] =
      sql"SELECT id, scope, client_id FROM client_scopes WHERE client_id = $int4".query(clientScope)

    val addScope: Query[String *: Int *: EmptyTuple, ClientScope] =
      sql"INSERT INTO client_scopes (scope, client_id) VALUES ($text, $int4) RETURNING id, scope, client_id"
        .query(clientScope)

    val removeScope: Command[String *: Int *: EmptyTuple] =
      sql"DELETE FROM client_scopes WHERE scope = $text AND client_id = $int4".command

    // Redirect URIs
    val listRedirectUris: Query[Int, ClientRedirectUri] =
      sql"SELECT id, redirect_uri, client_id FROM client_redirect_uris WHERE client_id = $int4"
        .query(clientRedirectUri)

    val addRedirectUri: Query[String *: Int *: EmptyTuple, ClientRedirectUri] =
      sql"INSERT INTO client_redirect_uris (redirect_uri, client_id) VALUES ($text, $int4) RETURNING id, redirect_uri, client_id"
        .query(clientRedirectUri)

    val removeRedirectUri: Command[String *: Int *: EmptyTuple] =
      sql"DELETE FROM client_redirect_uris WHERE redirect_uri = $text AND client_id = $int4".command

    // Grant types
    val listGrantTypes: Query[Int, ClientGrantType] =
      sql"SELECT id, grant_type, client_id FROM client_grant_types WHERE client_id = $int4"
        .query(clientGrantType)

    val addGrantType: Query[String *: Int *: EmptyTuple, ClientGrantType] =
      sql"INSERT INTO client_grant_types (grant_type, client_id) VALUES ($text, $int4) RETURNING id, grant_type, client_id"
        .query(clientGrantType)

    val removeGrantType: Command[String *: Int *: EmptyTuple] =
      sql"DELETE FROM client_grant_types WHERE grant_type = $text AND client_id = $int4".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ClientRepo[F]] =
    for {
      m               <- Instrumented.makeMetrics[F]("repo.client")
      pFindById       <- s.prepare(SQL.findById)
      pFindByClientId <- s.prepare(SQL.findByClientId)
      pInsert         <- s.prepare(SQL.insert)
      pDelete         <- s.prepare(SQL.delete)
      pSetEnabled     <- s.prepare(SQL.setEnabled)
      pListScopes     <- s.prepare(SQL.listScopes)
      pAddScope       <- s.prepare(SQL.addScope)
      pRemoveScope    <- s.prepare(SQL.removeScope)
      pListRedirects  <- s.prepare(SQL.listRedirectUris)
      pAddRedirect    <- s.prepare(SQL.addRedirectUri)
      pRemoveRedirect <- s.prepare(SQL.removeRedirectUri)
      pListGrants     <- s.prepare(SQL.listGrantTypes)
      pAddGrant       <- s.prepare(SQL.addGrantType)
      pRemoveGrant    <- s.prepare(SQL.removeGrantType)
    } yield new ClientRepo[F] {

      def findById(id: Int): F[Option[Client]] =
        Instrumented.trace("ClientRepo.findById", m, Attribute("client.db_id", id.toLong))(
          pFindById.option(id)
        )

      def findByClientId(clientId: String): F[Option[Client]] =
        Instrumented.trace("ClientRepo.findByClientId", m)(
          pFindByClientId.option(clientId)
        )

      def create(cmd: CreateClient): F[Client] =
        Instrumented.trace("ClientRepo.create", m)(
          pInsert.unique(
            (
              cmd.clientId,
              cmd.clientName,
              cmd.protocolType,
              cmd.description,
              cmd.requireClientSecret,
              cmd.requirePkce,
              cmd.allowOfflineAccess
            )
          )
        )

      def delete(id: Int): F[Unit] =
        Instrumented.trace("ClientRepo.delete", m, Attribute("client.db_id", id.toLong))(
          pDelete.execute(id).void
        )

      def setEnabled(id: Int, enabled: Boolean): F[Unit] =
        Instrumented.trace("ClientRepo.setEnabled", m, Attribute("client.db_id", id.toLong))(
          pSetEnabled.execute((enabled, id)).void
        )

      def listScopes(clientDbId: Int): F[List[ClientScope]] =
        Instrumented.trace("ClientRepo.listScopes", m)(
          pListScopes.stream(clientDbId, 64).compile.toList
        )

      def addScope(clientDbId: Int, scope: String): F[ClientScope] =
        Instrumented.trace("ClientRepo.addScope", m)(
          pAddScope.unique((scope, clientDbId))
        )

      def removeScope(clientDbId: Int, scope: String): F[Unit] =
        Instrumented.trace("ClientRepo.removeScope", m)(
          pRemoveScope.execute((scope, clientDbId)).void
        )

      def listRedirectUris(clientDbId: Int): F[List[ClientRedirectUri]] =
        Instrumented.trace("ClientRepo.listRedirectUris", m)(
          pListRedirects.stream(clientDbId, 64).compile.toList
        )

      def addRedirectUri(clientDbId: Int, uri: String): F[ClientRedirectUri] =
        Instrumented.trace("ClientRepo.addRedirectUri", m)(
          pAddRedirect.unique((uri, clientDbId))
        )

      def removeRedirectUri(clientDbId: Int, uri: String): F[Unit] =
        Instrumented.trace("ClientRepo.removeRedirectUri", m)(
          pRemoveRedirect.execute((uri, clientDbId)).void
        )

      def listGrantTypes(clientDbId: Int): F[List[ClientGrantType]] =
        Instrumented.trace("ClientRepo.listGrantTypes", m)(
          pListGrants.stream(clientDbId, 64).compile.toList
        )

      def addGrantType(clientDbId: Int, grantType: String): F[ClientGrantType] =
        Instrumented.trace("ClientRepo.addGrantType", m)(
          pAddGrant.unique((grantType, clientDbId))
        )

      def removeGrantType(clientDbId: Int, grantType: String): F[Unit] =
        Instrumented.trace("ClientRepo.removeGrantType", m)(
          pRemoveGrant.execute((grantType, clientDbId)).void
        )

    }

}
