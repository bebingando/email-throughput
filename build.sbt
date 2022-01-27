import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "org.bebingando",
      scalaVersion := "2.12.1",
      version      := "0.1.0-SNAPSHOT",
      cancelable in Global := true,
      fork in run := true
    )),
    name := "email-throughput",
    libraryDependencies ++= Seq(
        akka,
        javaMail,
        json4s,
        json4sAST,
        json4sCore,
        json4sScalaP,
        scalaTest % Test,
        scallop,
        sttp,
        sttpAsyncHttpClientBackendFuture,
        sttpJson4s
    ),
    assemblyJarName := "email-throughput.jar",
     /* Building a fat JAR application, so it should be fine to use MergeStrategy.discard
      * since we are providing all dependencies in our JAR
      */
    assemblyMergeStrategy in assembly := {
        case _ => MergeStrategy.discard
    },
    mainClass in assembly := Some("org.bebingando.EmailThroughput")
  )

