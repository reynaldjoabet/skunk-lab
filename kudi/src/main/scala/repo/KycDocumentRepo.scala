package kudi.repo

import cats.effect.*
import cats.syntax.all.*

import io.circe.Json
import kudi.db.Codecs.*
import kudi.domain.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.circe.codec.json.jsonb
import skunk.codec.all.*
import skunk.implicits.*

trait KycDocumentRepo[F[_]] {

  def create(
    userId: Long,
    documentType: String,
    fileReference: String,
    fileHash: Array[Byte]
  ): F[KycDocument]

  def review(
    documentId: Long,
    reviewerId: Long,
    approved: Boolean,
    rejectionReason: Option[String]
  ): F[Unit]

  def findByUser(userId: Long): F[List[KycDocument]]
  def findPending(limit: Int): F[List[KycDocument]]
  def findById(documentId: Long): F[Option[KycDocument]]

}

object KycDocumentRepo {

  private val cols =
    "document_id, user_id, reviewed_by, reviewed_at, expires_at, created_at, status_id, document_type, file_reference, rejection_reason, metadata"

  private object SQL {

    val insert: Query[Long *: String *: String *: Array[Byte] *: EmptyTuple, KycDocument] =
      sql"""
        INSERT INTO kyc_documents (user_id, document_type, file_reference, file_hash)
        VALUES ($int8, $text, $text, $bytea)
        RETURNING #$cols
      """.query(kycDocument)

    val approve: Command[Long *: Long *: EmptyTuple] =
      sql"""
        UPDATE kyc_documents
        SET status_id = 4, reviewed_by = $int8, reviewed_at = now()
        WHERE document_id = $int8
      """.command

    val reject: Command[Long *: String *: Long *: EmptyTuple] =
      sql"""
        UPDATE kyc_documents
        SET status_id = 5, reviewed_by = $int8, reviewed_at = now(), rejection_reason = $text
        WHERE document_id = $int8
      """.command

    val findByUser: Query[Long, KycDocument] =
      sql"""
        SELECT #$cols FROM kyc_documents
        WHERE user_id = $int8
        ORDER BY created_at DESC
      """.query(kycDocument)

    val findPending: Query[Int, KycDocument] =
      sql"""
        SELECT #$cols FROM kyc_documents
        WHERE status_id = 2
        ORDER BY created_at ASC
        LIMIT $int4
      """.query(kycDocument)

    val findById: Query[Long, KycDocument] =
      sql"""SELECT #$cols FROM kyc_documents WHERE document_id = $int8""".query(kycDocument)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[KycDocumentRepo[F]] =
    for {
      m        <- Instrumented.makeMetrics[F]("repo.kyc_document")
      pInsert  <- s.prepare(SQL.insert)
      pApprove <- s.prepare(SQL.approve)
      pReject  <- s.prepare(SQL.reject)
      pByUser  <- s.prepare(SQL.findByUser)
      pPending <- s.prepare(SQL.findPending)
      pById    <- s.prepare(SQL.findById)
    } yield new KycDocumentRepo[F] {

      def create(
        userId: Long,
        documentType: String,
        fileReference: String,
        fileHash: Array[Byte]
      ): F[KycDocument] =
        Instrumented.trace("KycDocumentRepo.create", m, Attribute("user.id", userId))(
          pInsert.unique((userId, documentType, fileReference, fileHash))
        )

      def review(
        documentId: Long,
        reviewerId: Long,
        approved: Boolean,
        rejectionReason: Option[String]
      ): F[Unit] =
        Instrumented.trace("KycDocumentRepo.review", m, Attribute("document.id", documentId))(
          if (approved) pApprove.execute((reviewerId, documentId)).void
          else
            rejectionReason match {
              case Some(reason) => pReject.execute((reviewerId, reason, documentId)).void
              case None         => pReject.execute((reviewerId, "Rejected", documentId)).void
            }
        )

      def findByUser(userId: Long): F[List[KycDocument]] =
        Instrumented.trace("KycDocumentRepo.findByUser", m, Attribute("user.id", userId))(
          pByUser.stream(userId, 64).compile.toList
        )

      def findPending(limit: Int): F[List[KycDocument]] =
        Instrumented.trace("KycDocumentRepo.findPending", m)(
          pPending.stream(limit, 64).compile.toList
        )

      def findById(documentId: Long): F[Option[KycDocument]] =
        Instrumented.trace("KycDocumentRepo.findById", m, Attribute("document.id", documentId))(
          pById.option(documentId)
        )

    }

}
