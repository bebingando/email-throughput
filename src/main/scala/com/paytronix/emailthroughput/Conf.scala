package com.paytronix.emailthroughput

import org.rogach.scallop.{stringConverter, stringListConverter, ScallopConf, Subcommand}
import scala.util.{Failure, Success, Try}

trait BaseConf { this: ScallopConf =>
    lazy val host = opt[String]("host", short='h', required=true, descr="Name of the host to connect to for relaying the test emails")
    lazy val toLocalBase = opt[String]("to-address-local-base", noshort=true, default=Some("test"), descr="Base onto which to append random numerics. Ex) 'foo' in 'foo+1234@domain.test'")
    lazy val toDomain = opt[String]("to-address-domain", noshort=true, default=Some("test.test"), descr="Domain of recipient addresses. Ex) 'domain.test' in 'foo+1234@domain.test'")
    lazy val fromAddress = opt[String]("from-address", required=true, descr="Email address used as the sender's address")
    lazy val subject = opt[String]("subject", default=Some("Test Email"), descr="Subject line to use in test emails")
    lazy val body = opt[String]("body", default=Some("Hello World!"), descr="Message body to use in test emails")
    lazy val emailCount = opt[Int]("email-count", short='c', required=true, descr="Number of emails to generate and handoff to the email server. Make it a multiple of 8, ok?")
    lazy val threadCount = opt[Int]("thread-count", noshort=true, default=Some(1), descr="Thread count level of concurrency")
    lazy val extraHeaders = trailArg[List[String]]("headers", default=Some(List.empty), required=false, descr="Header(s) to apply to each test email. Separate key from value with a pipe '|'. Ex) X-Account-ID|12345")
    validate (extraHeaders) { headers =>
        Try(headers.map { pair =>
            val kv = pair.split('|')
            if (kv.length < 2 || kv(0).length <= 0) {
                throw new IllegalArgumentException(s"'$pair' is not valid")
            }
        }) match {
            case Success(_) => Right(Unit)
            case Failure(e) => Left(s"Failed to parse extra headers: ${e.getMessage()}")
        }
    }
}

object RestAPIConf extends Subcommand("api") with BaseConf {
    lazy val apiKey = opt[String](name = "api-key", short='k', required=true, descr="API key for authenticating REST API call")
}

object SMTPConf extends Subcommand("smtp") with BaseConf {
    lazy val username = opt[String]("user", short='u', required=true, descr="Username for authenticating SMTP connection")
    lazy val password = opt[String]("password", short='p', required=true, descr="Password for authenticating SMTP connection")
    lazy val port = opt[Int]("port", noshort=true, default=Some(25), descr="TCP port to use for SMTP connection (default is 25)")
}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    addSubcommand(RestAPIConf)
    addSubcommand(SMTPConf)
    verify()
}

sealed abstract class EmailMeta(
    host: String,
    toLocalBase: String,
    toDomain: String,
    fromAddress: String,
    subject: String,
    body: String,
    headers: List[(String, String)]
) {
    def toLocalBase: String
    def toDomain: String
}

final case class SMTPMeta(
    host: String,
    port: Int,
    username: String,
    password: String,
    toLocalBase: String,
    toDomain: String,
    fromAddress: String,
    subject: String,
    body: String,
    headers: List[(String, String)]
) extends EmailMeta(host, toLocalBase, toDomain, fromAddress, subject, body, headers)

final case class RestAPIMeta(
    host: String,
    apiKey: String,
    toLocalBase: String,
    toDomain: String,
    fromAddress: String,
    subject: String,
    body: String,
    headers: List[(String, String)]
) extends EmailMeta(host, toLocalBase, toDomain, fromAddress, subject, body, headers)