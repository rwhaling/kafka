/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.api

import java.nio.ByteBuffer
import kafka.common.{ErrorMapping, TopicAndPartition}
import kafka.utils.Utils


object OffsetResponse {

  def readFrom(buffer: ByteBuffer): OffsetResponse = {
    val versionId = buffer.getShort
    val numTopics = buffer.getInt
    val pairs = (1 to numTopics).flatMap(_ => {
      val topic = Utils.readShortString(buffer)
      val numPartitions = buffer.getInt
      (1 to numPartitions).map(_ => {
        val partition = buffer.getInt
        val error = buffer.getShort
        val numOffsets = buffer.getInt
        val offsets = (1 to numOffsets).map(_ => buffer.getLong)
        (TopicAndPartition(topic, partition), PartitionOffsetsResponse(error, offsets))
      })
    })
    OffsetResponse(versionId, Map(pairs:_*))
  }

}


case class PartitionOffsetsResponse(error: Short, offsets: Seq[Long])


case class OffsetResponse(versionId: Short,
                          partitionErrorAndOffsets: Map[TopicAndPartition, PartitionOffsetsResponse])
        extends RequestOrResponse {

  lazy val offsetsGroupedByTopic = partitionErrorAndOffsets.groupBy(_._1.topic)

  def hasError = partitionErrorAndOffsets.values.exists(_.error != ErrorMapping.NoError)

  val sizeInBytes = {
    2 + /* versionId */
    4 + /* topic count */
    offsetsGroupedByTopic.foldLeft(0)((foldedTopics, currTopic) => {
      val (topic, errorAndOffsetsMap) = currTopic
      foldedTopics +
      Utils.shortStringLength(topic) +
      4 + /* partition count */
      errorAndOffsetsMap.foldLeft(0)((foldedPartitions, currPartition) => {
        foldedPartitions +
        4 + /* partition id */
        2 + /* partition error */
        4 + /* offset array length */
        currPartition._2.offsets.size * 8 /* offset */
      })
    })
  }

  def writeTo(buffer: ByteBuffer) {
    buffer.putShort(versionId)
    buffer.putInt(offsetsGroupedByTopic.size) // topic count
    offsetsGroupedByTopic.foreach {
      case((topic, errorAndOffsetsMap)) =>
        Utils.writeShortString(buffer, topic)
        buffer.putInt(errorAndOffsetsMap.size) // partition count
        errorAndOffsetsMap.foreach {
          case((TopicAndPartition(_, partition), errorAndOffsets)) =>
            buffer.putInt(partition)
            buffer.putShort(errorAndOffsets.error)
            buffer.putInt(errorAndOffsets.offsets.size) // offset array length
            errorAndOffsets.offsets.foreach(buffer.putLong(_))
        }
    }
  }

}
