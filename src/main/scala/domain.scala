package fintech.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// --- Identifiers ----------------------------------------------------------

type CustomerId = CustomerId.T
object CustomerId extends RefinedType[Long, Positive]

type AccountId = AccountId.T
object AccountId extends RefinedType[Long, Positive]

type TransferId = TransferId.T
object TransferId
    extends RefinedType[
      String,
      Match["""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"""]
    ]

// --- Money ----------------------------------------------------------------

// ISO 4217: 3 uppercase letters
type CurrencyCode = CurrencyCode.T
object CurrencyCode extends RefinedType[String, Match["""^[A-Z]{3}$"""]]

// Amounts in minor units (cents/öre/…); no negative balances on this account model
type MinorUnits         = Long :| GreaterEqual[0L]
type PositiveMinorUnits = Long :| Positive

final case class Money(amount: MinorUnits, currency: CurrencyCode)
final case class TransferAmount(amount: PositiveMinorUnits, currency: CurrencyCode)

// --- Banking codes --------------------------------------------------------

// IBAN: country (2) + check (2) + BBAN (up to 30), 15–34 chars
type Iban = Iban.T
object Iban extends RefinedType[String, Match["""^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$"""]]

// SWIFT/BIC: 8 or 11 chars
type Bic = Bic.T
object Bic extends RefinedType[String, Match["""^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$"""]]

// ISO 3166-1 alpha-2
type CountryCode = CountryCode.T
object CountryCode extends RefinedType[String, Match["""^[A-Z]{2}$"""]]

// --- KYC / customer -------------------------------------------------------

type KycTier = Int :| Interval.Closed[0, 3]

type Email = Email.T
object Email extends RefinedType[String, Match["""^[^@\s]+@[^@\s]+\.[^@\s]+$"""]]

// E.164: + then 1..15 digits (first non-zero)
type PhoneE164 = PhoneE164.T
object PhoneE164 extends RefinedType[String, Match["""^\+[1-9]\d{1,14}$"""]]

type FullName    = String :| (Not[Blank] & MaxLength[120])
type Description = String :| (Not[Blank] & MaxLength[140])

// --- Risk / pricing -------------------------------------------------------

// Basis points: 0.00% .. 100.00% inclusive
type RateBps = Int :| Interval.Closed[0, 10000]

enum AccountStatus {
  case Active, Frozen, Closed
}

enum TransferStatus {
  case Pending, Posted, Reversed, Failed
}

// --- Aggregates -----------------------------------------------------------

final case class Customer(
  id: CustomerId,
  fullName: FullName,
  email: Email,
  phone: PhoneE164,
  country: CountryCode,
  kycTier: KycTier
)

final case class Account(
  id: AccountId,
  owner: CustomerId,
  iban: Iban,
  balance: Money,
  status: AccountStatus
)

final case class Transfer(
  id: TransferId,
  from: AccountId,
  to: AccountId,
  amount: TransferAmount,
  reference: Description,
  status: TransferStatus
)

// Refined, NOT a newtype — structurally equal to its definition everywhere
type Temperature1 = Double :| Positive

// Newtype, NOT refined — no predicate, just a distinct name for Double
opaque type UserId = Double

// Both: refined AND a newtype — what RefinedType gives you
opaque type Temperature2 = Double :| Positive
