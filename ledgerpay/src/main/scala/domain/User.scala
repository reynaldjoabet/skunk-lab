package ledgerpay.domain

import java.time.OffsetDateTime
import java.util.UUID

case class User(
  id: UUID,
  email: String,
  fullName: String,
  kycLevel: KycLevel,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

case class CreateUser(email: String, fullName: String)
