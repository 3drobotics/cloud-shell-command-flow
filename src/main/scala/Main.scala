import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import io.dronekit.cloud.ShellCommandFlow
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Created by Jason Martens on 10/2/15. Sample application using ExternalCommandFlow
 *
 */

object Main extends App {
  implicit val system = ActorSystem()
  implicit val adapter = system.log
  implicit val materializer = ActorMaterializer()
  implicit val log = Logger(LoggerFactory.getLogger("io.dkc.cp"))

  val source = Source(1 to 10000).map(num => ByteString(s"$num "))
  val stream = source.via(ShellCommandFlow(Seq("/bin/cat"))).grouped(1000000).runWith(Sink.head)
  val result = Await.result(stream, 30 seconds)
  println(s"result: r$result")
  system.shutdown()

}
