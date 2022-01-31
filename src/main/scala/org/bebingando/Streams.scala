package org.bebingando.emailthroughput

import akka.stream.{ActorMaterializer, FlowShape}
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Source}
import akka.NotUsed
import akka.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.bebingando.emailthroughput.conf.{EmailMeta, RestAPIMeta, SMTPMeta}

case class SendWork(to: String)

abstract class Stream(
    count: Int,
    threadCount: Int,
    meta: EmailMeta
)(implicit actorSystem: ActorSystem) {
    implicit val mat = ActorMaterializer()
    private def setup = {
        val toSend = (1 to count).map(i => SendWork(s"${meta.toLocalBase}$i@${meta.toDomain}"))
        val source = Source(toSend)
        val streamSource = source.via(assembleFlows(makeFlow, threadCount))
        val sink = makeSink
        streamSource.runWith(sink)
    }

    def execute = {
        val sent = Await.result(setup, Duration.Inf)
        println(s"Sent $sent")
    }

    protected def makeFlow(): Flow[SendWork, Either[String, Unit], NotUsed]

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
)(implicit actorSystem: ActorSystem) extends Stream(count, threadCount, meta) {
    // SBDEV: fix the logic to send real email
    override def makeFlow(): Flow[SendWork, Either[String, Unit], NotUsed] = {
        Flow[SendWork].map { w =>
            Right(println(s""""Sending" ${meta.toLocalBase}$w@${meta.toDomain} via REST API"""))
        }.recover {
            case e: Exception => {
                e.printStackTrace()
                Left(s"Failed with: ${e.getMessage}")
            }
        }
    }
}

class SmtpStream(
    count: Int,
    threadCount: Int,
    meta: SMTPMeta
)(implicit actorSystem: ActorSystem) extends Stream(count, threadCount, meta) {
    // SBDEV: fix the logic to send real email
    override def makeFlow(): Flow[SendWork, Either[String, Unit], NotUsed] = {
        Flow[SendWork].map { w =>
            Right(println(s""""Sending" ${meta.toLocalBase}$w@${meta.toDomain} via SMTP"""))
        }.recover {
            case e: Exception => {
                e.printStackTrace()
                Left(s"Failed with: ${e.getMessage}")
            }
        }
    }
}