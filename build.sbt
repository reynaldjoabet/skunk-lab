import Dependencies.*
import Dependencies.Versions.*

ThisBuild / scalaVersion := "3.3.7"

ThisBuild / scalacOptions ++= Seq(
  "-no-indent",
  "-rewrite",
  "-deprecation", // Warns about deprecated APIs
  "-feature",     // Warns about advanced language features
  "-unchecked",
  // "-Wunused:imports",
  //   "-Wunused:privates",
  //   "-Wunused:locals",
  //   "-Wunused:explicits",
  //   "-Wunused:implicits",
  //   "-Wunused:params",
  //   "-Wvalue-discard",
  "-language:strictEquality",
  "-Xmax-inlines:100000"
)

lazy val `scala2-examples` = (project in file("scala2-examples")).settings(
  scalaVersion         := "2.13.18",
  scalacOptions        := Seq(),
  libraryDependencies ++= Seq(skunkCore)
)

lazy val `scala3-examples` = (project in file("scala3-examples")).settings(
  libraryDependencies ++= Seq(skunkCore, otelJava, otelExporterOtlp, otelSdkAutoconfigure)
)

run / fork  := true
javaOptions += "-Dotel.java.global-autoconfigure.enabled=true"

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
  libraryDependencies ++= common,
  run / fork           := true,
  javaOptions          += "-Dotel.java.global-autoconfigure.enabled=true"
)

lazy val meterbill = (project in file("meterbill")).settings(
  name                 := "meterbill",
  libraryDependencies ++= common,
  run / fork           := true
)

lazy val kudi = (project in file("kudi")).settings(
  name                 := "kudi",
  libraryDependencies ++= common,
  run / fork           := true
)

lazy val `identity-management` = (project in file("identity-management")).settings(
  name                 := "identity-management",
  libraryDependencies ++= common,
  run / fork           := true
)

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= common,
    name                 := "skunk-lab",
    version              := "1.0",
    publish / skip       := true
  )
  .aggregate(
    `scala2-examples`,
    `scala3-examples`,
    ledgerpay,
    meterbill,
    kudi,
    `identity-management`
  )
