addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.19")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.17")

// https://eed3si9n.com/sbt-1.8.0-beta
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
