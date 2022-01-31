package org.bebingando.emailthroughput

import java.util.concurrent.Executors
import javax.mail.{MessagingException, SendFailedException}
import javax.mail.internet.AddressException
import akka.actor.{Actor, ActorSystem, AllForOneStrategy, Props}
import akka.actor.SupervisorStrategy.Stop
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.bebingando
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import org.bebingando.emailthroughput.conf.{BaseConf, Conf, EmailMeta, RestAPIConf, RestAPIMeta, SMTPConf, SMTPMeta}

object EmailThroughput extends App {
    val conf = new Conf(args)
    val sc = conf.subcommand.getOrElse { throw new IllegalArgumentException("Invalid subcommand") }.asInstanceOf[BaseConf]
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
            ).asJava
        ).asJava)

    implicit val system = ActorSystem("email-throughput", config = akkaConfig)
    val parent = system.actorOf(Props[Parent], "email-supervisor")

    def makeHeaders(in: List[String]): List[(String, String)] = in.map { pair => {
        val kv = pair.split('|')
        (kv(0), kv(1))
    }}

    // FIXME: could probably clean this up by unifying the meta and conf classes (case class and companion object, respectively)
//    val meta = sc match {
//        case SMTPConf => {
//            import SMTPConf._
//            SMTPMeta(host(), port(), username(), password(), toLocalBase(), toDomain(), fromAddress(), subject(), body(), makeHeaders(extraHeaders()))
//        }
//        case RestAPIConf => {
//            import RestAPIConf._
//            RestAPIMeta(host(), apiKey(), toLocalBase(), toDomain(), fromAddress(), subject(), body(), makeHeaders(extraHeaders()))
//        }
//    }

    val executor = Executors.newCachedThreadPool()
    implicit val ec = ExecutionContext.fromExecutor(executor)

    implicit val to = Timeout(Duration(30, "minutes"))

//    val f = Future(parent ? SpawnChildren(senderCount, emailsPerSender, meta))
//    val r = Await.result(f, Duration(90, "minutes"))
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

// SBDEV: can probably get rid of all this actor craziness! once the Stream sending logic
// for REST and SMTP is complete

case class SpawnChildren(count: Int, emailsPerSender: Int, emailMeta: EmailMeta)
case class SimpleMessage(message: String)
case class Times(sendTime: Long, connectTime: Long)
case class CompletionNotice(count: Int, times: Times)

class Parent extends Actor {
    override def preStart = println(s"${self.path.name}: Starting")
    override def postStop = println(s"${self.path.name}: Stopping")

    override val supervisorStrategy = AllForOneStrategy() {
        case _: Exception => {
            println("Parent saw an exception!")
            Stop
        }
    }

    def receive = {
        case sc: SpawnChildren => {
            val emailSenders = (1 to sc.count).map(n => context.actorOf(Props[EmailSender], "email-sender-" + n))

            emailSenders.foreach(es => context.watch(es))

            val executor = Executors.newFixedThreadPool(sc.count + 1)
            implicit val ec = ExecutionContext.fromExecutor(executor)

            implicit val to = Timeout(Duration(30, "minutes"))

            val startClockTime = System.currentTimeMillis
            val f = Future.sequence(emailSenders.map { s => s ? ((sc.emailMeta, sc.emailsPerSender)) })
            val cns = Await.result(f, Duration(90, "minutes")).map(_.asInstanceOf[CompletionNotice])
            val endClockTime = System.currentTimeMillis
            val results = cns.foldLeft(CompletionNotice(0, Times(0l, 0l)))((i, cn) => {
                println(s"${self.path.name} relayed ${cn.count} emails; aggregated connect time (ms): ${cn.times.connectTime}; aggregated send time (ms): ${cn.times.sendTime}")
                CompletionNotice(i.count + cn.count, Times(i.times.sendTime + cn.times.sendTime, i.times.connectTime + cn.times.connectTime))
            })
            println(s"${self.path.name}: Sent ${results.count} emails across ${sc.count} senders; time spent connecting: ${results.times.connectTime}; time spend sending: ${results.times.sendTime}; elapsed time: ${endClockTime - startClockTime}")
        }
    }
}

/* One of potentially many sending actors in this application */
class EmailSender extends Actor {
    private def printLine(line: String): Unit = println(s"${self.path.name}: $line")

    override def preStart = printLine("Starting")
    override def postStop = printLine("Stopping")

    val rng = new scala.util.Random()

    def send(senderLogic: SenderLogic, localBase: String, toDomain: String, countToSend: Int, initialConnectTime: Long) = {
        printLine(s"Will send $countToSend total emails")

        val numbers = (1 to countToSend)
        val sliceSize = (numbers.size + 7) / 8 // guarantee at least one slice if there is a single item

        val executor = Executors.newFixedThreadPool(countToSend/sliceSize + 1)
        implicit val ec = ExecutionContext.fromExecutor(executor)
        implicit val to = Timeout(Duration(30, "minutes"))

        val sent = Future.traverse(numbers.grouped(sliceSize).toList.zipWithIndex)(pair => Future {
            val (ns, index) = pair

            val sentCountAndTimes: Seq[(Int, Times)] = ns.map { _ =>
                val randomInt = rng.nextInt(999999999)
                val toAddress = s"$localBase+${randomInt.toString()}@$toDomain"

                val output = (Try { senderLogic.send(toAddress) }) match {
                    case Success(SentInfo(addr, t)) =>
                        printLine(s"${self.path.name}: Successfully relayed email for $addr successfully; connect time=${t.connectTime}, send time=${t.sendTime}")
                        (1, t)
                    case Failure(e) =>
                        e match {
                            case ae: AddressException => printLine(s"Invalid address specified <${ae.getRef()}>: ${ae.getMessage()}")
                            case sfe: SendFailedException => printLine(s"Failed to send message to <$toAddress>: ${sfe.getMessage()}")
                            case me: MessagingException => printLine(s"Error building email to <$toAddress>: ${me.getMessage()}")
                            case e: Exception => printLine(s"Exception sending email to <$toAddress>: ${e.getMessage()}")
                        }
                        e.printStackTrace()
                        (0, Times(0L, 0L))
                }
                output
            }
            sentCountAndTimes
        })
        sent.onComplete(_ => { senderLogic.takedown() })
        val r: Seq[(Int, Times)] = Await.result(sent, Duration(90, "minutes")).flatten
        val endClockTime = System.currentTimeMillis
        val sentCount = r.foldLeft(0)((i,j) => i + j._1)
        val times = r.foldLeft[Times](Times(0L, initialConnectTime + senderLogic.initialConnectTime))((t, s) => Times(t.sendTime + s._2.sendTime, t.connectTime + s._2.connectTime))

        sender ! CompletionNotice(sentCount, times)
    }

    def receive = {
        // SBDEV: 0L isn't appropriate
        case (smtp: SMTPMeta, i: Int) => send(new SMTPSender(smtp), smtp.toLocalBase, smtp.toDomain, i, 0L)
        case (api: RestAPIMeta, i: Int) => send(new RestAPISender(api), api.toLocalBase, api.toDomain, i, 0L)
    }
}
