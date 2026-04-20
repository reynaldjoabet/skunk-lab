package kudi.domain

import java.time.OffsetDateTime

import io.circe.Json

// ── Lookup-table IDs (SMALLINT FKs, not Postgres enums) ──────────────

opaque type KycStatusId = Short
object KycStatusId {

  def apply(v: Short): KycStatusId             = v
  extension (id: KycStatusId) def value: Short = id

  val NotStarted: KycStatusId = KycStatusId(1)
  val Pending: KycStatusId    = KycStatusId(2)
  val InReview: KycStatusId   = KycStatusId(3)
  val Verified: KycStatusId   = KycStatusId(4)
  val Rejected: KycStatusId   = KycStatusId(5)
  val Expired: KycStatusId    = KycStatusId(6)

}

opaque type WalletTypeId = Short
object WalletTypeId {

  def apply(v: Short): WalletTypeId             = v
  extension (id: WalletTypeId) def value: Short = id

  val Personal: WalletTypeId   = WalletTypeId(1)
  val Business: WalletTypeId   = WalletTypeId(2)
  val Escrow: WalletTypeId     = WalletTypeId(3)
  val FeeCollect: WalletTypeId = WalletTypeId(4)
  val Settlement: WalletTypeId = WalletTypeId(5)

}

opaque type WalletStatusId = Short
object WalletStatusId {

  def apply(v: Short): WalletStatusId             = v
  extension (id: WalletStatusId) def value: Short = id

  val PendingKyc: WalletStatusId = WalletStatusId(1)
  val Active: WalletStatusId     = WalletStatusId(2)
  val Frozen: WalletStatusId     = WalletStatusId(3)
  val Suspended: WalletStatusId  = WalletStatusId(4)
  val Closed: WalletStatusId     = WalletStatusId(5)

}

//Transaction Type Identifier
opaque type TxnTypeId = Short
object TxnTypeId {

  def apply(v: Short): TxnTypeId             = v
  extension (id: TxnTypeId) def value: Short = id

  val TopUp: TxnTypeId      = TxnTypeId(1)
  val Withdrawal: TxnTypeId = TxnTypeId(2)
  val P2pSend: TxnTypeId    = TxnTypeId(3)
  val P2pReceive: TxnTypeId = TxnTypeId(4)
  val Payment: TxnTypeId    = TxnTypeId(5)
  val Refund: TxnTypeId     = TxnTypeId(6)
  val Fee: TxnTypeId        = TxnTypeId(7)
  val Cashback: TxnTypeId   = TxnTypeId(8)
  val Interest: TxnTypeId   = TxnTypeId(9)
  val FxBuy: TxnTypeId      = TxnTypeId(10)
  val FxSell: TxnTypeId     = TxnTypeId(11)
  val Reversal: TxnTypeId   = TxnTypeId(12)
  val Adjustment: TxnTypeId = TxnTypeId(13)

}

opaque type TxnStatusId = Short
object TxnStatusId {

  def apply(v: Short): TxnStatusId             = v
  extension (id: TxnStatusId) def value: Short = id

  val Pending: TxnStatusId    = TxnStatusId(1)
  val Processing: TxnStatusId = TxnStatusId(2)
  val Completed: TxnStatusId  = TxnStatusId(3)
  val Failed: TxnStatusId     = TxnStatusId(4)
  val Reversed: TxnStatusId   = TxnStatusId(5)
  val Expired: TxnStatusId    = TxnStatusId(6)
  val OnHold: TxnStatusId     = TxnStatusId(7)

}

opaque type EntryTypeId = Short
object EntryTypeId {

  def apply(v: Short): EntryTypeId             = v
  extension (id: EntryTypeId) def value: Short = id

  val Debit: EntryTypeId  = EntryTypeId(1)
  val Credit: EntryTypeId = EntryTypeId(2)

}

opaque type MfaMethodTypeId = Short
object MfaMethodTypeId {

  def apply(v: Short): MfaMethodTypeId             = v
  extension (id: MfaMethodTypeId) def value: Short = id

  val Totp: MfaMethodTypeId     = MfaMethodTypeId(1)
  val Sms: MfaMethodTypeId      = MfaMethodTypeId(2)
  val Email: MfaMethodTypeId    = MfaMethodTypeId(3)
  val WebAuthn: MfaMethodTypeId = MfaMethodTypeId(4)

}

opaque type NotificationChannelId = Short
object NotificationChannelId {

  def apply(v: Short): NotificationChannelId             = v
  extension (id: NotificationChannelId) def value: Short = id

  val Email: NotificationChannelId   = NotificationChannelId(1)
  val Sms: NotificationChannelId     = NotificationChannelId(2)
  val Push: NotificationChannelId    = NotificationChannelId(3)
  val InApp: NotificationChannelId   = NotificationChannelId(4)
  val Webhook: NotificationChannelId = NotificationChannelId(5)

}

opaque type DeliveryStatusId = Short
object DeliveryStatusId {

  def apply(v: Short): DeliveryStatusId             = v
  extension (id: DeliveryStatusId) def value: Short = id

  val Pending: DeliveryStatusId   = DeliveryStatusId(1)
  val Sent: DeliveryStatusId      = DeliveryStatusId(2)
  val Delivered: DeliveryStatusId = DeliveryStatusId(3)
  val Failed: DeliveryStatusId    = DeliveryStatusId(4)
  val Bounced: DeliveryStatusId   = DeliveryStatusId(5)

}

opaque type SessionRevokeReasonId = Short
object SessionRevokeReasonId {

  def apply(v: Short): SessionRevokeReasonId             = v
  extension (id: SessionRevokeReasonId) def value: Short = id

  val Logout: SessionRevokeReasonId          = SessionRevokeReasonId(1)
  val PasswordChange: SessionRevokeReasonId  = SessionRevokeReasonId(2)
  val AdminRevoke: SessionRevokeReasonId     = SessionRevokeReasonId(3)
  val Expired: SessionRevokeReasonId         = SessionRevokeReasonId(4)
  val MaxSessions: SessionRevokeReasonId     = SessionRevokeReasonId(5)
  val SecurityLockout: SessionRevokeReasonId = SessionRevokeReasonId(6)

}

opaque type RoleId = Short
object RoleId {

  def apply(v: Short): RoleId             = v
  extension (id: RoleId) def value: Short = id

  val Customer: RoleId   = RoleId(1)
  val Merchant: RoleId   = RoleId(2)
  val Support: RoleId    = RoleId(3)
  val Compliance: RoleId = RoleId(4)
  val Admin: RoleId      = RoleId(5)
  val SuperAdmin: RoleId = RoleId(6)

}

opaque type PermissionId = Short
object PermissionId {

  def apply(v: Short): PermissionId             = v
  extension (id: PermissionId) def value: Short = id

}

// ── Core domain models ───────────────────────────────────────────────

case class User(
  userId: Long,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime,
  lastLoginAt: Option[OffsetDateTime],
  dateOfBirth: Option[java.time.LocalDate],
  riskScore: Int,
  failedLoginCount: Int,
  kycStatusId: KycStatusId,
  isBlocked: Boolean,
  isPep: Boolean,
  mfaEnabled: Boolean,
  email: String,
  phone: String,
  firstName: String,
  lastName: String,
  countryCode: String,
  metadata: Json
)

case class CreateUser(
  email: String,
  phone: String,
  firstName: String,
  lastName: String,
  countryCode: String,
  passwordHash: String
)

case class Wallet(
  walletId: Long,
  userId: Long,
  balance: BigDecimal,
  holdAmount: BigDecimal,
  lifetimeIn: BigDecimal,
  lifetimeOut: BigDecimal,
  dailyLimit: BigDecimal,
  monthlyLimit: BigDecimal,
  createdAt: OffsetDateTime,
  walletTypeId: WalletTypeId,
  statusId: WalletStatusId,
  isDefault: Boolean,
  currencyCode: String,
  walletTag: Option[String],
  metadata: Json
)

case class Transaction(
  txnId: Long,
  sourceWalletId: Option[Long],
  destWalletId: Option[Long],
  amount: BigDecimal,
  feeAmount: BigDecimal,
  exchangeRate: Option[BigDecimal],
  balanceAfter: Option[BigDecimal],
  createdAt: OffsetDateTime,
  completedAt: Option[OffsetDateTime],
  expiresAt: Option[OffsetDateTime],
  merchantId: Option[Long],
  relatedTxnId: Option[Long],
  txnTypeId: TxnTypeId,
  statusId: TxnStatusId,
  isSuspicious: Boolean,
  sourceCurrency: Option[String],
  destCurrency: Option[String],
  referenceId: String,
  description: Option[String],
  failureReason: Option[String],
  ipAddress: Option[String],
  deviceFingerprint: Option[String],
  metadata: Json
)

case class LedgerEntry(
  entryId: Long,
  txnId: Long,
  txnCreatedAt: OffsetDateTime,
  walletId: Long,
  amount: BigDecimal,
  balanceAfter: BigDecimal,
  createdAt: OffsetDateTime,
  entryTypeId: EntryTypeId,
  currencyCode: String,
  description: Option[String],
  metadata: Json
)

case class AuditLogEntry(
  logId: Long,
  actorId: Option[Long],
  createdAt: OffsetDateTime,
  action: String,
  entityType: String,
  entityId: String,
  ipAddress: Option[String],
  userAgent: Option[String],
  oldValues: Option[Json],
  newValues: Option[Json],
  metadata: Json
)

// ── Request / command types ──────────────────────────────────────────

case class TransferRequest(
  sourceWalletId: Long,
  destWalletId: Long,
  amount: BigDecimal,
  currencyCode: String,
  referenceId: String,
  description: Option[String]
)

case class TopUpRequest(
  destWalletId: Long,
  amount: BigDecimal,
  currencyCode: String,
  referenceId: String,
  description: Option[String]
)

case class WithdrawalRequest(
  sourceWalletId: Long,
  amount: BigDecimal,
  currencyCode: String,
  referenceId: String,
  description: Option[String]
)

// ── Auth domain models ───────────────────────────────────────────────

case class PasswordVerifyResult(
  userId: Option[Long],
  isValid: Boolean,
  isLocked: Boolean
)

case class AuthSession(
  sessionId: Long,
  userId: Long,
  createdAt: OffsetDateTime,
  expiresAt: OffsetDateTime,
  lastActiveAt: OffsetDateTime,
  revokedAt: Option[OffsetDateTime],
  revokeReasonId: Option[SessionRevokeReasonId],
  isActive: Boolean,
  mfaVerified: Boolean,
  deviceFingerprint: Option[String],
  ipAddress: String,
  userAgent: Option[String],
  metadata: Json
)

case class RefreshToken(
  tokenId: Long,
  sessionId: Long,
  userId: Long,
  createdAt: OffsetDateTime,
  expiresAt: OffsetDateTime,
  usedAt: Option[OffsetDateTime],
  isRevoked: Boolean,
  tokenFamily: String
)

case class LoginAttempt(
  attemptId: Long,
  userId: Option[Long],
  createdAt: OffsetDateTime,
  success: Boolean,
  identifier: String,
  ipAddress: String,
  userAgent: Option[String],
  failureReason: Option[String]
)

case class ApiKey(
  keyId: Long,
  userId: Long,
  lastUsedAt: Option[OffsetDateTime],
  expiresAt: Option[OffsetDateTime],
  createdAt: OffsetDateTime,
  revokedAt: Option[OffsetDateTime],
  rateLimitRpm: Int,
  isActive: Boolean,
  keyPrefix: String,
  name: String,
  scopes: List[String],
  metadata: Json
)

case class MfaMethod(
  mfaId: Long,
  userId: Long,
  lastUsedAt: Option[OffsetDateTime],
  createdAt: OffsetDateTime,
  methodTypeId: MfaMethodTypeId,
  isPrimary: Boolean,
  isVerified: Boolean,
  phoneNumber: Option[String],
  metadata: Json
)

// ── RBAC domain models ───────────────────────────────────────────────

case class Role(
  roleId: RoleId,
  isSystem: Boolean,
  roleName: String,
  description: Option[String],
  createdAt: OffsetDateTime
)

case class Permission(
  permissionId: PermissionId,
  resource: String,
  action: String,
  description: Option[String]
)

case class UserRole(
  userId: Long,
  roleId: RoleId,
  grantedBy: Option[Long],
  grantedAt: OffsetDateTime,
  expiresAt: Option[OffsetDateTime]
)

// ── KYC domain models ────────────────────────────────────────────────

case class KycDocument(
  documentId: Long,
  userId: Long,
  reviewedBy: Option[Long],
  reviewedAt: Option[OffsetDateTime],
  expiresAt: Option[OffsetDateTime],
  createdAt: OffsetDateTime,
  statusId: KycStatusId,
  documentType: String,
  fileReference: String,
  rejectionReason: Option[String],
  metadata: Json
)

// ── Notification domain models ───────────────────────────────────────

case class NotificationTemplate(
  templateId: Long,
  createdAt: OffsetDateTime,
  channelId: NotificationChannelId,
  isActive: Boolean,
  code: String,
  subjectTemplate: Option[String],
  bodyTemplate: String,
  metadata: Json
)

case class NotificationPreference(
  userId: Long,
  channelId: NotificationChannelId,
  isEnabled: Boolean,
  eventType: String
)
