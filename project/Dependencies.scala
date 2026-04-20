import sbt.*

object Dependencies {

  object Versions {

    val skunkVersion    = "1.0.0"
    val otel4sVersion   = "0.16.0"
    val otelJavaVersion = "1.55.0"
    val circeVersion    = "0.14.14"
    val http4sVersion   = "0.23.30"
    val refinedVersion  = "0.11.3"

  }
  lazy val skunkCore  = "org.tpolecat"  %% "skunk-core"      % Versions.skunkVersion
  lazy val skunkCirce = "org.tpolecat"  %% "skunk-circe"     % Versions.skunkVersion
  lazy val otelJava   = "org.typelevel" %% "otel4s-oteljava" % Versions.otel4sVersion

  lazy val otelExporterOtlp = "io.opentelemetry" % "opentelemetry-exporter-otlp" % Versions
    .otelJavaVersion % Runtime

  lazy val otelSdkAutoconfigure =
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % Versions
      .otelJavaVersion % Runtime

  def http4s(artifact: String) = "org.http4s" %% s"http4s-$artifact" % Versions.http4sVersion
  lazy val http4sEmberServer   = http4s("ember-server")
  lazy val http4sDsl           = http4s("dsl")
  lazy val http4sCirce         = http4s("circe")

  def circe(artifact: String) = "io.circe" %% s"circe-$artifact" % Versions.circeVersion
  lazy val circeGeneric       = circe("generic")
  lazy val circeParser        = circe("parser")

  lazy val refined = "eu.timepit" %% "refined" % Versions.refinedVersion

}
