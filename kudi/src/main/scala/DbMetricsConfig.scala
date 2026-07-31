package kudi

import scala.concurrent.duration.*

final case class DbMetricsConfig(
    interval: FiniteDuration = 15.seconds,
    pollTimeout: FiniteDuration = 10.seconds,
    collectSystemMetrics: Boolean = true,
    collectDbSreMetrics: Boolean = true,
    collectBusinessMetrics: Boolean = true,
    collectAuthMetrics: Boolean = true,
    collectFinancialIntegrityMetrics: Boolean = true,
    collectPlatformOpsMetrics: Boolean = false,

    // Keep disabled by default until the extension is installed and tested.
    collectPgStatStatements: Boolean = false
)
