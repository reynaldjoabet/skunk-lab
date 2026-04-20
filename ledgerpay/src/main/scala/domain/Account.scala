package ledgerpay.domain

import java.time.OffsetDateTime
import java.util.UUID

case class Account(
  id: UUID,
  userId: UUID,
  currency: CurrencyCode,
  balance: BigDecimal,
  status: AccountStatus,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

case class CreateAccount(userId: UUID, currency: CurrencyCode)
