import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.dronekit.cloud.ExternalCommandFlow
import org.scalatest._
import java.io.{IOException, File}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Created by Jason Martens <jason.martens@3dr.com> on 10/19/15.
 *
 */
class CommandPipeSpec extends FlatSpec with Matchers {
  implicit val system = ActorSystem()
  implicit val log: LoggingAdapter = system.log
  implicit val materializer = ActorMaterializer()

  it should "echo data piped through /bin/cat" in {
    val source = Source(1 to 10).map(num => ByteString(s"$num "))
    val stream = source.via(ExternalCommandFlow(Seq("/bin/cat"))).grouped(1000000).runWith(Sink.head)
    val result = Await.result(stream, 1 seconds)
    result.head shouldBe (1 to 10).mkString(" ") + " "
  }

  it should "throw a NoSuchElementException if the source is empty" in {
    val source = Source.empty[ByteString]
    val stream = source.via(ExternalCommandFlow(Seq("/bin/cat"))).grouped(1000000).runWith(Sink.head)
    intercept[NoSuchElementException] {
    val result = Await.result(stream, 1 seconds)
    }
  }
  val falseCommand =
    if (new File("/usr/bin/false").exists) "/usr/bin/false"
    else if (new File("/bin/false").exists) "/bin/false"
    else "false"

  it should "allow giving arguments to binaries" in {
    val inputString =
      """first,last,email
        |jason,martens,jason.martens@3dr.com""".stripMargin
    val outputString =
      """last
        |martens""".stripMargin
    val stream = Source.single(inputString).map(ByteString(_))
      .via(ExternalCommandFlow(Seq("/usr/bin/cut", "-d", ",", "-f", "2")))
      .grouped(1000000)
      .runWith(Sink.head)
    val result = Await.result(stream, 1 seconds)
    result.mkString("\n") shouldBe outputString
  }

  // This test is not the best... but does check some error handling
  it should "close java streams on error from the command" in {
    val f = Source.single(ByteString("hello"))
      .via(ExternalCommandFlow(Seq(falseCommand)))
      .runWith(Sink.ignore)
    Await.result(f, 1 seconds)
  }

}
