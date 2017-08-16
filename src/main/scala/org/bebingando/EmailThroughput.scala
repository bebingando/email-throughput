package org.bebingando.emailthroughput

import java.util.Properties
import javax.mail.{Address, Message, MessagingException, SendFailedException, Session, Transport}
import javax.mail.internet.{AddressException, InternetAddress, MimeMessage}

import akka.actor.{Actor, ActorSystem, AllForOneStrategy, PoisonPill, Props}
import akka.actor.SupervisorStrategy.Stop
import akka.pattern.ask
import akka.util.Timeout
import org.rogach.scallop.{ScallopConf, Subcommand, flagConverter, stringConverter, stringListConverter}

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object EmailThroughput extends App {
    val conf = new Conf(args)
    println("Email Server Throughput Test Application")

    val senderCount = 8
    val emailsPerSender = conf.emailCount() / senderCount
    println(s"Will send $emailsPerSender emails from each of the $senderCount senders")

    val system = ActorSystem("email-throughput")
    val parent = system.actorOf(Props[Parent], "email-supervisor")

    val headers: List[(String, String)] = conf.extraHeaders().map { pair =>
        val kv = pair.split('|')
        (kv(0), kv(1))
    }
    val meta = EmailMeta(conf.smtpHost(), conf.toAddressLocalBase(), conf.toAddressDomain(), conf.fromAddress(), conf.subject(), conf.body(), headers)

    implicit val ec = ExecutionContext.global
    implicit val to = Timeout(Duration(30, "minutes"))
    val f = Future(parent ? SpawnChildren(senderCount, emailsPerSender, meta))
    Await.result(f, Duration(90, "minutes"))

    system.terminate()
}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    lazy val smtpHost = opt[String]("smtp-host", noshort=true, required=true, descr="Name of the host running the email server instance to test")
    lazy val toAddressLocalBase = opt[String]("to-address-local-base", noshort=true, default=Some("test"), descr="Base onto which to append random numerics. Ex) 'foo' in 'foo+1234@domain.test'")
    lazy val toAddressDomain = opt[String]("to-address-domain", noshort=true, default=Some("test.test"), descr="Domain of recipient addresses. Ex) 'domain.test' in 'foo+1234@domain.test'")
    lazy val fromAddress = opt[String]("from-address", required=true, descr="Email address used as the sender's address")
    lazy val subject = opt[String]("subject", default=Some("Test Email"), descr="Subject line to use in test emails")
    lazy val body = opt[String]("body", default=Some("Hello World!"), descr="Message body to use in test emails")
    lazy val emailCount = opt[Int]("email-count", short='c', required=true, descr="Number of emails to generate and handoff to the email server. Make it a multiple of 8, ok?")
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
    verify()
}

case class SpawnChildren(
    count: Int,
    emailsPerSender: Int,
    emailMeta: EmailMeta
)

case class EmailMeta(
    smtpHost: String,
    toAddressLocalBase: String,
    toAddressDomain: String,
    fromAddress: String,
    subject: String,
    body: String,
    headers: List[(String, String)]
)

case class SimpleMessage(message: String)
case class CompletionNotice(count: Int, message: String)

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

            implicit val ec = ExecutionContext.global
            implicit val to = Timeout(Duration(30, "minutes"))

            val f = Future.sequence(emailSenders.map { s => s ? ((sc.emailMeta, sc.emailsPerSender)) })
            val cns = Await.result(f, Duration(90, "minutes")).map(_.asInstanceOf[CompletionNotice])
            val resultCount = cns.foldLeft(0)((i, cn) => {
                println(cn.message)
                i + cn.count
            })
            println(s"${self.path.name}: Execution of ${sc.count} email senders complete")
            println(s"${self.path.name}: ${resultCount} emails sent across all senders")
        }
    }
}

/* One of potentially many sending actors in this application */
class EmailSender extends Actor {

    override def preStart = println(s"${self.path.name}: Starting")
    override def postStop = println(s"${self.path.name}: Stopping")

    val rng = new scala.util.Random()
    val maxConnections = 8

    def receive = {
        case (m: EmailMeta, i: Int) => {
            println(s"${self.path.name}: Will send $i total emails")
            var sentCount: Int = 0
            var smtpConnectionCount: Int = 0

            implicit val ec = ExecutionContext.global
            implicit val to = Timeout(Duration(30, "minutes"))

            val properties = new Properties()
            properties.put("mail.smtp.host", m.smtpHost)
            val session = Session.getDefaultInstance(properties, null)

            val windowSize = i/maxConnections

            val nums = (1 to i)
            val sliceSize = (nums.size + 7) / 8 // guarantee at least one slice if there is a single item

            val sent = Future.traverse(nums.grouped(sliceSize).toList.zipWithIndex)(pair => Future {
                val (ns, index) = pair
                val transport = session.getTransport("smtp")

                ns.foreach { n => 
                    val randomInt = rng.nextInt(999999999)
                    val toAddress = s"${m.toAddressLocalBase}+${randomInt.toString()}@${m.toAddressDomain}"

                    (Try {
                        val toInetAddress = new InternetAddress(toAddress, true)
                        val fromInetAddress = new InternetAddress(m.fromAddress)
                        val recipients = Array(toInetAddress).map(a => a.asInstanceOf[Address])

                        val message = new MimeMessage(session)
                        message.setFrom(fromInetAddress)
                        message.setSubject(m.subject)
                        message.setText(m.body)
                        message.setRecipients(Message.RecipientType.TO, recipients)
                        m.headers.foreach(kv => message.setHeader(kv._1, kv._2))
        
                        // Only open it if it isn't already
                        if (!transport.isConnected()) {
                            transport.connect()
                            synchronized { smtpConnectionCount += 1 }
                        }
                        transport.sendMessage(message, recipients)
                        toInetAddress
                    }) match {
                        case Success(addr) =>
                            println(s"${self.path.name}: Successfully relayed email for $addr successfully via ${m.smtpHost}")
                            synchronized { sentCount += 1 }
                        case Failure(e) => e match {
                            case ae: AddressException => println(s"${self.path.name}: Invalid address specified <" + ae.getRef() + ">: " + ae.getMessage())
                            case sfe: SendFailedException => println(s"${self.path.name}: Failed to send message to <" + toAddress + ">: " + sfe.getMessage())
                            case me: MessagingException => println(s"${self.path.name}: Error building email to <" + toAddress + ">: " + me.getMessage())
                            case e: Exception => println(s"${self.path.name}: Exception sending email to <" + toAddress + ">: " + e.getMessage())                            
                        }
                    }
                }
                // only close it if it is open
                if (transport.isConnected()) {
                    Try (transport.close()) match {
                        case Success(_) => synchronized { smtpConnectionCount -= 1 }
                        case Failure(e) => println(s"${self.path.name}: Exception while closing connection: " + e.getMessage())
                    }
                }
                sentCount
            })
            Await.result(sent, Duration(90, "minutes"))
            sender ! CompletionNotice(sentCount, s"${self.path.name} relayed $sentCount emails")
        }
    }
}
