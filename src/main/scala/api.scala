package example.api

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import fintech.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.jsoniter.given

// ─── User-facing DTOs ────────────────────────────────────────────────

type Password = Password.T
object Password extends RefinedType[String, MinLength[12] & MaxLength[256]]

type Tags = List[String :| (Not[Blank] & MaxLength[32])] :| MaxLength[10]

final case class CreateUserRequest(
  email: Email,
  password: Password,
  displayName: String :| (Not[Blank] & MaxLength[64]),
  tags: Tags
)

final case class UserResponse(
  id: Long :| Positive,
  email: Email,
  displayName: String :| Not[Blank]
)

// ─── Fintech wire DTOs — decoupled from persistence model. ──────────

final case class OpenAccountRequest(
  customerId: CustomerId,
  currency: CurrencyCode,
  initialDeposit: MinorUnits
)

final case class TransferRequest(
  from: AccountId,
  to: AccountId,
  amount: PositiveMinorUnits,
  currency: CurrencyCode,
  reference: Description,
  idempotencyKey: String :| (Not[Blank] & MaxLength[64])
)

final case class CustomerView(
  id: CustomerId,
  fullName: FullName,
  email: Email,
  country: CountryCode,
  kycTier: KycTier
)

final case class AccountView(
  id: AccountId,
  iban: Iban,
  balance: MinorUnits,
  currency: CurrencyCode,
  status: AccountStatus
)

final case class TransferView(
  id: TransferId,
  from: AccountId,
  to: AccountId,
  amount: PositiveMinorUnits,
  currency: CurrencyCode,
  reference: Description,
  status: TransferStatus
)

// ─── Jsoniter codecs ────────────────────────────────────────────────

object Codecs {

  inline given config: CodecMakerConfig =
    CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case)

  // Plain enums encoded as strings
  given JsonValueCodec[AccountStatus]  = JsonCodecMaker.make(config)
  given JsonValueCodec[TransferStatus] = JsonCodecMaker.make(config)

  given JsonValueCodec[CreateUserRequest]  = JsonCodecMaker.make(config)
  given JsonValueCodec[UserResponse]       = JsonCodecMaker.make(config)
  given JsonValueCodec[OpenAccountRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[TransferRequest]    = JsonCodecMaker.make(config)
  given JsonValueCodec[CustomerView]       = JsonCodecMaker.make(config)
  given JsonValueCodec[AccountView]        = JsonCodecMaker.make(config)
  given JsonValueCodec[TransferView]       = JsonCodecMaker.make(config)

}

// HTTP handler (http4s-style)
def handle(body: Array[Byte]): Either[String, CreateUserRequest] =
  scala
    .util
    .Try(
      readFromArray[CreateUserRequest](body)(using Codecs.given_JsonValueCodec_CreateUserRequest)
    )
    .toEither
    .left
    .map(_.getMessage)
