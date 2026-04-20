package idmgmt.db

import idmgmt.domain.*
import skunk.*
import skunk.codec.all.*

object Codecs {

  // ── Main entity decoders ───────────────────────────────────────────

  val apiResource: Decoder[ApiResource] =
    (int4 *: text *: text.opt *: text.opt *:
      bool *: bool *: bool *: text.opt *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *: bool).to[ApiResource]

  val client: Decoder[Client] =
    (int4 *: text *: text.opt *: text *: bool *: text.opt *: text.opt *: text.opt *:
      bool *: bool *: bool *: bool *: bool *:
      int4 *: int4 *: int4 *:
      int4 *: int4 *: int4 *: int4 *:
      int4.opt *: int4.opt *: bool.opt *:
      bool *: int4 *: interval *:
      text.opt *: int4.opt *: bool *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *: bool).to[Client]

  val apiScope: Decoder[ApiScope] =
    (int4 *: text *: text.opt *: text.opt *:
      bool *: bool *: bool *: bool *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *: bool).to[ApiScope]

  val identityResource: Decoder[IdentityResource] =
    (int4 *: text *: text.opt *: text.opt *:
      bool *: bool *: bool *: bool *:
      timestamptz *: timestamptz.opt *: bool).to[IdentityResource]

  val identityProvider: Decoder[IdentityProvider] =
    (int4 *: text *: text.opt *: bool *:
      text *: text.opt *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *: bool).to[IdentityProvider]

  // ── Client child decoders ──────────────────────────────────────────

  val clientScope: Decoder[ClientScope] =
    (int4 *: text *: int4).to[ClientScope]

  val clientRedirectUri: Decoder[ClientRedirectUri] =
    (int4 *: text *: int4).to[ClientRedirectUri]

  val clientPostLogoutRedirectUri: Decoder[ClientPostLogoutRedirectUri] =
    (int4 *: text *: int4).to[ClientPostLogoutRedirectUri]

  val clientGrantType: Decoder[ClientGrantType] =
    (int4 *: text *: int4).to[ClientGrantType]

  val clientSecret: Decoder[ClientSecret] =
    (int4 *: text *: text.opt *: timestamptz.opt *:
      text *: timestamptz *: int4).to[ClientSecret]

  val clientClaim: Decoder[ClientClaim] =
    (int4 *: text *: text *: int4).to[ClientClaim]

  val clientCorsOrigin: Decoder[ClientCorsOrigin] =
    (int4 *: text *: int4).to[ClientCorsOrigin]

  val clientIdpRestriction: Decoder[ClientIdpRestriction] =
    (int4 *: text *: int4).to[ClientIdpRestriction]

  val clientProperty: Decoder[ClientProperty] =
    (int4 *: text *: text *: int4).to[ClientProperty]

  // ── API Resource child decoders ────────────────────────────────────

  val apiResourceScope: Decoder[ApiResourceScope] =
    (int4 *: text *: int4).to[ApiResourceScope]

  val apiResourceClaim: Decoder[ApiResourceClaim] =
    (int4 *: text *: int4).to[ApiResourceClaim]

  val apiResourceSecret: Decoder[ApiResourceSecret] =
    (int4 *: text *: text.opt *: timestamptz.opt *:
      text *: timestamptz *: int4).to[ApiResourceSecret]

  val apiResourceProperty: Decoder[ApiResourceProperty] =
    (int4 *: text *: text *: int4).to[ApiResourceProperty]

  // ── API Scope child decoders ───────────────────────────────────────

  val apiScopeClaim: Decoder[ApiScopeClaim] =
    (int4 *: text *: int4).to[ApiScopeClaim]

  val apiScopeProperty: Decoder[ApiScopeProperty] =
    (int4 *: text *: text *: int4).to[ApiScopeProperty]

  // ── Identity Resource child decoders ───────────────────────────────

  val identityResourceClaim: Decoder[IdentityResourceClaim] =
    (int4 *: text *: int4).to[IdentityResourceClaim]

  val identityResourceProperty: Decoder[IdentityResourceProperty] =
    (int4 *: text *: text *: int4).to[IdentityResourceProperty]

  // ── Operational entity decoders (idmgmt schema) ────────────────────

  val deviceCode: Decoder[DeviceCode] =
    (text *: text *: text.opt *: text.opt *:
      text *: text.opt *:
      timestamptz *: timestamptz *: text).to[DeviceCode]

  val signingKey: Decoder[SigningKey] =
    (text *: int4 *: timestamptz *: text.opt *:
      text *: bool *: bool *: text).to[SigningKey]

  val persistedGrant: Decoder[PersistedGrant] =
    (int8 *: text.opt *: text *: text.opt *: text.opt *:
      text *: text.opt *:
      timestamptz *: timestamptz.opt *: timestamptz.opt *: text).to[PersistedGrant]

  val pushedAuthorizationRequest: Decoder[PushedAuthorizationRequest] =
    (int8 *: text *: timestamptz *: text).to[PushedAuthorizationRequest]

  val serverSideSession: Decoder[ServerSideSession] =
    (int8 *: text *: text *: text *: text.opt *: text.opt *:
      timestamptz *: timestamptz *: timestamptz.opt *: text).to[ServerSideSession]

}
