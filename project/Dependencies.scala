import sbt.*

object Dependencies {

  private object Version {

    val http4s     = "0.23.36"
    val circe      = "0.14.15"
    val skunk      = "1.1.0-RC1"
    val otel4s     = "0.16.0"
    val otelJava   = "1.55.0" // OpenTelemetry Java SDK runtime exporters
    val tapir      = "1.13.18"
    val iron       = "3.3.1"
    val pureconfig = "0.17.10"
    val jsoniter   = "2.38.12"

  }

  // ─── Helpers ─────────────────────────────────────────────────────────
  private def http4s(a: String) =
    "org.http4s" %% s"http4s-$a" % Version.http4s

  private def circe(a: String) =
    "io.circe" %% s"circe-$a" % Version.circe

  private def tapir(a: String) =
    "com.softwaremill.sttp.tapir" %% s"tapir-$a" % Version.tapir

  // ─── DB ──────────────────────────────────────────────────────────────
  lazy val skunkCore  = "org.tpolecat" %% "skunk-core"  % Version.skunk
  lazy val skunkCirce = "org.tpolecat" %% "skunk-circe" % Version.skunk

  // ─── HTTP ────────────────────────────────────────────────────────────
  lazy val http4sEmberServer = http4s("ember-server")
  lazy val http4sDsl         = http4s("dsl")
  lazy val http4sCirce       = http4s("circe")

  // ─── JSON ────────────────────────────────────────────────────────────
  lazy val circeGeneric = circe("generic")
  lazy val circeParser  = circe("parser")

  // ─── Observability ───────────────────────────────────────────────────
  lazy val otelJava = "org.typelevel" %% "otel4s-oteljava" % Version.otel4s

  lazy val otelExporterOtlp =
    "io.opentelemetry" % "opentelemetry-exporter-otlp" % Version.otelJava % Runtime

  // ─── Tapir ───────────────────────────────────────────────────────────
  lazy val tapirCore   = tapir("core")
  lazy val tapirServer = tapir("server")

  // ─── Refined types ───────────────────────────────────────────────────
  private def iron(a: String) =
    "io.github.iltotore" %% s"iron-$a" % Version.iron

  lazy val iron           = "io.github.iltotore" %% "iron" % Version.iron
  lazy val ironSkunk      = iron("skunk")
  lazy val ironPureconfig = iron("pureconfig")
  lazy val ironJsoniter   = iron("jsoniter")
  lazy val ironScalacheck = iron("scalacheck")

  // ─── Config ──────────────────────────────────────────────────────────
  lazy val pureconfig =
    "com.github.pureconfig" %% "pureconfig-core" % Version.pureconfig

  lazy val pureconfigGeneric =
    "com.github.pureconfig" %% "pureconfig-generic-scala3" % Version.pureconfig

  // ─── JSON (jsoniter-scala) ───────────────────────────────────────────
  lazy val jsoniterCore =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % Version.jsoniter

  lazy val jsoniterMacros =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Version.jsoniter % Provided

}
