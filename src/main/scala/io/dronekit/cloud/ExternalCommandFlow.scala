package io.dronekit.cloud

import java.io.{IOException, InputStream, OutputStream}
import java.util.concurrent.LinkedBlockingDeque

import akka.event.LoggingAdapter
import akka.stream.scaladsl.Flow
import akka.stream.stage.{Context, PushPullStage, SyncDirective, TerminationDirective}
import akka.util.ByteString

import scala.collection.JavaConversions._
import scala.sys.process.{Process, ProcessIO}

/**
 * Created by Jason Martens <jason.martens@3dr.com> on 10/15/15.
 *
 */
object ExternalCommandFlow {
  def apply(command: Seq[String])(implicit adapter: LoggingAdapter): Flow[ByteString, String, Any] = {
   Flow() {implicit b =>
     val flow = b.add(
       Flow[ByteString]
         .transform(() => new PipeThroughCommand(command))
         .filter(elem => elem != "")
     )
     (flow.inlet, flow.outlet)
   }
  }


  private class PipeThroughCommand(command: Seq[String])(implicit adapter: LoggingAdapter) extends PushPullStage[ByteString, String] {
    var upstreamFinished = false
    var inputClosed = false
    var outputClosed = false
    var errorAbort = false
    val process = Process(command)
    val inputQueue = new LinkedBlockingDeque[ByteString]()
    val outputQueue = new LinkedBlockingDeque[String]()
    val errorQueue = new LinkedBlockingDeque[String]()

    /**
     * Executed in a separate thread, this method is responsible for
     * writing the data from the Akka Stream onPush to the process's
     * InputStream (which is an OutputStream for us)
     * @param in OutputStream to write to, which is Input for the process
     */
    def processInput(in: OutputStream): Unit = {
      while (!upstreamFinished && !errorAbort) {
        if (inputQueue.isEmpty)
          Thread.`yield`()
        else {
          in.write(inputQueue.takeLast().toArray[Byte])
        }
      }
      // Make sure we wrote everything to the input stream
      try {

        while (!inputQueue.isEmpty && !errorAbort) {
          in.write(inputQueue.takeLast().toArray[Byte])
        }
        in.close()
      } catch {
        case ex: IOException =>
          if (ex.getMessage == "Stream closed")
            adapter.error(s"Ignoring $ex in processInput while closing")
          else
            throw ex
      }
      inputClosed = true
    }

    /**
     * Executed in a separate thread, this method is responsible for reading
     * the process's output, and writing the results to the outputQueue, which
     * is then read by onPull and pushed downstream.
     * @param out An InputStream which is connected to the process's stdout
     */
    def processOutput(out: InputStream): Unit = {
      while (!inputClosed && !errorAbort) {
        scala.io.Source.fromInputStream(out)
          .getLines()
          .foreach{line => outputQueue.putFirst(line)}
        if (out.available() == 0)
          Thread.`yield`()
      }
      // Finish up anything remaining in the stream
      while (out.available() > 0 && !errorAbort) {
        scala.io.Source.fromInputStream(out)
          .getLines()
          .foreach{line => outputQueue.putFirst(line)}
      }
      out.close()
      outputClosed = true
    }

    /**
     * Executed in a separate thread, this method reads the stderr output from
     * the process
     * @param err An InputStream which is attached to the process's stderr output
     */
    def processErrorOutput(err: InputStream): Unit = {
      while (!inputClosed || !outputClosed || errorAbort) {
        scala.io.Source.fromInputStream(err)
          .getLines()
          .foreach{msg => println(s"caught error: $msg"); errorQueue.putFirst(msg)}
      }
      err.close()
    }

    val io = new ProcessIO(processInput, processOutput, processErrorOutput)
    // TODO: create a materialized value with the exit code
    val runningProcess = process.run(io)

    override def onPush(elem: ByteString, ctx: Context[String]): SyncDirective = {
      if (!errorQueue.isEmpty) {
        // What is the right thing to do if there is stderr output?
        adapter.error(s"""Got stderr output from $command: ${errorQueue.iterator().mkString("\n")}""")
        errorQueue.clear()
//        errorAbort = true
//        ctx.fail(new RuntimeException(errorQueue.iterator().mkString))
      }

      inputQueue.putFirst(elem)
      if (outputQueue.isEmpty)
        ctx.pull()
      else
        ctx.push(outputQueue.takeLast())
    }

    override def onPull(ctx: Context[String]): SyncDirective = {
      if (!errorQueue.isEmpty) {
//        errorAbort = true
//        ctx.fail(new RuntimeException(errorQueue.iterator().mkString))
      }

      if (ctx.isFinishing) {
        // Upstream is finished, need to emit all of the piped output
        if (inputClosed && outputClosed) {
          val finalPush = outputQueue.descendingIterator().mkString("\n")
          ctx.pushAndFinish(finalPush)
        }
        else if (outputQueue.isEmpty) {
          ctx.push("")
        }
        else {
          ctx.push(outputQueue.takeLast())
        }
      } else {
        if (outputQueue.isEmpty) ctx.pull()
        else ctx.push(outputQueue.takeLast())
      }
    }

    override def onUpstreamFinish(ctx: Context[String]): TerminationDirective = {
      upstreamFinished = true
      ctx.absorbTermination()
    }
  }
}
