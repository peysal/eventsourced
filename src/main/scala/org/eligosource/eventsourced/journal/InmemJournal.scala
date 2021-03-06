/*
 * Copyright 2012 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.journal

import scala.collection.immutable.SortedMap

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._

/**
 * In-memory journal for testing purposes.
 */
private [eventsourced] class InmemJournal extends Journal {
  var redoMap = SortedMap.empty[Key, Any]

  def executeWriteInMsg(cmd: WriteInMsg) {
    redoMap = redoMap + (Key(cmd.processorId, 0, counter, 0) -> cmd.message.clearConfirmationSettings)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) {
    redoMap = redoMap + (Key(Int.MaxValue, cmd.channelId, counter, 0) -> cmd.message.clearConfirmationSettings)

    if (cmd.ackSequenceNr != SkipAck) {
      redoMap = redoMap + (Key(cmd.ackProcessorId, 0, cmd.ackSequenceNr, cmd.channelId) -> null)
    }
  }

  def executeWriteAck(cmd: WriteAck) {
    redoMap = redoMap + (Key(cmd.processorId, 0, cmd.ackSequenceNr, cmd.channelId) -> null)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    redoMap = redoMap - Key(Int.MaxValue, cmd.channelId, cmd.msgSequenceNr, 0)
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => executeReplayInMsgs(cmd, msg => p(msg, cmd.target)))
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, p)
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, p)
  }

  def storedCounter = counter

  private def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val startKey = Key(processorId, channelId, fromSequenceNr, 0)
    val iter = redoMap.from(startKey).iterator.buffered
    replay(iter, startKey, p)
  }

  @scala.annotation.tailrec
  private def replay(iter: BufferedIterator[(Key, Any)], key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey   = nextEntry._1
      assert(nextKey.confirmingChannelId == 0)
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = nextEntry._2.asInstanceOf[Message]
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: BufferedIterator[(Key, Any)], key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.head
      val nextKey = nextEntry._1
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }
}

/**
 * Creates an in-memory journal for testing purposes.
 */
object InmemJournal {
  def apply(name: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    if (name.isDefined)
      system.actorOf(Props(new InmemJournal), name.get) else
      system.actorOf(Props(new InmemJournal))
}