package ledgerpay.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._
import fs2.Stream

import io.circe._
import io.circe.parser.decode
import io.circe.syntax._
import skunk._
import skunk.data.Notification
import skunk.implicits._

// Payload sent over the Postgres channel
case class BalanceChangeEvent(accountId: UUID, newBalance: BigDecimal, txId: UUID)
object BalanceChangeEvent {

  implicit val encoder: io.circe.Encoder[BalanceChangeEvent] = io
    .circe
    .Encoder
    .forProduct3("accountId", "newBalance", "txId")(e => (e.accountId, e.newBalance, e.txId))

  implicit val decoder: io.circe.Decoder[BalanceChangeEvent] = io
    .circe
    .Decoder
    .forProduct3("accountId", "newBalance", "txId")(BalanceChangeEvent.apply)

}

trait NotificationService[F[_]] {

  def publishBalanceChange(event: BalanceChangeEvent): F[Unit]
  def subscribeBalanceChanges: Stream[F, BalanceChangeEvent]

}

object NotificationService {

  // Use a DEDICATED session for LISTEN — never share with query traffic
  def make[F[_]: Concurrent](
    listenerSession: Session[F], // dedicated session for LISTEN
    writerSession: Session[F]    // any session (or pooled) for NOTIFY
  ): NotificationService[F] =
    new NotificationService[F] {

      //  creates a reusable typed channel with both send (contramap) and receive (map) transformations baked in.
      private val channel: Channel[F, BalanceChangeEvent, Option[BalanceChangeEvent]] =
        listenerSession
          .channel(id"balance_changes")
          .contramap[BalanceChangeEvent](e => e.asJson.noSpaces) // encode on send
          .map(str => decode[BalanceChangeEvent](str).toOption)  // decode on receive

      def publishBalanceChange(event: BalanceChangeEvent): F[Unit] =
        writerSession.channel(id"balance_changes").notify(event.asJson.noSpaces)

      def subscribeBalanceChanges: Stream[F, BalanceChangeEvent] =
        channel.listen(256).map(_.value).unNone

    }

}
