package com.paytronix.emailthroughput

import java.util.Properties
import akka.stream.{ActorMaterializer, FlowShape}
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Source}
import akka.NotUsed
import akka.actor.ActorSystem
import javax.activation.{CommandMap, MailcapCommandMap}
import javax.mail.internet.{InternetAddress, MimeBodyPart, MimeMessage, MimeMultipart}
import javax.mail.{Address, Authenticator, Message, PasswordAuthentication, Session, Transport}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, SECONDS}
import org.json4s.native.Serialization
import org.json4s.DefaultFormats
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.client3.{basicRequest, multipart, UriContext}
import sttp.client3.json4s.asJson
import sttp.model.Uri
import scala.util.{Failure, Success, Try}

case class Response(message: String, id: String)

abstract class SendWork
case class SmtpWork(message: MimeMessage) extends SendWork
case class RestApiWork(to: String) extends SendWork

abstract class Stream[S <: SendWork](
    count: Int,
    threadCount: Int,
    meta: EmailMeta
)(implicit actorSystem: ActorSystem) {
    implicit val mat = ActorMaterializer()
    protected def makeSendWork: List[S]
    protected def makeRecipientAddressString(number: Int) = s"${meta.toLocalBase}$number@${meta.toDomain}"
    private def setup = {
        val source = Source(makeSendWork)
        val streamSource = source.via(assembleFlows(makeFlow, threadCount))
        val sink = makeSink
        streamSource.runWith(sink)
    }

    protected def takeDown(): Unit

    def execute = {
        val start = System.currentTimeMillis
        val sent = Await.result(setup, Duration.Inf)
        val executedTime = System.currentTimeMillis - start
        println(s"Sent $sent in ${executedTime}ms (${(sent.toLong * 1000l * 60l * 60l)/executedTime}/hr)")
    }

    protected def makeFlow(): Flow[S, Either[String, Unit], NotUsed]

    private def assembleFlows[In, Out](
        worker: () => Flow[In, Out, Any],
        threadCount: Int
    ): Flow[In, Out, NotUsed] = {
        import GraphDSL.Implicits._

        val flows = List.fill(threadCount)(worker().async("dispatcher"))
        val graph = GraphDSL.create(flows) { implicit builder => flowShapes =>
            val balancer = builder.add(Balance[In](threadCount, waitForAllDownstreams = true))
            val merge = builder.add(Merge[Out](threadCount))
            flowShapes.foreach { flow => balancer ~> flow ~> merge }
            FlowShape(balancer.in, merge.out)
        }
        Flow.fromGraph(graph).mapMaterializedValue(_ => NotUsed)
    }

    private def makeSink =
        Sink.fold[Int, Either[String,Unit]](0) { case (acc, _) => acc + 1 }
}

class RestApiStream(
    count: Int,
    threadCount: Int,
    meta: RestAPIMeta
)(implicit actorSystem: ActorSystem) extends Stream[RestApiWork](count, threadCount, meta) {
    implicit val serialization = Serialization
    implicit val formats = DefaultFormats

    private val memoizedTextBody = BodyBuilder.buildText(meta.bodyMultiple)
    private val memoizedHtmlBody = BodyBuilder.buildHtml(meta.bodyMultiple)

    val httpBackend = AsyncHttpClientFutureBackend()
    val uri: Uri = uri"https://api.mailgun.net/v3/campaignmail.dev.pxslab.com/messages"
    val requestTemplate = basicRequest.post(uri).contentType("multipart/form-data").response(asJson[Response])

    override def makeSendWork: List[RestApiWork] = (1 to count).toList.map { i => RestApiWork(makeRecipientAddressString(i))}

    override def makeFlow(): Flow[RestApiWork, Either[String, Unit], NotUsed] = {
        Flow[RestApiWork].map { w =>
            val request = requestTemplate
                .auth.basic("api", meta.apiKey)
                .header("from", s"<${meta.fromAddress}>")
                .multipartBody(
                    multipart("to", w.to),
                    multipart("subject", meta.subject),
                    multipart("text", memoizedTextBody),
                    multipart("html", memoizedHtmlBody),
                    multipart("from", meta.fromAddress),
                    multipart("o:testmode", "yes")
                )
            val response = Await.result(request.send(httpBackend), Duration(60, SECONDS))
            val logString = response.body match {
                case Left(re) => s"Failure: $re"
                case Right(re) => s"Success: $re"
            }
            Right(println(s"""Sending ${w.to} via REST API ($logString)"""))
        }.recover {
            case e: Exception => {
                e.printStackTrace()
                Left(s"Failed with: ${e.getMessage}")
            }
        }
    }

    override def takeDown(): Unit = {
        httpBackend.close()
        println("Closed HTTP connection")
    }
}

class SmtpStream(
    count: Int,
    threadCount: Int,
    meta: SMTPMeta
)(implicit actorSystem: ActorSystem) extends Stream[SmtpWork](count, threadCount, meta) {
    val fromInetAddress = new InternetAddress(meta.fromAddress)
    val properties = new Properties()
    properties.put("mail.smtp.connectiontimeout", "300000")
    properties.put("mail.smtp.timeout", "60000")
    properties.put("mail.smtp.quitwait", "false")
    properties.put("mail.smtp.auth", "true")
    properties.put("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
    properties.put("mail.smtp.host", meta.host)
    properties.put("mail.smtp.port", meta.port.toString)

    val authenticator = new Authenticator { override def getPasswordAuthentication = new PasswordAuthentication(meta.username, meta.password) }
    val session = Session.getInstance(properties, authenticator)

    val transportLocal: ThreadLocal[Transport] = new ThreadLocal[Transport] {
        override protected def initialValue(): Transport = session.getTransport("smtp")
    }

    val mc: MailcapCommandMap = CommandMap.getDefaultCommandMap.asInstanceOf[MailcapCommandMap]
    mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
    mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
    mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
    mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
    mc.addMailcap("message/rfc822;; x-java-content- handler=com.sun.mail.handlers.message_rfc822")

    val textBody = new MimeBodyPart
    textBody.setText(BodyBuilder.buildText(20), "utf-8")
    textBody.setHeader("Content-Transfer-Encoding", "quoted-printable")

    val htmlBody = new MimeBodyPart
    htmlBody.setContent(BodyBuilder.buildHtml(20), "text/html; charset=utf-8")
    htmlBody.setHeader("Content-Transfer-Encoding", "quoted-printable")

    val mp = new MimeMultipart("alternative")
    mp.addBodyPart(textBody)
    mp.addBodyPart(htmlBody)

    override def makeSendWork = (1 to count).toList.map { i =>
        val toInetAddress = new InternetAddress(makeRecipientAddressString(i), true)
        val recipients = Array(toInetAddress).map(_.asInstanceOf[Address])
        val message = new MimeMessage(session)
        message.setFrom(fromInetAddress)
        message.setSubject(meta.subject)
        message.setContent(mp)
        message.setRecipients(Message.RecipientType.TO, recipients)
        message.setHeader("X-Mailgun-Drop-Message", "yes")
        meta.headers.foreach(kv => message.setHeader(kv._1, kv._2))
        SmtpWork(message)
    }

    override def makeFlow(): Flow[SmtpWork, Either[String, Unit], NotUsed] = {
        Flow[SmtpWork].map { w =>
            val transport = transportLocal.get()
            if (!transport.isConnected) { transport.connect() }
            transport.sendMessage(w.message, w.message.getRecipients(Message.RecipientType.TO))
            Right(println(s"""Sending to ${w.message.getRecipients(Message.RecipientType.TO).toList.map(_.asInstanceOf[InternetAddress].getAddress).mkString(",")} via SMTP"""))
        }.recover {
            case e: Exception => {
                e.printStackTrace()
                Left(s"Failed with: ${e.getMessage}")
            }
        }
    }

    override def takeDown(): Unit = {
        val transport = transportLocal.get()
        if (transport.isConnected) {
            Try (transport.close()) match {
                case Success(_) => println("Closed SMTP connection")
                case Failure(e) => println(s"Exception while closing SMTP connection: ${e.getMessage()}")
            }
        }
    }
}

object BodyBuilder {
    private val loremIpsum =
        """Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
          |Magna ac placerat vestibulum lectus mauris ultrices eros in cursus.
          |Tincidunt eget nullam non nisi est sit amet.
          |Pulvinar pellentesque habitant morbi tristique senectus et netus et malesuada.
          |Massa placerat duis ultricies lacus.
          |Et magnis dis parturient montes nascetur ridiculus mus mauris vitae.
          |Tempus quam pellentesque nec nam aliquam sem et tortor.
          |Metus vulputate eu scelerisque felis imperdiet.
          |Aliquet bibendum enim facilisis gravida.
          |Tristique et egestas quis ipsum suspendisse ultrices gravida dictum fusce.
          |Feugiat nisl pretium fusce id velit ut tortor pretium viverra.
          |In arcu cursus euismod quis viverra nibh cras pulvinar mattis.
          |Duis ultricies lacus sed turpis tincidunt id aliquet.
          |Dui id ornare arcu odio ut sem nulla pharetra.
          |Nisi quis eleifend quam adipiscing vitae.""".stripMargin
    def buildText(lineCount: Int): String = (1 to lineCount).toList.map(_ => loremIpsum).mkString("\n")
    def buildHtml(rowCount: Int): String = {
        val rows = (1 to rowCount).toList.map { _ =>
             s"""|<tr>
                 | <td style="padding:15px;" class="em_padd" valign="top" bgcolor="#efefef" align="center"><table width="100%" cellspacing="0" cellpadding="0" border="0" align="center">
                 |  <tbody>
                 |   <tr>
                 |    <td style="font-family:'Open Sans', Arial, sans-serif; font-size:12px; line-height:15px; color:#0d1121;" valign="top" align="center">
                 |    ${loremIpsum}
                 |    </td>
                 |   </tr>
                 |  </tbody>
                 | </td>
                 |</tr>""".stripMargin
        }
        s"""|<html xmlns="https://www.w3.org/1999/xhtml">
            |<head>
            |<title>Test Email Sample</title>
            |<meta http–equiv="Content-Type" content="text/html; charset=UTF-8" />
            |<meta http–equiv="X-UA-Compatible" content="IE=edge" />
            |<meta name="viewport" content="width=device-width, initial-scale=1.0 " />
            |<style></style>
            |</head>
            |<body class="em_body" style="margin:0px; padding:0px;" bgcolor="#efefef">
            | <table align="center" width="700" border="0" cellspacing="0" cellpadding="0" class="em_main_table" style="width:700px;">
            |  ${rows.mkString("\n")}
            | </table>
            |</body>
            |</html>""".stripMargin
    }
}