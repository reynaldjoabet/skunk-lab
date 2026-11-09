import Dependencies.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Werror",
  // "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:strictEquality",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)
Global / onChangedBuildSource := ReloadOnSourceChanges

run / fork  := true
javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"
javaOptions += "-Dotel.exporter.otlp.endpoint=https://api.eu1.honeycomb.io"
javaOptions += "-Dotel.logs.exporter=otlp"
javaOptions += "-Dotel.bsp.schedule.delay=1000"
javaOptions += "-Dotel.instrumentation.runtime-telemetry.emit-experimental-metrics=true"
javaOptions += "-Dotel.instrumentation.runtime-telemetry.emit-experimental-jfr-metrics=true"

// ─── Subprojects ──────────────────────────────────────────────────────────────
lazy val `scala2-examples` = (project in file("scala2-examples")).settings(
  scalaVersion         := "2.13.18",
  scalacOptions        := Seq(),
  libraryDependencies ++= Seq(skunkCore)
)

lazy val `scala3-examples` = (project in file("scala3-examples")).settings(
  libraryDependencies ++= Seq(skunkCore, otelJava, otelExporterOtlp)
)

lazy val common = Seq(
  skunkCore,
  skunkCirce,
  otelJava,
  otelExporterOtlp,
  http4sEmberServer,
  http4sDsl,
  http4sCirce,
  circeGeneric,
  circeParser,
  iron,
  ironSkunk,
  ironPureconfig,
  ironJsoniter,
  ironScalacheck,
  pureconfig,
  pureconfigGeneric,
  logback
)

// Settings applied to runnable services, for both packaging formats:
// `<svc>/Universal/stage` (bin/<svc> start script) and `<svc>/assembly`
// (single runnable jar, used by deploy/hetzner.sh).
//
// The dev `javaOptions` above (Honeycomb endpoint etc.) are deliberately NOT
// copied into the start script: -D flags take precedence over OTEL_* env
// vars, so baking them in would make the exporter unconfigurable at deploy
// time. Configure OpenTelemetry in production via OTEL_* env vars instead.
//
// Once sbt-javaagent ships an sbt 2.x build, uncomment the `javaAgents`
// line below and re-enable `JavaAgent` on each project's `enablePlugins`.
lazy val deploySettings = Seq(
  Universal / javaOptions ++= Seq(
    "-J-XX:MaxRAMPercentage=75",     // size the heap from the container/cgroup memory limit
    "-J-XX:+ExitOnOutOfMemoryError", // fail fast so the supervisor restarts a broken JVM
    "-Dotel.java.global-autoconfigure.enabled=true"
  ),
  assembly / assemblyJarName       := s"${name.value}.jar",
  assembly / assemblyMergeStrategy := {
    case PathList("module-info.class")                            => MergeStrategy.discard
    case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
    case PathList("META-INF", "versions", _, "OSGI-INF", _*)      => MergeStrategy.discard
    case other                                                    => (assembly / assemblyMergeStrategy).value(other)
  }
  // javaAgents += "io.github.irevive" % "otel4s-opentelemetry-javaagent" % "2.27.0" % Runtime
)

lazy val ledgerpay = (project in file("ledgerpay"))
  .enablePlugins(JavaAppPackaging) // add JavaAgent once sbt-javaagent supports sbt 2.x
  .settings(
    name                 := "ledgerpay",
    Compile / mainClass  := Some("ledgerpay.Main"),
    libraryDependencies ++= common
  )
  .settings(deploySettings *)

lazy val meterbill = (project in file("meterbill"))
  .enablePlugins(JavaAppPackaging) // add JavaAgent once sbt-javaagent supports sbt 2.x
  .settings(
    name                 := "meterbill",
    Compile / mainClass  := Some("meterbill.Main"),
    libraryDependencies ++= common
  )
  .settings(deploySettings *)

lazy val kudi = (project in file("kudi"))
  .enablePlugins(JavaAppPackaging) // add JavaAgent once sbt-javaagent supports sbt 2.x
  .settings(
    name                 := "kudi",
    Compile / mainClass  := Some("kudi.Main"),
    libraryDependencies ++= common ++ Seq(tapirCore, tapirServer, iron),
    // WIP metric-collector extractions that still need to be wired back into
    // DbMetricsCollector's scope (helpers like mkLongGauge / uniqueOne and the
    // private case classes are not yet exposed). Skip until that refactor lands.
    Compile / unmanagedSources / excludeFilter :=
      HiddenFileFilter ||
        "DbSreInstruments.scala" ||
        "FinancialIntegrityStats.scala" ||
        "PgStatementsStats.scala" ||
        "PlatformOpsStats.scala" ||
        "ProductionInstruments.scala"
  )
  .settings(deploySettings *)

lazy val `identity-management` = (project in file("identity-management"))
  .enablePlugins(JavaAppPackaging) // add JavaAgent once sbt-javaagent supports sbt 2.x
  .settings(
    name                 := "identity-management",
    Compile / mainClass  := Some("idmgmt.Main"),
    libraryDependencies ++= common
  )
  .settings(deploySettings *)

// rootProject = (project in file(".")), autoAggregate = discovers all subprojects automatically
lazy val root = rootProject.autoAggregate
  .settings(
    libraryDependencies ++= common ++ Seq(
      jsoniterCore,
      jsoniterMacros
    ),
    name           := "skunk-lab",
    version        := "1.0",
    publish / skip := true,
    // WIP: uses .refined[X] / .assume which aren't actual iron-skunk API.
    // Rewrite to .imap(applyUnsafe)(_.value) before re-enabling.
    Compile / unmanagedSources / excludeFilter :=
      HiddenFileFilter || "persistence.scala"
  )

// ─── Remote Cache (pick ONE option, uncomment it) ─────────────────────────────

// Option A: Self-hosted bazel-remote with mTLS
// Global / remoteCache                     := Some(uri("grpcs://your-cache-host:2024"))
// Global / remoteCacheTlsCertificate       := Some(file("/path/to/ca.crt"))
// Global / remoteCacheTlsClientCertificate := Some(file("/path/to/client.crt"))
// Global / remoteCacheTlsClientKey         := Some(file("/path/to/client.pem"))

// Option B: NativeLink Cloud — $HOME/.sbt/nativelink_credential.txt: x-nativelink-api-key=*******
// Global / remoteCache        := Some(uri("grpcs://something.build-faster.nativelink.net"))
// Global / remoteCacheHeaders += IO.read(BuildPaths.defaultGlobalBase / "nativelink_credential.txt").trim

// Option C: BuildBuddy Cloud — $HOME/.sbt/buildbuddy_credential.txt: x-buildbuddy-api-key=*******
// Global / remoteCache        := Some(uri("grpcs://something.buildbuddy.io"))
// Global / remoteCacheHeaders += IO.read(BuildPaths.defaultGlobalBase / "buildbuddy_credential.txt").trim

// Option D: Dev/testing only (no auth, local)
// Global / remoteCache := Some(uri("grpc://localhost:8080"))
