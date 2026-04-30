package ledgerpay.domain

import java.util.UUID

import skunk.codec.`enum`.*
import skunk.codec.all.*
import skunk.data.Type
import skunk.Codec

// ─── Enums ───

enum AccountStatus(val label: String) derives CanEqual {

  case Active extends AccountStatus("active")
  case Frozen extends AccountStatus("frozen")
  case Closed extends AccountStatus("closed")

}

object AccountStatus {
  def fromLabel(s: String): Option[AccountStatus] = values.find(_.label == s)
}

enum KycLevel(val label: String) derives CanEqual {

  case None     extends KycLevel("none")
  case Basic    extends KycLevel("basic")
  case Verified extends KycLevel("verified")
  case Enhanced extends KycLevel("enhanced")

}

object KycLevel {
  def fromLabel(s: String): Option[KycLevel] = values.find(_.label == s)
}

enum TxStatus(val label: String) derives CanEqual {

  case Pending   extends TxStatus("pending")
  case Completed extends TxStatus("completed")
  case Failed    extends TxStatus("failed")
  case Reversed  extends TxStatus("reversed")

}

object TxStatus {
  def fromLabel(s: String): Option[TxStatus] = values.find(_.label == s)
}

enum TxType(val label: String) derives CanEqual {

  case Deposit    extends TxType("deposit")
  case Withdrawal extends TxType("withdrawal")
  case Transfer   extends TxType("transfer")
  case Fee        extends TxType("fee")
  case Refund     extends TxType("refund")

}

object TxType {
  def fromLabel(s: String): Option[TxType] = values.find(_.label == s)
}

enum CurrencyCode(val label: String) derives CanEqual {

  case USD extends CurrencyCode("USD")
  case EUR extends CurrencyCode("EUR")
  case GBP extends CurrencyCode("GBP")
  case CAD extends CurrencyCode("CAD")
  case NGN extends CurrencyCode("NGN")

}

object CurrencyCode {
  def fromLabel(s: String): Option[CurrencyCode] = values.find(_.label == s)
}
