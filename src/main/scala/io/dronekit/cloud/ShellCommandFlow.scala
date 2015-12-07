package io.dronekit.cloud

import java.io.{IOException, InputStream, OutputStream}
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.util.ByteString
import com.typesafe.scalalogging.Logger

import scala.collection.JavaConversions._
import scala.sys.process.{Process, ProcessIO}

/**
 * Created by Jason Martens <jason.martens@3dr.com> on 10/15/15.
 *
 */
object ShellCommandFlow {
  def apply(command: Seq[String])(implicit log: Logger): Flow[ByteString, String, Unit] = {
    Flow.fromGraph(new ShellCommandFlow(command))
  }

  class ShellCommandFlowException(msg: String) extends RuntimeException(msg)


  private class ShellCommandFlow(command: Seq[String], bufferSize: Int = 10)
                                (implicit log: Logger) extends GraphStage[FlowShape[ByteString, String]] {
    val in: Inlet[ByteString] = Inlet("CommandInput")
    val out: Outlet[String] = Outlet("CommandOutput")
    override val shape: FlowShape[ByteString, String] = FlowShape(in, out)

    sealed trait Output
    case class StandardOutput(out: String) extends Output
    case class ErrorOutput(out: String) extends Output
    case object StandardOutputFinished extends Output


    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        var upstreamFinished = new AtomicBoolean(false)
        var inputClosed = new AtomicBoolean(false)
        var outputClosed = new AtomicBoolean(false)
        var errorAbort = new AtomicBoolean(false)
        val process = Process(command)
        val inputQueue = new LinkedBlockingDeque[ByteString]()
        val outputQueue = new LinkedBlockingDeque[String]()
        val errorQueue = new LinkedBlockingDeque[String]()
        val callback = getAsyncCallback(outputAsyncInput)

        val io = try {
          new ProcessIO(processInput, processOutput, processErrorOutput)
        } catch {
          case e: IOException => throw new ShellCommandFlowException(e.toString)
        }
        // TODO: create a materialized value with the exit code
        val runningProcess = process.run(io)

        private def pushAndPull(): Unit = {
          if (outputQueue.isEmpty && !hasBeenPulled(in) && !isClosed(in)) {
            log.debug("pulling")
            pull(in)
          }
          while (!outputQueue.isEmpty && !isClosed(out) && isAvailable(out)) {
            val output = outputQueue.takeLast()
            log.debug(s"pushing $output")
            push(out, output)
          }
        }

        private def outputAsyncInput(data: Output): Unit = data match {
          case StandardOutput(msg) =>
            outputQueue.putFirst(msg)
            pushAndPull()
          case ErrorOutput(msg) =>
            log.error(msg)
            errorQueue.putFirst(msg)
          case StandardOutputFinished =>
            while (!outputQueue.isEmpty && !isClosed(out)) {
              if (isAvailable(out)) {
                val finalPush = outputQueue.descendingIterator().mkString("\n")
                log.debug(s"Pushing final value: $finalPush")
                push(out, finalPush)
              }
              else
                Thread.`yield`()
            }
            log.debug("completeStage")
            completeStage()
        }

        setHandler(in, new InHandler{
          override def onPush: Unit = {
            val elem = grab(in)
            if (!errorQueue.isEmpty) {
              // What is the right thing to do if there is stderr output?
              errorQueue.clear()
            }

            inputQueue.putFirst(elem)
            pushAndPull()
          }

          override def onUpstreamFinish: Unit = {
            upstreamFinished.set(true)
            // This un-blocks processInput allowing it to exit
            log.debug("onUpstreamFinish")
            inputQueue.putFirst(ByteString())
          }

        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            log.debug("onPull")
            pushAndPull()
          }
        })



        /**
          * Executed in a separate thread, this method is responsible for
          * writing the data from the Akka Stream onPush to the process's
          * InputStream (which is an OutputStream for us)
          * @param in OutputStream to write to, which is Input for the process
          */
        def processInput(in: OutputStream): Unit = {
          while (!upstreamFinished.get && !errorAbort.get) {
            // Block until there is data available
            val data = inputQueue.takeLast().toArray[Byte]
            in.write(data)
          }
          // Make sure we wrote everything to the input stream
          while (!inputQueue.isEmpty && !errorAbort.get) {
            in.write(inputQueue.takeLast().toArray[Byte])
          }
          try {
            in.close()
          } catch {
            case ex: IOException if ex.getMessage == "Stream closed" =>
              log.error(s"Ignoring $ex in processInput while closing")
            case ex: IOException if ex.getMessage == "Broken pipe" =>
              log.error(s"Ignoring $ex in processInput while closing")
          }
          inputClosed.set(true)
        }

        /**
          * Executed in a separate thread, this method is responsible for reading
          * the process's output, and writing the results to the outputQueue, which
          * is then read by onPull and pushed downstream.
          * @param out An InputStream which is connected to the process's stdout
          */
        def processOutput(out: InputStream): Unit = {
          while (!inputClosed.get && !errorAbort.get) {
            scala.io.Source.fromInputStream(out)
              .getLines()
              .foreach{line => callback.invoke(StandardOutput(line))}
            if (out.available() == 0)
              Thread.`yield`()
          }
          // Finish up anything remaining in the stream
          while (out.available() > 0 && !errorAbort.get) {
            scala.io.Source.fromInputStream(out)
              .getLines()
              .foreach{line => callback.invoke(StandardOutput(line))}
          }
          try {
            out.close()
          } catch {
            case e: IOException => log.warn("Ignoring IO Exception closing stream")
          }
          callback.invoke(StandardOutputFinished)
        }

        /**
          * Executed in a separate thread, this method reads the stderr output from
          * the process
          * @param err An InputStream which is attached to the process's stderr output
          */
        def processErrorOutput(err: InputStream): Unit = {
          while (!inputClosed.get || !outputClosed.get || errorAbort.get) {
            scala.io.Source.fromInputStream(err)
              .getLines()
              .foreach{msg => callback.invoke(ErrorOutput(msg))}
          }
          err.close()
        }
      }
  }
}
