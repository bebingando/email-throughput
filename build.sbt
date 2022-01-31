import Dependencies._

lazy val root = (project in file("."))
  .settings(
    inThisBuild(List(
      organization := "org.bebingando",
      scalaVersion := "2.12.1",
      version      := "0.1.0-SNAPSHOT",
      cancelable in Global := true,
      fork in run := true
    )),
    name := "email-throughput",
    libraryDependencies ++= Seq(
        akkaActor,
        akkaStreams,
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
    assemblyMergeStrategy in assembly := {
        case PathList(x, xs @ _*) if List(
                "io.netty.versions.properties",
                "META-INF"
            ).contains(x) => MergeStrategy.discard
        case _ => MergeStrategy.first
    }
  )