// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iot.iothubreact.checkpointing

import java.time.Instant
import java.util.concurrent.Executors

import akka.actor.{Actor, Stash}
import com.microsoft.azure.iot.iothubreact.Logger
import com.microsoft.azure.iot.iothubreact.config.IConfiguration
import com.microsoft.azure.iot.iothubreact.checkpointing.CheckpointService.{ReadCheckpoint, CheckpointToStorage, CheckpointInMemory}
import com.microsoft.azure.iot.iothubreact.checkpointing.backends.{AzureBlob, CassandraTable, CosmosDbSql, CheckpointBackend}

import com.microsoft.azure.iot.iothubreact.scaladsl.IoTHubPartition

import scala.concurrent.ExecutionContext

trait ICheckpointServiceLocator {

  def getCheckpointBackend(implicit config: ICPConfiguration): CheckpointBackend

}

private[iothubreact] object CheckpointService extends ICheckpointServiceLocator {

  // Command used to read the current partition position
  case object ReadCheckpoint

  // Command used to update the position stored in memory
  case class CheckpointInMemory(value: String)

  // Command use to write the position from memory to storage
  case object CheckpointToStorage

  // TODO: Support plugins
  def getCheckpointBackend(implicit config: ICPConfiguration) = config.checkpointBackendType.toUpperCase match {
    case "AZUREBLOB"   ⇒ new AzureBlob(config)
    case "CASSANDRA"   ⇒ new CassandraTable(config)
    case "COSMOSDBSQL" ⇒ new CosmosDbSql(config)
    case x             ⇒ throw new UnsupportedOperationException(s"Unknown storage type ${x}")
  }

}

/** Checkpointing agent. Takes care of initializing the right storage, reading and writing to it.
  * Each agent instance work on a single IoT hub partition
  *
  * @param partition IoT hub partition number [0..N]
  */
private[iothubreact] class CheckpointService(config: IConfiguration, partition: Int, locator: ICheckpointServiceLocator)
  extends Actor
    with Stash
    with Logger {

  type OffsetsData = Tuple3[String, Long, Long]

  log.debug("New instance of CheckpointService [partition {}]", partition)

  implicit val executionContext = ExecutionContext
    .fromExecutorService(Executors.newFixedThreadPool(sys.runtime.availableProcessors))

  // Contains the offsets up to one hour ago, max 1 offset per second (max size = 3600)
  private[this] val queue                     = new scala.collection.mutable.Queue[OffsetsData]
  // Count the offsets tracked in the queue (!= queue.size)
  private[this] var queuedOffsets   : Long    = 0
  private[checkpointing] var currentOffset   : String  = IoTHubPartition.OffsetStartOfStream
  private[this] val storage                   = getCheckpointBackend
  private[this] var schedulerStarted: Boolean = false

  override def receive: Receive = notReady

  // At the beginning the actor can only read, stashing other commands for later
  def notReady: Receive = {
    case _ ⇒ {
      try {
        context.become(busyReading)
        stash()
        log.debug("Retrieving partition {} offset from the storage", partition)
        val offset = storage.readOffset(config.connect.hashedNamespace, partition)
        if (offset != IoTHubPartition.OffsetCheckpointNotFound) {
          currentOffset = offset
        }
        log.debug("Offset retrieved for partition {}: {}", partition, currentOffset)
        context.become(ready)
        queuedOffsets = 0
      }
      catch {
        case e: Exception ⇒
          log.error(e, e.getMessage)
          context.become(notReady)
      }
      finally {
        unstashAll()
      }
    }
  }

  // While reading the offset, we stash all commands, to avoid concurrent ReadCheckpoint commands
  def busyReading: Receive = {
    case _ ⇒ stash()
  }

  // After loading the offset from the storage, the actor is ready process all commands
  def ready: Receive = {

    case ReadCheckpoint ⇒ sender() ! currentOffset

    case CheckpointInMemory(value: String) ⇒ checkpointInMemoryAction(value)

    case CheckpointToStorage ⇒ {
      try {
        if (queue.size > 0) {
          context.become(busyWriting)

          var offsetToStore: String = ""
          val now = Instant.now.getEpochSecond

          val timeThreshold = config.checkpointing.checkpointTimeThreshold.toSeconds
          val countThreshold = config.checkpointing.checkpointCountThreshold

          // Check if the queue contains old offsets to flush (time threshold)
          // Check if the queue contains data of too many messages (count threshold)
          while (queue.size > 0 && ((queuedOffsets >= countThreshold) || ((now - timeOf(queue.head)) >= timeThreshold))) {
            val data = queue.dequeue()
            offsetToStore = offsetOf(data)
            queuedOffsets -= countOf(data)

            if (queue.size == 0) queuedOffsets = 0
          }

          if (offsetToStore == "") {
            log.debug("Checkpoint skipped: partition={}, count {} < threshold {}", partition, queuedOffsets, config.checkpointing.checkpointCountThreshold)
          } else {
            log.info("Writing checkpoint: partition={}, storing {} (current offset={})", partition, offsetToStore, currentOffset)
            storage.writeOffset(config.connect.hashedNamespace, partition, offsetToStore)
          }
        } else {
          log.debug("Partition={}, checkpoint queue is empty [count {}, current offset={}]", partition, queuedOffsets, currentOffset)
        }
      } catch {
        case e: Exception ⇒ log.error(e, e.getMessage)
      } finally {
        context.become(ready)
      }
    }
  }

  // While writing we discard CheckpointToStorage signals
  def busyWriting: Receive = {

    case ReadCheckpoint ⇒ sender() ! currentOffset

    case CheckpointInMemory(value: String) ⇒ checkpointInMemoryAction(value)

    case CheckpointToStorage ⇒ {}
  }

  def checkpointInMemoryAction(offset: String) = {

    if (!schedulerStarted) {
      val time = config.checkpointing.checkpointFrequency
      schedulerStarted = true
      context.system.scheduler.schedule(time, time, self, CheckpointToStorage)
      log.info("Scheduled checkpoint for partition {} every {} ms", partition, time.toMillis)
    }

    if (offset.toLong > currentOffset.toLong) {
      val epoch = Instant.now.getEpochSecond

      // Reminder:
      //  queue.enqueue --> queue.last == queue(queue.size -1)
      //  queue.dequeue --> queue.head == queue(0)

      // If the tail of the queue contains an offset stored in the current second, then increment
      // the count of messages for that second. Otherwise enqueue a new element.
      if (queue.size > 0 && epoch == timeOf(queue.last))
        queue.update(queue.size - 1, Tuple3(offset, epoch, countOf(queue.last) + 1))
      else
        queue.enqueue(Tuple3(offset, epoch, 1))

      queuedOffsets += 1
      currentOffset = offset
    }
  }

  def getCheckpointBackend = locator.getCheckpointBackend(config.checkpointing)

  def offsetOf(x: OffsetsData): String = x._1

  def timeOf(x: OffsetsData): Long = x._2

  def countOf(x: OffsetsData): Long = x._3
}
