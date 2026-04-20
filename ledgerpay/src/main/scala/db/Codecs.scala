package ledgerpay.db

import ledgerpay.domain.*
import skunk.{Transaction as _, *}
import skunk.codec.`enum`.`enum`
import skunk.codec.all._
import skunk.data.Type

object Codecs {

  // ─── Enum Codecs (require TypingStrategy.SearchPath) ───

  val accountStatus: Codec[AccountStatus] =
    `enum`(_.label, AccountStatus.fromLabel, Type("account_status"))

  val kycLevel: Codec[KycLevel] =
    `enum`(_.label, KycLevel.fromLabel, Type("kyc_level"))

  val txStatus: Codec[TxStatus] =
    `enum`(_.label, TxStatus.fromLabel, Type("tx_status"))

  val txType: Codec[TxType] =
    `enum`(_.label, TxType.fromLabel, Type("tx_type"))

  val currencyCode: Codec[CurrencyCode] =
    `enum`(_.label, CurrencyCode.fromLabel, Type("currency_code"))

  // ─── Composite Codecs ───

  val user: Decoder[User] =
    (uuid *: varchar *: varchar *: kycLevel *: timestamptz *: timestamptz).to[User]

  val account: Decoder[Account] =
    (uuid *: uuid *: currencyCode *: numeric *: accountStatus *: timestamptz *: timestamptz)
      .to[Account]

  val transaction: Decoder[ledgerpay.domain.Transaction] =
    (uuid *: varchar *: txType *: txStatus *: uuid.opt *: uuid.opt *:
      numeric *: currencyCode *: text.opt *: timestamptz *: timestamptz.opt)
      .to[ledgerpay.domain.Transaction]

}
