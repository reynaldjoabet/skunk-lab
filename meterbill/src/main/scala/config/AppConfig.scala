package meterbill.config

import com.comcast.ip4s.Host
import com.comcast.ip4s.Port

case class DbConfig(
    host: String,
    port: Int,
    user: String,
    password: String,
    database: String,
    poolSize: Int,
    ssl: Boolean
) {

  // Keep the password out of logs and error messages that print the config.
  override def toString: String =
    s"DbConfig($host, $port, $user, <redacted>, $database, $poolSize, ssl=$ssl)"

}

case class HttpConfig(host: Host, port: Port)
case class AppConfig(db: DbConfig, http: HttpConfig)

object AppConfig {

  final case class ConfigError(problems: List[String])
      extends RuntimeException(s"Invalid configuration: ${problems.mkString("; ")}")

  /**
    * Reads the process environment; throws [[ConfigError]] listing every problem at once.
    */
  def load: AppConfig = from(sys.env).fold(e => throw e, identity)

  def from(env: Map[String, String]): Either[ConfigError, AppConfig] = {
    def str(key: String, default: String): String =
      env.get(key).map(_.trim).filter(_.nonEmpty).getOrElse(default)

    def parsed[A](key: String, default: String, expected: String)(
        parse: String => Option[A]
    ): Either[String, A] = {
      val raw = str(key, default)
      parse(raw).toRight(s"$key must be $expected, got '$raw'")
    }

    def int(key: String, default: String, min: Int, max: Int): Either[String, Int] =
      parsed(key, default, s"an integer between $min and $max")(
        _.toIntOption.filter(n => n >= min && n <= max)
      )

    val dbPort   = int("DB_PORT", "5432", 1, 65535)
    val poolSize = int("DB_POOL_SIZE", "10", 1, 1000)
    val ssl      = parsed("DB_SSL", "false", "true or false")(_.toLowerCase.toBooleanOption)
    // No default: refuse to start without a password.
    val password = env.get("DB_PASSWORD").filter(_.nonEmpty).toRight("DB_PASSWORD must be set")
    val httpHost = parsed("HTTP_HOST", "0.0.0.0", "an IP address or hostname")(Host.fromString)
    val httpPort =
      parsed("HTTP_PORT", "8080", "a port between 0 and 65535")(_.toIntOption.flatMap(Port.fromInt))

    val problems =
      List(dbPort, poolSize, ssl, password, httpHost, httpPort).collect { case Left(p) => p }

    val config = for {
      dbPort   <- dbPort
      poolSize <- poolSize
      ssl      <- ssl
      password <- password
      httpHost <- httpHost
      httpPort <- httpPort
    } yield AppConfig(
      db = DbConfig(
        host = str("DB_HOST", "localhost"),
        port = dbPort,
        user = str("DB_USER", "meterbill"),
        password = password,
        database = str("DB_NAME", "meterbill"),
        poolSize = poolSize,
        ssl = ssl
      ),
      http = HttpConfig(httpHost, httpPort)
    )

    config.left.map(_ => ConfigError(problems))
  }

}
