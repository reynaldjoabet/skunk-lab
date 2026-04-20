package ledgerpay.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import io.circe.syntax._
import io.circe.Json
import ledgerpay.domain._
import ledgerpay.repo._
import skunk._
import skunk.data.TransactionAccessMode

case class KycLimits(dailyTransfer: BigDecimal, singleTransfer: BigDecimal)

trait KycService[F[_]] {

  def getLimits(userId: UUID): F[KycLimits]
  def upgradeKyc(userId: UUID, newLevel: KycLevel, verifiedBy: UUID): F[Unit]

}

object KycService {

  private val limits: Map[KycLevel, KycLimits] = Map(
    KycLevel.None     -> KycLimits(0, 0),
    KycLevel.Basic    -> KycLimits(1000, 500),
    KycLevel.Verified -> KycLimits(50000, 10000),
    KycLevel.Enhanced -> KycLimits(1000000, 250000)
  )

  def fromSession[F[_]: MonadCancelThrow](s: Session[F]): F[KycService[F]] =
    (UserRepo.fromSession(s), AuditRepo.fromSession(s)).mapN { (users, audit) =>
      new KycService[F] {

        def getLimits(userId: UUID): F[KycLimits] =
          users
            .findById(userId)
            .flatMap {
              case Some(u) => limits.getOrElse(u.kycLevel, limits(KycLevel.None)).pure[F]
              case None    => MonadCancelThrow[F].raiseError(new Exception(s"User $userId not found"))
            }

        def upgradeKyc(userId: UUID, newLevel: KycLevel, verifiedBy: UUID): F[Unit] =
          s.transaction
            .use { _ =>
              for {
                _ <- users.updateKycLevel(userId, newLevel)
                _ <- audit.log(
                       entityType = "user",
                       entityId = userId,
                       action = "kyc_upgrade",
                       actorId = Some(verifiedBy),
                       payload = Some(Json.obj("new_level" -> newLevel.label.asJson))
                     )
              } yield ()
            }

      }
    }

}
