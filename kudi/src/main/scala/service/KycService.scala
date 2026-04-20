package kudi.service

import cats.effect.*
import cats.syntax.all.*

import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait KycService[F[_]] {

  def submitDocument(
    userId: Long,
    documentType: String,
    fileReference: String,
    fileHash: Array[Byte]
  ): F[KycDocument]

  def reviewDocument(
    documentId: Long,
    reviewerId: Long,
    approved: Boolean,
    rejectionReason: Option[String]
  ): F[Unit]

  def getUserDocuments(userId: Long): F[List[KycDocument]]
  def getPendingDocuments(limit: Int): F[List[KycDocument]]

}

object KycService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[KycService[F]] =
    for {
      m     <- Instrumented.makeMetrics[F]("service.kyc")
      docs  <- KycDocumentRepo.fromSession(s)
      users <- UserRepo.fromSession(s)
      audit <- AuditRepo.fromSession(s)
    } yield new KycService[F] {

      def submitDocument(
        userId: Long,
        documentType: String,
        fileReference: String,
        fileHash: Array[Byte]
      ): F[KycDocument] =
        Instrumented.trace("KycService.submitDocument", m, Attribute("user.id", userId))(
          for {
            doc <- docs.create(userId, documentType, fileReference, fileHash)
            _   <- users.updateKycStatus(userId, KycStatusId.Pending)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "kyc_document_submitted",
                   entityType = "kyc_document",
                   entityId = doc.documentId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield doc
        )

      def reviewDocument(
        documentId: Long,
        reviewerId: Long,
        approved: Boolean,
        rejectionReason: Option[String]
      ): F[Unit] =
        Instrumented.trace("KycService.reviewDocument", m, Attribute("document.id", documentId))(
          for {
            docOpt <- docs.findById(documentId)
            doc <- docOpt match {
                     case Some(d) => d.pure[F]
                     case None =>
                       MonadCancelThrow[F]
                         .raiseError(new Exception(s"KYC document $documentId not found"))
                   }
            _ <- docs.review(documentId, reviewerId, approved, rejectionReason)
            _ <- if (approved) users.updateKycStatus(doc.userId, KycStatusId.Verified)
                 else users.updateKycStatus(doc.userId, KycStatusId.Rejected)
            _ <- audit.log(
                   actorId = Some(reviewerId),
                   action = if (approved) "kyc_document_approved" else "kyc_document_rejected",
                   entityType = "kyc_document",
                   entityId = documentId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def getUserDocuments(userId: Long): F[List[KycDocument]] =
        Instrumented.trace("KycService.getUserDocuments", m, Attribute("user.id", userId))(
          docs.findByUser(userId)
        )

      def getPendingDocuments(limit: Int): F[List[KycDocument]] =
        Instrumented.trace("KycService.getPendingDocuments", m)(
          docs.findPending(limit)
        )

    }

}
