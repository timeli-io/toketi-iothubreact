// Copyright (c) Microsoft. All rights reserved.

// TODO: Implement once SDK is ready

package com.microsoft.azure.iot.iothubreact.sinks

import java.util.concurrent.CompletionStage

import akka.Done
import akka.japi.function.Procedure
import akka.stream.javadsl.{Sink => JavaSink}
import akka.stream.scaladsl.{Sink => ScalaSink}
import com.microsoft.azure.iot.iothubreact.checkpointing.backends.CheckpointBackend
import com.microsoft.azure.iot.iothubreact.{Logger, MessageFromDevice}

case class OffsetCommitSink(parallelism: Int, backend: CheckpointBackend) extends ISink[MessageFromDevice] with Logger {

  private[this] object JavaSinkProcedure extends Procedure[MessageFromDevice] {
    @scala.throws[Exception](classOf[Exception])
    override def apply(m: MessageFromDevice): Unit = {
      m.partition.map { p =>
        log.info(s"Committing offset ${m.offset} on partition ${p}")
        backend.writeOffset(m.partition.get, m.offset)
      }
    }
  }

  def scalaSink(): ScalaSink[MessageFromDevice, scala.concurrent.Future[Done]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    ScalaSink.foreachParallel[MessageFromDevice](parallelism) { m =>
      m.partition.map { p =>
        log.info(s"Committing offset ${m.offset} on partition ${p}")
        backend.writeOffset(p, m.offset)
      }
    }
  }

  def javaSink(): JavaSink[MessageFromDevice, CompletionStage[Done]] = {
    JavaSink.foreach[MessageFromDevice] {
      JavaSinkProcedure
    }
  }
}