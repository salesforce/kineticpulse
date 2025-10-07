import xerial.sbt.Sonatype.sonatypeCentralHost

val scala213Version = "2.13.15"
val scala333Version = "3.3.3"
val prometheusVersion = "0.16.+"

val scalaTestArtifact    = "org.scalatest"     %% "scalatest"           % "3.2.+"    % Test
val prometheusClient     = "io.prometheus"     % "simpleclient"         % prometheusVersion
val prometheusCommon     = "io.prometheus"     % "simpleclient_common"  % prometheusVersion
val prometheusHotSpot    = "io.prometheus"     % "simpleclient_hotspot" % prometheusVersion
val logbackArtifact      = "ch.qos.logback"    % "logback-classic"      % "1.5.19"

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  publishTo := sonatypePublishToBundle.value,
  licenses := Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
  homepage := Some(url("https://github.com/salesforce/kineticpulse")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/salesforce/kineticpulse"),
      "scm:git:git@github.com:salesforce/kineticpulse.git"
    )
  ),
  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USERNAME",""),
    sys.env.getOrElse("SONATYPE_PASSWORD","")
  ),
  developers := List(
    Developer(
      id = "schowsf",
      name = "Simon Chow",
      email = "simon.chow@salesforce.com",
      url = url("https://github.com/schowsf")
    )
  ),
  useGpgPinentry := true,
  ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
)


lazy val commonSettings = Seq(
  scalaVersion := scala213Version,
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    // scala 2.13.3 introduced this new lint rule, but it does not work too well with things
    // that depend on shapeless (e.g., circe) https://github.com/scala/bug/issues/12072.
    // Disabling it for now.
    case Some((2, 13)) => Seq("-Xlint:-byname-implicit,_", "-deprecation", "-feature", "-Xlint", "-Xfatal-warnings")
    case Some((3, 3)) => Seq()
    case _ => Seq("-deprecation", "-feature", "-Xlint", "-Xfatal-warnings")
  }),
  crossScalaVersions := Seq(
    scala213Version,
    scala333Version
  ),
  libraryDependencies += scalaTestArtifact,
  organization := "com.salesforce.mce",
  headerLicense := Some(HeaderLicense.Custom(
    """|Copyright (c) 2022, salesforce.com, inc.
       |All rights reserved.
       |SPDX-License-Identifier: BSD-3-Clause
       |For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
       |""".stripMargin
  ))
)
lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    name := "kineticpulse",
    libraryDependencies ++= Seq(
    )
  ).
  aggregate(metric)


lazy val metric = (project in file("kineticpulse-metric")).
  enablePlugins(BuildInfoPlugin).
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    name := "kineticpulse-metric",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "com.salesforce.mce.kineticpulse",
    fork := true,
    libraryDependencies ++= Seq(
      guice,
      prometheusClient,
      prometheusCommon,
      prometheusHotSpot,
      logbackArtifact
    )
  )
