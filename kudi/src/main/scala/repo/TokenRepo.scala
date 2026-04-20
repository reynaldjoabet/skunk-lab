package kudi.repo

import cats.effect.*
import cats.syntax.all.*

import kudi.db.Codecs.*
import kudi.domain.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait TokenRepo[F[_]] {

  def createRefreshToken(
    sessionId: Long,
    userId: Long,
    tokenHash: Array[Byte],
    tokenFamily: String,
    ttlHours: Int
  ): F[RefreshToken]

  def findRefreshTokenByFamily(tokenFamily: String): F[List[RefreshToken]]
  def markRefreshTokenUsed(tokenId: Long): F[Unit]
  def revokeRefreshToken(tokenId: Long): F[Unit]
  def revokeRefreshTokenFamily(tokenFamily: String): F[Unit]

  def createApiKey(
    userId: Long,
    keyPrefix: String,
    keyHash: Array[Byte],
    name: String,
    scopes: List[String],
    rateLimitRpm: Int
  ): F[ApiKey]

  def findApiKeyByPrefix(keyPrefix: String): F[Option[ApiKey]]
  def revokeApiKey(keyId: Long): F[Unit]
  def findApiKeysByUser(userId: Long): F[List[ApiKey]]

}

object TokenRepo {

  private val refreshCols =
    "token_id, session_id, user_id, created_at, expires_at, used_at, is_revoked, token_family"

  private val apiKeyCols =
    "key_id, user_id, last_used_at, expires_at, created_at, revoked_at, rate_limit_rpm, is_active, key_prefix, name, scopes, metadata"

  private object SQL {

    val insertRefreshToken: Query[
      Long *: Long *: Array[Byte] *: String *: Int *: EmptyTuple,
      RefreshToken
    ] =
      sql"""
        INSERT INTO auth.refresh_tokens (session_id, user_id, token_hash, token_family, expires_at)
        VALUES ($int8, $int8, $bytea, $text, now() + ($int4 || ' hours')::interval)
        RETURNING #$refreshCols
      """.query(refreshToken)

    val findRefreshByFamily: Query[String, RefreshToken] =
      sql"""
        SELECT #$refreshCols FROM auth.refresh_tokens
        WHERE token_family = $text
        ORDER BY created_at DESC
      """.query(refreshToken)

    val markRefreshUsed: Command[Long] =
      sql"""UPDATE auth.refresh_tokens SET used_at = now() WHERE token_id = $int8""".command

    val revokeRefresh: Command[Long] =
      sql"""UPDATE auth.refresh_tokens SET is_revoked = true WHERE token_id = $int8""".command

    val revokeRefreshFamily: Command[String] =
      sql"""UPDATE auth.refresh_tokens SET is_revoked = true WHERE token_family = $text AND NOT is_revoked"""
        .command

    val insertApiKey: Query[
      Long *: String *: Array[Byte] *: String *: List[String] *: Int *: EmptyTuple,
      ApiKey
    ] =
      sql"""
        INSERT INTO auth.api_keys (user_id, key_prefix, key_hash, name, scopes, rate_limit_rpm)
        VALUES ($int8, $bpchar(8), $bytea, $text, $textList, $int4)
        RETURNING #$apiKeyCols
      """.query(apiKey)

    val findApiKeyByPrefix: Query[String, ApiKey] =
      sql"""
        SELECT #$apiKeyCols FROM auth.api_keys
        WHERE key_prefix = $bpchar(8) AND is_active = true
      """.query(apiKey)

    val revokeApiKey: Command[Long] =
      sql"""
        UPDATE auth.api_keys SET is_active = false, revoked_at = now()
        WHERE key_id = $int8
      """.command

    val findApiKeysByUser: Query[Long, ApiKey] =
      sql"""
        SELECT #$apiKeyCols FROM auth.api_keys
        WHERE user_id = $int8 AND is_active = true
        ORDER BY created_at DESC
      """.query(apiKey)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[TokenRepo[F]] =
    for {
      m               <- Instrumented.makeMetrics[F]("repo.token")
      pInsertRefresh  <- s.prepare(SQL.insertRefreshToken)
      pFindFamily     <- s.prepare(SQL.findRefreshByFamily)
      pMarkUsed       <- s.prepare(SQL.markRefreshUsed)
      pRevokeRefresh  <- s.prepare(SQL.revokeRefresh)
      pRevokeFamily   <- s.prepare(SQL.revokeRefreshFamily)
      pInsertApiKey   <- s.prepare(SQL.insertApiKey)
      pFindKeyPrefix  <- s.prepare(SQL.findApiKeyByPrefix)
      pRevokeKey      <- s.prepare(SQL.revokeApiKey)
      pFindKeysByUser <- s.prepare(SQL.findApiKeysByUser)
    } yield new TokenRepo[F] {

      def createRefreshToken(
        sessionId: Long,
        userId: Long,
        tokenHash: Array[Byte],
        tokenFamily: String,
        ttlHours: Int
      ): F[RefreshToken] =
        Instrumented.trace("TokenRepo.createRefreshToken", m, Attribute("user.id", userId))(
          pInsertRefresh.unique((sessionId, userId, tokenHash, tokenFamily, ttlHours))
        )

      def findRefreshTokenByFamily(tokenFamily: String): F[List[RefreshToken]] =
        Instrumented.trace("TokenRepo.findRefreshTokenByFamily", m)(
          pFindFamily.stream(tokenFamily, 64).compile.toList
        )

      def markRefreshTokenUsed(tokenId: Long): F[Unit] =
        Instrumented.trace("TokenRepo.markRefreshTokenUsed", m, Attribute("token.id", tokenId))(
          pMarkUsed.execute(tokenId).void
        )

      def revokeRefreshToken(tokenId: Long): F[Unit] =
        Instrumented.trace("TokenRepo.revokeRefreshToken", m, Attribute("token.id", tokenId))(
          pRevokeRefresh.execute(tokenId).void
        )

      def revokeRefreshTokenFamily(tokenFamily: String): F[Unit] =
        Instrumented.trace("TokenRepo.revokeRefreshTokenFamily", m)(
          pRevokeFamily.execute(tokenFamily).void
        )

      def createApiKey(
        userId: Long,
        keyPrefix: String,
        keyHash: Array[Byte],
        name: String,
        scopes: List[String],
        rateLimitRpm: Int
      ): F[ApiKey] =
        Instrumented.trace("TokenRepo.createApiKey", m, Attribute("user.id", userId))(
          pInsertApiKey.unique((userId, keyPrefix, keyHash, name, scopes, rateLimitRpm))
        )

      def findApiKeyByPrefix(keyPrefix: String): F[Option[ApiKey]] =
        Instrumented.trace("TokenRepo.findApiKeyByPrefix", m)(
          pFindKeyPrefix.option(keyPrefix)
        )

      def revokeApiKey(keyId: Long): F[Unit] =
        Instrumented.trace("TokenRepo.revokeApiKey", m, Attribute("key.id", keyId))(
          pRevokeKey.execute(keyId).void
        )

      def findApiKeysByUser(userId: Long): F[List[ApiKey]] =
        Instrumented.trace("TokenRepo.findApiKeysByUser", m, Attribute("user.id", userId))(
          pFindKeysByUser.stream(userId, 64).compile.toList
        )

    }

}
