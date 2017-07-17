// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iot.iothubreact.sinks

import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

import akka.Done
import akka.actor.ActorRef
import akka.japi.function.Procedure
import akka.stream.javadsl.{Sink ⇒ JavaSink}
import akka.stream.scaladsl.{Sink ⇒ ScalaSink}
import com.microsoft.azure.iot.iothubreact.checkpointing.CheckpointService.UpdateOffset
import com.microsoft.azure.iot.iothubreact.checkpointing.{CheckpointActorSystem, IOffsetLoader, OffsetLoader}
import com.microsoft.azure.iot.iothubreact.checkpointing.backends.CheckpointBackend
import com.microsoft.azure.iot.iothubreact.config.{Configuration, IConfiguration}
import com.microsoft.azure.iot.iothubreact.{Logger, MessageFromDevice}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

final case class OffsetSaveSink(parallelism: Int, config: IConfiguration, offsetLoader: IOffsetLoader) extends ISink[MessageFromDevice] with Logger {

  lazy val checkpointService = (0 until config.connect.iotHubPartitions).map { p ⇒
    p → CheckpointActorSystem(config.checkpointing).getCheckpointService(p)
  }(collection.breakOut): Map[Int, ActorRef]

  val current: TrieMap[Int, Long] = TrieMap()
  offsetLoader.GetSavedOffsets.foreach{ case (a, c) ⇒
    current += a → c.toLong
  }

  private[this] object JavaSinkProcedure extends Procedure[MessageFromDevice] {
    @scala.throws[Exception](classOf[Exception])
    override def apply(m: MessageFromDevice): Unit = {
      doWrite(m)
    }
  }

  def scalaSink(): ScalaSink[MessageFromDevice, scala.concurrent.Future[Done]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    ScalaSink.foreachParallel[MessageFromDevice](parallelism) { doWrite }
  }

  private def doWrite(m: MessageFromDevice) = {
    m.runtimeInfo.partitionInfo.partitionNumber.map { p =>
        synchronized {
          val os: Long = m.offset.toLong
          val cur: Long = current.getOrElse(p, -1)
          if (os > cur) {
            log.debug(s"Committing offset ${m.offset} on partition ${p}")
            checkpointService(p) ! UpdateOffset(m.offset)
            current += p → os
          } else {
              log.debug(s"Ignoring offset ${m.offset} since it precedes ${cur}")
              Future successful (Done)
          }
        }
    }
  }

  def javaSink(): JavaSink[MessageFromDevice, CompletionStage[Done]] = {
    JavaSink.foreach[MessageFromDevice] {
      JavaSinkProcedure
    }
  }
}
