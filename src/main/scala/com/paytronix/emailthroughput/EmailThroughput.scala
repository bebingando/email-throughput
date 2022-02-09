package com.paytronix.emailthroughput

import java.util.concurrent.Executors
import akka.actor.ActorSystem
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object EmailThroughput extends App {
    val conf = new Conf(args)
    val sc = conf.subcommand.getOrElse {
        throw new IllegalArgumentException("Invalid subcommand")
    }.asInstanceOf[BaseConf]
    println("Email Service (Mailgun, etc) Throughput Test Application")

    val senderCount = sc.threadCount() //8
    val emailsPerSender = sc.emailCount() / senderCount
    println(s"Will send $emailsPerSender emails from each of the $senderCount senders")

    val akkaConfig = ConfigFactory.parseMap(
        Map("dispatcher" -> Map(
            "type" -> "Dispatcher",
            "executor" -> "thread-pool-executor",
            "thread-pool-executor" -> Map("fixed-pool-size" -> 10).asJava,
            "throughput" -> 1
        ).asJava).asJava)

    implicit val system = ActorSystem("email-throughput", config = akkaConfig)

    def makeHeaders(in: List[String]): List[(String, String)] = in.map { pair => {
        val kv = pair.split('|')
        (kv(0), kv(1))
    }}

    val executor = Executors.newCachedThreadPool()
    implicit val ec = ExecutionContext.fromExecutor(executor)

    implicit val to = Timeout(Duration(30, "minutes"))

    val stream = sc match {
        case SMTPConf =>
            import SMTPConf._
            new SmtpStream(sc.emailCount(), senderCount, SMTPMeta(host(), port(), username(), password(), toLocalBase(), toDomain(), fromAddress(), subject(), body(), makeHeaders(extraHeaders())))
        case RestAPIConf =>
            import RestAPIConf._
            new RestApiStream(sc.emailCount(), senderCount, RestAPIMeta(host(), apiKey(), toLocalBase(), toDomain(), fromAddress(), subject(), body(), makeHeaders(extraHeaders())))
    }
    stream.execute
    system.terminate().onComplete(_ => System.exit(0))
}
