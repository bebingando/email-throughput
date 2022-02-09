import sbt._

object Dependencies {
  val akkaVersion = "2.5.26"
  val json4sVersion = "4.0.3"
  val sttpVersion = "3.3.18"
  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  lazy val akkaStreams = "com.typesafe.akka" %% "akka-stream" % akkaVersion
  lazy val javaActivation = "javax.activation" % "activation" % "1.1.1"
  lazy val javaMail = "javax.mail" % "mail" % "1.4"
  lazy val json4s = "org.json4s" %% "json4s-native" % json4sVersion
  lazy val json4sAST = "org.json4s" %% "json4s-ast" % json4sVersion
  lazy val json4sCore = "org.json4s" %% "json4s-core" % json4sVersion
  lazy val json4sScalaP = "org.json4s" %% "json4s-scalap" % json4sVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"
  lazy val scallop = "org.rogach" %% "scallop" % "3.1.0"
  lazy val sttp = "com.softwaremill.sttp.client3" %% "core" % sttpVersion
  lazy val sttpAsyncHttpClientBackendFuture = "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % sttpVersion
  lazy val sttpJson4s = "com.softwaremill.sttp.client3" %% "json4s" % sttpVersion
}
