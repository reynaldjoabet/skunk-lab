package idmgmt.domain

import java.time.Duration
import java.time.OffsetDateTime

// ── Main entities ────────────────────────────────────────────────────

case class ApiResource(
  id: Int,
  name: String,
  displayName: Option[String],
  description: Option[String],
  enabled: Boolean,
  showInDiscoveryDocument: Boolean,
  requireResourceIndicator: Boolean,
  allowedAccessTokenSigningAlgorithms: Option[String],
  created: OffsetDateTime,
  updated: Option[OffsetDateTime],
  lastAccessed: Option[OffsetDateTime],
  nonEditable: Boolean
)

case class Client(
  id: Int,
  clientId: String,
  clientName: Option[String],
  protocolType: String,
  enabled: Boolean,
  description: Option[String],
  clientUri: Option[String],
  logoUri: Option[String],
  requireClientSecret: Boolean,
  requirePkce: Boolean,
  allowPlainTextPkce: Boolean,
  requireRequestObject: Boolean,
  allowOfflineAccess: Boolean,
  accessTokenLifetime: Int,
  authorizationCodeLifetime: Int,
  identityTokenLifetime: Int,
  refreshTokenUsage: Int,
  refreshTokenExpiration: Int,
  absoluteRefreshTokenLifetime: Int,
  slidingRefreshTokenLifetime: Int,
  cibaLifetime: Option[Int],
  pollingInterval: Option[Int],
  coordinateLifetimeWithUserSession: Option[Boolean],
  requireDpop: Boolean,
  dpopValidationMode: Int,
  dpopClockSkew: Duration,
  initiateLoginUri: Option[String],
  pushedAuthorizationLifetime: Option[Int],
  requirePushedAuthorization: Boolean,
  created: OffsetDateTime,
  updated: Option[OffsetDateTime],
  lastAccessed: Option[OffsetDateTime],
  nonEditable: Boolean
)

case class ApiScope(
  id: Int,
  name: String,
  displayName: Option[String],
  description: Option[String],
  enabled: Boolean,
  required: Boolean,
  emphasize: Boolean,
  showInDiscoveryDocument: Boolean,
  created: OffsetDateTime,
  updated: Option[OffsetDateTime],
  lastAccessed: Option[OffsetDateTime],
  nonEditable: Boolean
)

case class IdentityResource(
  id: Int,
  name: String,
  displayName: Option[String],
  description: Option[String],
  enabled: Boolean,
  required: Boolean,
  emphasize: Boolean,
  showInDiscoveryDocument: Boolean,
  created: OffsetDateTime,
  updated: Option[OffsetDateTime],
  nonEditable: Boolean
)

case class IdentityProvider(
  id: Int,
  scheme: String,
  displayName: Option[String],
  enabled: Boolean,
  providerType: String,
  properties: Option[String],
  created: OffsetDateTime,
  updated: Option[OffsetDateTime],
  lastAccessed: Option[OffsetDateTime],
  nonEditable: Boolean
)

// ── Client child entities ────────────────────────────────────────────

case class ClientScope(id: Int, scope: String, clientId: Int)
case class ClientRedirectUri(id: Int, redirectUri: String, clientId: Int)
case class ClientPostLogoutRedirectUri(id: Int, postLogoutRedirectUri: String, clientId: Int)
case class ClientGrantType(id: Int, grantType: String, clientId: Int)

case class ClientSecret(
  id: Int,
  value: String,
  description: Option[String],
  expiration: Option[OffsetDateTime],
  secretType: String,
  created: OffsetDateTime,
  clientId: Int
)

case class ClientClaim(id: Int, claimType: String, value: String, clientId: Int)
case class ClientCorsOrigin(id: Int, origin: String, clientId: Int)
case class ClientIdpRestriction(id: Int, provider: String, clientId: Int)
case class ClientProperty(id: Int, key: String, value: String, clientId: Int)

// ── API Resource child entities ──────────────────────────────────────

case class ApiResourceScope(id: Int, scope: String, apiResourceId: Int)
case class ApiResourceClaim(id: Int, claimType: String, apiResourceId: Int)

case class ApiResourceSecret(
  id: Int,
  value: String,
  description: Option[String],
  expiration: Option[OffsetDateTime],
  secretType: String,
  created: OffsetDateTime,
  apiResourceId: Int
)

case class ApiResourceProperty(id: Int, key: String, value: String, apiResourceId: Int)

// ── API Scope child entities ─────────────────────────────────────────

case class ApiScopeClaim(id: Int, claimType: String, scopeId: Int)
case class ApiScopeProperty(id: Int, key: String, value: String, scopeId: Int)

// ── Identity Resource child entities ─────────────────────────────────

case class IdentityResourceClaim(id: Int, claimType: String, identityResourceId: Int)
case class IdentityResourceProperty(id: Int, key: String, value: String, identityResourceId: Int)

// ── Operational entities (idmgmt schema) ─────────────────────────────

case class DeviceCode(
  userCode: String,
  deviceCode: String,
  subjectId: Option[String],
  sessionId: Option[String],
  clientId: String,
  description: Option[String],
  creationTime: OffsetDateTime,
  expiration: OffsetDateTime,
  data: String
)

case class SigningKey(
  id: String,
  version: Int,
  created: OffsetDateTime,
  use: Option[String],
  algorithm: String,
  isX509Certificate: Boolean,
  dataProtected: Boolean,
  data: String
)

case class PersistedGrant(
  id: Long,
  key: Option[String],
  grantType: String,
  subjectId: Option[String],
  sessionId: Option[String],
  clientId: String,
  description: Option[String],
  creationTime: OffsetDateTime,
  expiration: Option[OffsetDateTime],
  consumedTime: Option[OffsetDateTime],
  data: String
)

case class PushedAuthorizationRequest(
  id: Long,
  referenceValueHash: String,
  expiresAtUtc: OffsetDateTime,
  parameters: String
)

case class ServerSideSession(
  id: Long,
  key: String,
  scheme: String,
  subjectId: String,
  sessionId: Option[String],
  displayName: Option[String],
  created: OffsetDateTime,
  renewed: OffsetDateTime,
  expires: Option[OffsetDateTime],
  data: String
)

// ── Create commands ──────────────────────────────────────────────────

case class CreateClient(
  clientId: String,
  clientName: Option[String],
  protocolType: String,
  description: Option[String],
  requireClientSecret: Boolean,
  requirePkce: Boolean,
  allowOfflineAccess: Boolean
)

case class CreateApiResource(
  name: String,
  displayName: Option[String],
  description: Option[String]
)

case class CreateApiScope(
  name: String,
  displayName: Option[String],
  description: Option[String]
)

case class CreateIdentityResource(
  name: String,
  displayName: Option[String],
  description: Option[String]
)

case class CreateIdentityProvider(
  scheme: String,
  displayName: Option[String],
  enabled: Boolean,
  providerType: String,
  properties: Option[String]
)

case class CreatePersistedGrant(
  key: Option[String],
  grantType: String,
  subjectId: Option[String],
  sessionId: Option[String],
  clientId: String,
  description: Option[String],
  creationTime: OffsetDateTime,
  expiration: Option[OffsetDateTime],
  data: String
)

case class CreateDeviceCode(
  userCode: String,
  deviceCode: String,
  clientId: String,
  description: Option[String],
  creationTime: OffsetDateTime,
  expiration: OffsetDateTime,
  data: String
)

case class CreateSigningKey(
  id: String,
  version: Int,
  use: Option[String],
  algorithm: String,
  isX509Certificate: Boolean,
  dataProtected: Boolean,
  data: String
)

case class CreateServerSideSession(
  key: String,
  scheme: String,
  subjectId: String,
  sessionId: Option[String],
  displayName: Option[String],
  data: String
)
