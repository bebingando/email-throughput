import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "org.bebingando",
      scalaVersion := "2.12.1",
      version      := "0.1.0-SNAPSHOT",
      cancelable in Global := true
    )),
    name := "Email Server Throughput Test Application",
    libraryDependencies ++= Seq(
        scalaTest % Test,
        javaMail,
        akka,
        scallop
    )
  )
