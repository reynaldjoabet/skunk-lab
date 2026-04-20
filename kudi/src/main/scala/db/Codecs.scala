package kudi.db

import cats.syntax.all.*

import io.circe.Json
import kudi.domain.*
import skunk.{Transaction as _, *}
import skunk.circe.codec.json.jsonb
import skunk.codec.all.*
import skunk.data.Arr

object Codecs {

  // ── Opaque type codecs (SMALLINT ↔ opaque Short wrappers) ──────────

  val kycStatusId: Codec[KycStatusId] =
    int2.imap(KycStatusId(_))(_.value)

  val walletTypeId: Codec[WalletTypeId] =
    int2.imap(WalletTypeId(_))(_.value)

  val walletStatusId: Codec[WalletStatusId] =
    int2.imap(WalletStatusId(_))(_.value)

  val txnTypeId: Codec[TxnTypeId] =
    int2.imap(TxnTypeId(_))(_.value)

  val txnStatusId: Codec[TxnStatusId] =
    int2.imap(TxnStatusId(_))(_.value)

  val entryTypeId: Codec[EntryTypeId] =
    int2.imap(EntryTypeId(_))(_.value)

  val mfaMethodTypeId: Codec[MfaMethodTypeId] =
    int2.imap(MfaMethodTypeId(_))(_.value)

  val notificationChannelId: Codec[NotificationChannelId] =
    int2.imap(NotificationChannelId(_))(_.value)

  val deliveryStatusId: Codec[DeliveryStatusId] =
    int2.imap(DeliveryStatusId(_))(_.value)

  val sessionRevokeReasonId: Codec[SessionRevokeReasonId] =
    int2.imap(SessionRevokeReasonId(_))(_.value)

  val roleId: Codec[RoleId] =
    int2.imap(RoleId(_))(_.value)

  val permissionId: Codec[PermissionId] =
    int2.imap(PermissionId(_))(_.value)

  // ── Array codec helper ─────────────────────────────────────────────

  val textList: Codec[List[String]] =
    _text.imap(_.toList)(l => Arr(l*))

  // ── Composite decoders ─────────────────────────────────────────────

  val user: Decoder[User] =
    (int8 *: timestamptz *: timestamptz *: timestamptz.opt *: date.opt *:
      int4 *: int4 *: kycStatusId *: bool *: bool *: bool *:
      text *: text *: text *: text *: bpchar(2) *: jsonb).to[User]

  val wallet: Decoder[Wallet] =
    (int8 *: int8 *: numeric *: numeric *: numeric *: numeric *: numeric *: numeric *:
      timestamptz *: walletTypeId *: walletStatusId *: bool *:
      bpchar(3) *: text.opt *: jsonb).to[Wallet]

  val transaction: Decoder[Transaction] =
    (int8 *: int8.opt *: int8.opt *: numeric *: numeric *: numeric.opt *: numeric.opt *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *:
      int8.opt *: int8.opt *: txnTypeId *: txnStatusId *: bool *:
      bpchar(3).opt *: bpchar(3).opt *: text *: text.opt *: text.opt *:
      text.opt *: text.opt *: jsonb).to[Transaction]

  val ledgerEntry: Decoder[LedgerEntry] =
    (int8 *: int8 *: timestamptz *: int8 *: numeric *: numeric *:
      timestamptz *: entryTypeId *: bpchar(3) *: text.opt *: jsonb).to[LedgerEntry]

  val auditLogEntry: Decoder[AuditLogEntry] =
    (int8 *: int8.opt *: timestamptz *: text *: text *: text *:
      text.opt *: text.opt *: jsonb.opt *: jsonb.opt *: jsonb).to[AuditLogEntry]

  // ── Auth decoders ──────────────────────────────────────────────────

  val passwordVerifyResult: Decoder[PasswordVerifyResult] =
    (int8.opt *: bool *: bool).to[PasswordVerifyResult]

  val authSession: Decoder[AuthSession] =
    (int8 *: int8 *: timestamptz *: timestamptz *: timestamptz *:
      timestamptz.opt *: sessionRevokeReasonId.opt *: bool *: bool *:
      text.opt *: text *: text.opt *: jsonb).to[AuthSession]

  val refreshToken: Decoder[RefreshToken] =
    (int8 *: int8 *: int8 *: timestamptz *: timestamptz *:
      timestamptz.opt *: bool *: text).to[RefreshToken]

  val loginAttempt: Decoder[LoginAttempt] =
    (int8 *: int8.opt *: timestamptz *: bool *:
      text *: text *: text.opt *: text.opt).to[LoginAttempt]

  val apiKey: Decoder[ApiKey] =
    (int8 *: int8 *: timestamptz.opt *: timestamptz.opt *:
      timestamptz *: timestamptz.opt *: int4 *: bool *:
      bpchar(8) *: text *: textList *: jsonb).to[ApiKey]

  val mfaMethod: Decoder[MfaMethod] =
    (int8 *: int8 *: timestamptz.opt *: timestamptz *:
      mfaMethodTypeId *: bool *: bool *:
      text.opt *: jsonb).to[MfaMethod]

  // ── RBAC decoders ──────────────────────────────────────────────────

  val role: Decoder[Role] =
    (roleId *: bool *: text *: text.opt *: timestamptz).to[Role]

  val permission: Decoder[Permission] =
    (permissionId *: text *: text *: text.opt).to[Permission]

  val userRole: Decoder[UserRole] =
    (int8 *: roleId *: int8.opt *: timestamptz *: timestamptz.opt).to[UserRole]

  // ── KYC decoders ───────────────────────────────────────────────────

  val kycDocument: Decoder[KycDocument] =
    (int8 *: int8 *: int8.opt *: timestamptz.opt *: timestamptz.opt *:
      timestamptz *: kycStatusId *: text *: text *:
      text.opt *: jsonb).to[KycDocument]

  // ── Notification decoders ──────────────────────────────────────────

  val notificationTemplate: Decoder[NotificationTemplate] =
    (int8 *: timestamptz *: notificationChannelId *: bool *:
      text *: text.opt *: text *: jsonb).to[NotificationTemplate]

  val notificationPreference: Decoder[NotificationPreference] =
    (int8 *: notificationChannelId *: bool *: text).to[NotificationPreference]

}
