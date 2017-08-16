import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"
  lazy val javaMail = "javax.mail" % "mail" % "1.4"
  lazy val akka = "com.typesafe.akka" %% "akka-actor" % "2.5.3"
  lazy val scallop = "org.rogach" %% "scallop" % "3.1.0"
}
