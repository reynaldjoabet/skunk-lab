package meterbill.config

case class DbConfig(
  host: String,
  port: Int,
  user: String,
  password: String,
  database: String,
  poolSize: Int
)

case class HttpConfig(host: String, port: Int)
case class AppConfig(db: DbConfig, http: HttpConfig)

object AppConfig {

  def load: AppConfig = AppConfig(
    db = DbConfig(
      host = sys.env.getOrElse("DB_HOST", "localhost"),
      port = sys.env.getOrElse("DB_PORT", "5432").toInt,
      user = sys.env.getOrElse("DB_USER", "meterbill"),
      password = sys.env.getOrElse("DB_PASSWORD", "meterbill_secret"),
      database = sys.env.getOrElse("DB_NAME", "meterbill"),
      poolSize = sys.env.getOrElse("DB_POOL_SIZE", "10").toInt
    ),
    http = HttpConfig(
      host = sys.env.getOrElse("HTTP_HOST", "0.0.0.0"),
      port = sys.env.getOrElse("HTTP_PORT", "8080").toInt
    )
  )

}
