package ledgerpay.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import ledgerpay.db.Codecs._
import ledgerpay.domain._
import skunk._
import skunk.codec.all._
import skunk.implicits._

case class TransferLimits(singleTransfer: BigDecimal, dailyTransfer: BigDecimal)

trait VelocityService[F[_]] {
  def checkDailyLimit(accountId: UUID, additionalAmount: BigDecimal): F[Either[String, Unit]]
}

object VelocityService {

  private val dailyVolume: Query[UUID, BigDecimal] =
    sql"""
      SELECT COALESCE(SUM(amount), 0)
      FROM transactions
      WHERE source_acct_id = $uuid
        AND status IN ('completed', 'pending')
        AND created_at >= now() - interval '24 hours'
    """.query(numeric)

  private val accountOwnerKyc: Query[UUID, KycLevel] =
    sql"""
      SELECT u.kyc_level
      FROM accounts a
      JOIN users u ON u.id = a.user_id
      WHERE a.id = $uuid
    """.query(kycLevel)

  def fromSession[F[_]: MonadCancelThrow](s: Session[F]): F[VelocityService[F]] =
    (s.prepare(dailyVolume), s.prepare(accountOwnerKyc)).mapN { (pVolume, pKyc) =>
      new VelocityService[F] {

        def checkDailyLimit(
          accountId: UUID,
          additionalAmount: BigDecimal
        ): F[Either[String, Unit]] =
          for {
            kyc <- pKyc.unique(accountId)
            limits = kyc match {
                       case KycLevel.Verified =>
                         TransferLimits(BigDecimal(100000), BigDecimal(1000000))
                       case KycLevel.Enhanced =>
                         TransferLimits(BigDecimal(10000), BigDecimal(100000))
                       case _ => TransferLimits(BigDecimal(1000), BigDecimal(10000))
                     }
            volume <- pVolume.unique(accountId)
            total   = volume + additionalAmount
          } yield
            if (additionalAmount > limits.singleTransfer)
              Left(s"Single transfer limit exceeded: ${limits.singleTransfer}")
            else if (total > limits.dailyTransfer)
              Left(s"Daily volume limit exceeded: ${limits.dailyTransfer}")
            else Right(())

      }
    }

}
