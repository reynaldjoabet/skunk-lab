package ledgerpay.domain

import java.time.OffsetDateTime
import java.util.UUID

case class Transaction(
  id: UUID,
  idempotencyKey: String,
  txType: TxType,
  status: TxStatus,
  sourceAcctId: Option[UUID],
  destAcctId: Option[UUID],
  amount: BigDecimal,
  currency: CurrencyCode,
  description: Option[String],
  createdAt: OffsetDateTime,
  completedAt: Option[OffsetDateTime]
)

case class TransferRequest(
  idempotencyKey: String,
  fromAccountId: UUID,
  toAccountId: UUID,
  amount: BigDecimal,
  currency: CurrencyCode,
  description: Option[String]
)
