import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "org.bebingando",
      scalaVersion := "2.12.1",
      version      := "0.1.0-SNAPSHOT",
      cancelable in Global := true
    )),
    name := "email-throughput",
    libraryDependencies ++= Seq(
        scalaTest % Test,
        javaMail,
        akka,
        scallop
    )
  )

import AssemblyKeys._

assemblySettings
