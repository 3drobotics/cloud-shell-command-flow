package io.dronekit.cloud

import java.io.{IOException, InputStream, OutputStream}
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.sys.process.{Process, ProcessIO}

/**
 * Created by Jason Martens <jason.martens@3dr.com> on 10/15/15.
 *
 */
object ShellCommandFlow {
  def apply(command: Seq[String]): Flow[ByteString, String, Unit] = {
    Flow.fromGraph(new ShellCommandFlow(command))
  }

  class ShellCommandFlowException(msg: String) extends RuntimeException(msg)


  private class ShellCommandFlow(command: Seq[String], bufferSize: Int = 10) extends GraphStage[FlowShape[ByteString, String]] {
    val in: Inlet[ByteString] = Inlet("CommandInput")
    val out: Outlet[String] = Outlet("CommandOutput")
    override val shape: FlowShape[ByteString, String] = FlowShape(in, out)

    sealed trait Output
    case object StandardOutput extends Output
    case class ErrorOutput(out: String) extends Output
    case object StandardOutputFinished extends Output


    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        val log: Logger = Logger(LoggerFactory.getLogger(getClass))
        var upstreamFinished = new AtomicBoolean(false)
        var inputClosed = new AtomicBoolean(false)
        var outputClosed = new AtomicBoolean(false)
        var errorAbort = new AtomicBoolean(false)
        val process = Process(command)
        val inputQueue = new LinkedBlockingDeque[ByteString]()
        val outputQueue = new LinkedBlockingDeque[String]()
        val errorQueue = new LinkedBlockingDeque[String]()
        val callback = getAsyncCallback(outputAsyncInput)

        override def preStart(): Unit = {
          val io = new ProcessIO(processInput, processOutput, processErrorOutput)
          // TODO: create a materialized value with the exit code
          process.run(io)
        }

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

        private def pushAndComplete(): Unit = {
          if (outputQueue.isEmpty) {
            log.info("Completing stage")
            completeStage()
          }
          else {
            if (isAvailable(out)) {
              val finalPush = outputQueue.descendingIterator().mkString("\n")
              log.info(s"Pushing final value and completing")
              push(out, finalPush)
              completeStage()
            }
            else {
              // Wait for onPull
              log.info("waiting for onPull to complete stage")
            }
          }
        }

        private def outputAsyncInput(data: Output): Unit = data match {
          case StandardOutput =>
            pushAndPull()
          case ErrorOutput(msg) =>
            log.error(msg)
            errorQueue.putFirst(msg)
          case StandardOutputFinished =>
            log.debug("StandardOutputFinished")
            outputClosed.set(true)
            pushAndComplete()

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
            if (outputClosed.get) {
              pushAndComplete()
            }
            else
              pushAndPull()
          }
        })



        /**
          * Executed in a separate thread, this method is responsible for
          * writing the data from the Akka Stream onPush to the process's
          * InputStream (which is an OutputStream for us)
          * @param in OutputStream to write to, which is Input for the process
          */
        private def processInput(in: OutputStream): Unit = {
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
        private def processOutput(out: InputStream): Unit = {
          while (!inputClosed.get && !errorAbort.get) {
            scala.io.Source.fromInputStream(out)
              .getLines()
              .foreach { line =>
                outputQueue.putFirst(line)
                callback.invoke(StandardOutput)
              }
            if (out.available() == 0)
              Thread.`yield`()
          }
          // Finish up anything remaining in the stream
          log.info("input closed, reading the rest of the output")
          while (out.available() > 0 && !errorAbort.get) {
            scala.io.Source.fromInputStream(out)
              .getLines()
              .foreach { line =>
                outputQueue.putFirst(line)
                callback.invoke(StandardOutput)
              }
          }
          try {
            out.close()
          } catch {
            case e: IOException => log.warn("Ignoring IO Exception closing stream")
          }
          log.info(s"output stream for $command is complete")
          callback.invoke(StandardOutputFinished)
        }

        /**
          * Executed in a separate thread, this method reads the stderr output from
          * the process
          * @param err An InputStream which is attached to the process's stderr output
          */
        private def processErrorOutput(err: InputStream): Unit = {
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
