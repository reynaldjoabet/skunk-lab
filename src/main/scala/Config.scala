package example.config

import _root_.pureconfig.*
import fintech.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.pureconfig.given

// ─── Operational refinements ─────────────────────────────────────────

type Host = Host.T
object Host extends RefinedType[String, Not[Blank]]

type Port = Port.T
object Port extends RefinedType[Int, Interval.Closed[1, 65535]]

type Secret = Secret.T
object Secret extends RefinedType[String, MinLength[32]]

type PoolSize   = Int :| Interval.Closed[1, 64]
type RatePerSec = Int :| Positive

// ─── Config sections ─────────────────────────────────────────────────

final case class HttpConfig(host: Host, port: Port) derives ConfigReader

final case class DbConfig(
    host: Host,
    port: Port,
    database: String :| Not[Blank],
    user: String :| Not[Blank],
    password: Secret,
    poolSize: PoolSize,
    schema: String :| Match["""[a-z_][a-z0-9_]*"""]
) derives ConfigReader

final case class LedgerLimits(
    maxTransferAmount: PositiveMinorUnits,
    // Keys are tier names ("tier0".."tier3"); HOCON map keys are always strings.
    dailyLimitByTier: Map[String, PositiveMinorUnits],
    feeBps: RateBps,
    defaultCurrency: CurrencyCode,
    supportedCurrencies: List[CurrencyCode] :| MinLength[1]
) derives ConfigReader

final case class SanctionsConfig(
    blockedCountries: Set[CountryCode],
    highRiskCountries: Set[CountryCode]
) derives ConfigReader

final case class AppConfig(
    http: HttpConfig,
    db: DbConfig,
    jwtSecret: Secret,
    rateLimits: Map[String :| Not[Blank], RatePerSec],
    ledger: LedgerLimits,
    sanctions: SanctionsConfig
) derives ConfigReader

object AppConfig {

  def load: ConfigReader.Result[AppConfig] =
    ConfigSource.default.load[AppConfig]

}
