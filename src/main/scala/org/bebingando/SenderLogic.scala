package org.bebingando.emailthroughput

import java.util.Properties
import javax.mail.internet.{InternetAddress, MimeMessage}
import javax.mail.{Address, Authenticator, Message, PasswordAuthentication, Session, Transport}
import org.bebingando.emailthroughput.conf.{RestAPIMeta, SMTPMeta}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, SECONDS}
import scala.util.{Failure, Success, Try}
import sttp.client3.{asStringAlways, basicRequest, multipart, UriContext}
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.client3.json4s._//{asJson, json4sBodySerializer}
import sttp.model.Uri

case class SentInfo(address: String, times: Times)
trait SenderLogic {
    def send(to: String): SentInfo
    def takedown(): Unit
    def initialConnectTime: Long
}
class SMTPSender(smtp: SMTPMeta) extends SenderLogic {
    val fromInetAddress = new InternetAddress(smtp.fromAddress)

    val properties = new Properties()
    properties.put("mail.smtp.connectiontimeout", "300000")
    properties.put("mail.smtp.timeout", "60000")
    properties.put("mail.smtp.quitwait", "false")
    //    properties.put("mail.smtp.starttls.enable", "true")
    //    properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
    properties.put("mail.smtp.auth", "true")
    properties.put("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
    properties.put("mail.smtp.host", smtp.host)
    properties.put("mail.smtp.port", smtp.port.toString)

    val authenticator = new Authenticator { override def getPasswordAuthentication = new PasswordAuthentication(smtp.username, smtp.password) }
    val session = Session.getInstance(properties, authenticator)

    val transport = session.getTransport("smtp")
    override val initialConnectTime = connectAndCaptureTiming(transport)

    override def send(to: String): SentInfo = {
        val toInetAddress = new InternetAddress(to, true)

        val recipients = Array(toInetAddress).map(_.asInstanceOf[Address])

        val message = new MimeMessage(session)
        message.setFrom(fromInetAddress)
        message.setSubject(smtp.subject)
        message.setText(smtp.body)
        message.setRecipients(Message.RecipientType.TO, recipients)
        message.setHeader("X-Mailgun-Drop-Message", "yes")
        smtp.headers.foreach(kv => message.setHeader(kv._1, kv._2))
        val connectTime = connectAndCaptureTiming(transport)
        val beforeSend = System.currentTimeMillis
        transport.sendMessage(message, recipients)
        val sendTime = System.currentTimeMillis - beforeSend
        SentInfo(toInetAddress.toString, Times(sendTime, connectTime))
    }

    override def takedown(): Unit = {
        // only close it if it is open
        if (transport.isConnected()) {
            Try (transport.close()) match {
                case Success(_) => println("Closed SMTP connection")
                case Failure(e) => println(s"Exception while closing connection: ${e.getMessage()}")
            }
        }
    }

    def connectAndCaptureTiming(t: Transport): Long = {
        // Only open it if it isn't already
        if (!t.isConnected()) {
            val beforeConnect = System.currentTimeMillis
            t.connect()
            System.currentTimeMillis - beforeConnect
        } else 0L
    }
}

class RestAPISender(api: RestAPIMeta) extends SenderLogic {
//    curl -s --user 'api:YOUR_API_KEY' \
//    https://api.mailgun.net/v3/YOUR_DOMAIN_NAME/messages \
//        -F from='Excited User <mailgun@YOUR_DOMAIN_NAME>' \
//        -F to=YOU@YOUR_DOMAIN_NAME \
//        -F to=bar@example.com \
//        -F subject='Hello' \
//    -F text='Testing some Mailgun awesomeness!'
    implicit val serialization = Serialization
    implicit val formats = DefaultFormats

    val httpBackend = AsyncHttpClientFutureBackend()
    val uri: Uri = uri"https://api.mailgun.net/v3/campaignmail.dev.pxslab.com/messages"
    val requestTemplate = basicRequest.post(uri).contentType("multipart/form-data").response(asJson[Response])


    override def send(to: String): SentInfo = {
        val payload = RequestPayload(to, api.subject, api.body, "yes")
        val beforeSend = System.currentTimeMillis
        val request = requestTemplate
            .auth.basic("api", api.apiKey)
            .header("from", s"<${api.fromAddress}>")
            .multipartBody(
                multipart("to", to),
                multipart("subject", api.subject),
                multipart("text", api.body),
                multipart("from", api.fromAddress),
                multipart("o:testmode", "yes")
            )
        val response = Await.result(request.send(httpBackend), Duration(60, SECONDS))
        val sendTime = System.currentTimeMillis - beforeSend
        val b = response.body match {
            case Left(re) =>
                println(s"Failure: $re")
                re.printStackTrace()
            case Right(r) => println(s"Success: $r")
        }
        SentInfo(to, Times(sendTime, 0))
    }
    override def takedown(): Unit = {
        httpBackend.close()
        println("Closed HTTP connection")
    }
    override def initialConnectTime: Long = 0
}

case class RequestPayload(to: String, subject: String, text: String, `o:testmode`: String)
case class Response(message: String, id: String)