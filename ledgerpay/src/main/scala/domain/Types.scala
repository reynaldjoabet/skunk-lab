package ledgerpay.domain

import java.util.UUID

import skunk.codec.`enum`.*
import skunk.codec.all.*
import skunk.data.Type
import skunk.Codec

// ─── Enums ───

sealed abstract class AccountStatus(val label: String)
object AccountStatus {

  case object Active extends AccountStatus("active")
  case object Frozen extends AccountStatus("frozen")
  case object Closed extends AccountStatus("closed")
  val values: List[AccountStatus]                 = List(Active, Frozen, Closed)
  def fromLabel(s: String): Option[AccountStatus] = values.find(_.label == s)
  given CanEqual[AccountStatus, AccountStatus]    = CanEqual.derived

}

sealed abstract class KycLevel(val label: String)
object KycLevel {

  case object None     extends KycLevel("none")
  case object Basic    extends KycLevel("basic")
  case object Verified extends KycLevel("verified")
  case object Enhanced extends KycLevel("enhanced")
  val values: List[KycLevel]                 = List(None, Basic, Verified, Enhanced)
  def fromLabel(s: String): Option[KycLevel] = values.find(_.label == s)
  given CanEqual[KycLevel, KycLevel]         = CanEqual.derived

}

sealed abstract class TxStatus(val label: String)
object TxStatus {

  case object Pending   extends TxStatus("pending")
  case object Completed extends TxStatus("completed")
  case object Failed    extends TxStatus("failed")
  case object Reversed  extends TxStatus("reversed")
  val values: List[TxStatus]                 = List(Pending, Completed, Failed, Reversed)
  def fromLabel(s: String): Option[TxStatus] = values.find(_.label == s)
  given CanEqual[TxStatus, TxStatus]         = CanEqual.derived

}

sealed abstract class TxType(val label: String)
object TxType {

  case object Deposit    extends TxType("deposit")
  case object Withdrawal extends TxType("withdrawal")
  case object Transfer   extends TxType("transfer")
  case object Fee        extends TxType("fee")
  case object Refund     extends TxType("refund")
  val values: List[TxType]                 = List(Deposit, Withdrawal, Transfer, Fee, Refund)
  def fromLabel(s: String): Option[TxType] = values.find(_.label == s)
  given CanEqual[TxType, TxType]           = CanEqual.derived

}

sealed abstract class CurrencyCode(val label: String)
object CurrencyCode {

  case object USD extends CurrencyCode("USD")
  case object EUR extends CurrencyCode("EUR")
  case object GBP extends CurrencyCode("GBP")
  case object CAD extends CurrencyCode("CAD")
  case object NGN extends CurrencyCode("NGN")
  val values: List[CurrencyCode]                 = List(USD, EUR, GBP, CAD, NGN)
  def fromLabel(s: String): Option[CurrencyCode] = values.find(_.label == s)
  given CanEqual[CurrencyCode, CurrencyCode]     = CanEqual.derived

}
