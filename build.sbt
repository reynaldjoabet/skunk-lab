import Dependencies.*
import Dependencies.Versions.*

// ─── Common settings (bare = injected into ALL subprojects in sbt 2.x) ────────
scalaVersion := "3.3.7"

scalacOptions ++= Seq(
  "-no-indent", // enforce brace style (does NOT rewrite files)
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Wunused:imports",
  // "-Wunused:privates",
  // "-Wunused:locals",
  // "-Wunused:explicits",
  // "-Wunused:implicits",
  // "-Wunused:params",
  // "-Wvalue-discard",
  "-language:strictEquality",
  "-Xmax-inlines:100000"
)

run / fork  := true
javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"

// ─── Subprojects ──────────────────────────────────────────────────────────────
lazy val `scala2-examples` = (project in file("scala2-examples")).settings(
  scalaVersion         := "2.13.18",
  scalacOptions        := Seq(),
  libraryDependencies ++= Seq(skunkCore)
)

lazy val `scala3-examples` = (project in file("scala3-examples")).settings(
  libraryDependencies ++= Seq(skunkCore, otelJava, otelExporterOtlp, otelSdkAutoconfigure)
)

lazy val common = Seq(
  skunkCore,
  skunkCirce,
  otelJava,
  otelExporterOtlp,
  otelSdkAutoconfigure,
  http4sEmberServer,
  http4sDsl,
  http4sCirce,
  circeGeneric,
  circeParser,
  refined
)

lazy val ledgerpay = (project in file("ledgerpay")).settings(
  name                 := "ledgerpay",
  libraryDependencies ++= common
)

lazy val meterbill = (project in file("meterbill")).settings(
  name                 := "meterbill",
  libraryDependencies ++= common
)

lazy val kudi = (project in file("kudi")).settings(
  name                 := "kudi",
  libraryDependencies ++= common
)

lazy val `identity-management` = (project in file("identity-management")).settings(
  name                 := "identity-management",
  libraryDependencies ++= common
)

// rootProject = (project in file(".")), autoAggregate = discovers all subprojects automatically
lazy val root = rootProject
  .autoAggregate
  .settings(
    libraryDependencies ++= common,
    name                 := "skunk-lab",
    version              := "1.0",
    publish / skip       := true
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
