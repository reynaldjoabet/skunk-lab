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

trait MfaRepo[F[_]] {

  def addMethod(
    userId: Long,
    methodTypeId: MfaMethodTypeId,
    phoneNumber: Option[String]
  ): F[MfaMethod]

  def findByUser(userId: Long): F[List[MfaMethod]]
  def setPrimary(mfaId: Long, userId: Long): F[Unit]
  def verify(mfaId: Long): F[Unit]
  def markUsed(mfaId: Long): F[Unit]
  def deleteMethod(mfaId: Long): F[Unit]

}

object MfaRepo {

  private val cols =
    "mfa_id, user_id, last_used_at, created_at, method_type_id, is_primary, is_verified, phone_number, metadata"

  private object SQL {

    val insert: Query[Long *: Short *: Option[String] *: EmptyTuple, MfaMethod] =
      sql"""
        INSERT INTO auth.mfa_methods (user_id, method_type_id, phone_number)
        VALUES ($int8, $int2, ${text.opt})
        RETURNING #$cols
      """.query(mfaMethod)

    val findByUser: Query[Long, MfaMethod] =
      sql"""SELECT #$cols FROM auth.mfa_methods WHERE user_id = $int8 ORDER BY is_primary DESC, created_at"""
        .query(mfaMethod)

    val clearPrimary: Command[Long] =
      sql"""UPDATE auth.mfa_methods SET is_primary = false WHERE user_id = $int8""".command

    val setPrimary: Command[Long *: Long *: EmptyTuple] =
      sql"""UPDATE auth.mfa_methods SET is_primary = true WHERE mfa_id = $int8 AND user_id = $int8"""
        .command

    val verify: Command[Long] =
      sql"""UPDATE auth.mfa_methods SET is_verified = true WHERE mfa_id = $int8""".command

    val markUsed: Command[Long] =
      sql"""UPDATE auth.mfa_methods SET last_used_at = now() WHERE mfa_id = $int8""".command

    val delete: Command[Long] =
      sql"""DELETE FROM auth.mfa_methods WHERE mfa_id = $int8""".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[MfaRepo[F]] =
    for {
      m           <- Instrumented.makeMetrics[F]("repo.mfa")
      pInsert     <- s.prepare(SQL.insert)
      pFindByUser <- s.prepare(SQL.findByUser)
      pClearPrim  <- s.prepare(SQL.clearPrimary)
      pSetPrimary <- s.prepare(SQL.setPrimary)
      pVerify     <- s.prepare(SQL.verify)
      pMarkUsed   <- s.prepare(SQL.markUsed)
      pDelete     <- s.prepare(SQL.delete)
    } yield new MfaRepo[F] {

      def addMethod(
        userId: Long,
        methodTypeId: MfaMethodTypeId,
        phoneNumber: Option[String]
      ): F[MfaMethod] =
        Instrumented.trace("MfaRepo.addMethod", m, Attribute("user.id", userId))(
          pInsert.unique((userId, methodTypeId.value, phoneNumber))
        )

      def findByUser(userId: Long): F[List[MfaMethod]] =
        Instrumented.trace("MfaRepo.findByUser", m, Attribute("user.id", userId))(
          pFindByUser.stream(userId, 64).compile.toList
        )

      def setPrimary(mfaId: Long, userId: Long): F[Unit] =
        Instrumented.trace("MfaRepo.setPrimary", m, Attribute("mfa.id", mfaId))(
          pClearPrim.execute(userId) *> pSetPrimary.execute((mfaId, userId)).void
        )

      def verify(mfaId: Long): F[Unit] =
        Instrumented.trace("MfaRepo.verify", m, Attribute("mfa.id", mfaId))(
          pVerify.execute(mfaId).void
        )

      def markUsed(mfaId: Long): F[Unit] =
        Instrumented.trace("MfaRepo.markUsed", m, Attribute("mfa.id", mfaId))(
          pMarkUsed.execute(mfaId).void
        )

      def deleteMethod(mfaId: Long): F[Unit] =
        Instrumented.trace("MfaRepo.deleteMethod", m, Attribute("mfa.id", mfaId))(
          pDelete.execute(mfaId).void
        )

    }

}
