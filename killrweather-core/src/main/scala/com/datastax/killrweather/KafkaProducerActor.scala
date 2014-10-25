/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.killrweather

import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}
import java.util.Properties

import akka.actor.{ActorSystem, ActorLogging, Actor}
import com.datastax.killrweather.Weather.RawWeatherData
import scalaz.concurrent.Task
import kafka.producer.KeyedMessage
import kafka.producer.Partitioner
import kafka.producer.ProducerClosedException
import kafka.producer.ProducerConfig
import kafka.producer.ProducerPool
import kafka.producer.ProducerTopicStatsRegistry
import kafka.producer.async._
import kafka.utils._
import kafka.serializer.Encoder
import kafka.common.QueueFullException
import kafka.metrics._

class KafkaProducerActor[K, V](props: Properties, config: ProducerConfig) extends Actor with ActorLogging {

  def this(props: Properties, brokers: Set[String]) =
    this(props, KafkaProducer.producerConfig(brokers))

  def this(brokers: Set[String]) =
    this(KafkaProducer.producerProps(brokers), brokers)

  import KafkaEvent._

  implicit val system: ActorSystem = context.system

  //val encoder:Encoder[RawWeatherData]  = new KillrWeatherEncoder(config.props)(system)

  val eventHandler = new DefaultEventHandler[K,V](config,
        Utils.createObject[Partitioner[K]](config.partitionerClass, config.props),
        Utils.createObject[Encoder[V]](config.serializerClass, config.props),
        Utils.createObject[Encoder[K]](config.keySerializerClass, config.props),
        new ProducerPool(config))

  private val hasShutdown = false
  private val queue = new LinkedBlockingQueue[KeyedMessage[K, V]](config.queueBufferingMaxMessages)
  private val producerSendThread: ProducerSendThread[K, V] = new ProducerSendThread[K, V](
    s"ProducerSendThread-$config.clientId",
    queue, eventHandler, config.queueBufferingMaxMs, config.batchNumMessages, config.clientId)

  producerSendThread.start()

  private val producerTopicStats = ProducerTopicStatsRegistry.getProducerTopicStats(config.clientId)

  KafkaMetricsReporter.startReporters(config.props)

  override def postStop(): Unit = close()

  def receive = {
    case e: KafkaMessageEnvelope[K,V] => send(e.messages:_*)
  }

  def send(topic: String, k:K, v:V): Unit =
    send(new KeyedMessage[K, V](topic, k, v))

  /**
   * Sends the data, partitioned by key to the topic using either the
   * synchronous or the asynchronous producer
   * @param messages the producer data object that encapsulates the topic, key and message data
   */
  def send(messages: KeyedMessage[K, V]*): Unit = {
    if (hasShutdown) throw new ProducerClosedException
    recordStats(messages)
    //log.debug(s"Attempting to send ${messages.headOption}")
    asyncSend(messages)
  }

  private def recordStats(messages: Seq[KeyedMessage[K, V]]): Unit =
    for (message <- messages) {
      producerTopicStats.getProducerTopicStats(message.topic).messageRate.mark()
      producerTopicStats.getProducerAllTopicsStats.messageRate.mark()
    }

  private def asyncSend(messages: Seq[KeyedMessage[K, V]]): Unit =
    for (message <- messages) {
      val added = config.queueEnqueueTimeoutMs match {
        case 0 =>
          queue.offer(message)
        case _ =>
          try config.queueEnqueueTimeoutMs < 0 match {
            case true =>
              queue.put(message)
              true
            case _ =>
              queue.offer(message, config.queueEnqueueTimeoutMs, TimeUnit.MILLISECONDS)
          }
          catch {
            case e: InterruptedException =>
              log.error(e, "Error sending messages")
              false
          }
      }
      if (!added) {
        producerTopicStats.getProducerTopicStats(message.topic).droppedMessageRate.mark()
        producerTopicStats.getProducerAllTopicsStats.droppedMessageRate.mark()
        throw new QueueFullException("Event queue is full of unsent messages, could not send event: " + message.toString)
      } else {
        //log.debug(s"Added to send queue an event '${message.toString}. Remaining queue size '${queue.remainingCapacity}'")
      }
    }

  /**
   * Close API to close the producer pool connections to all Kafka brokers. Also closes
   * the zookeeper client connection if one exists
   */
  def close(): Task[Unit] = Task.delay {
    if (!hasShutdown) {
      log.info("Shutting down producer.")
      producerSendThread.shutdown
      eventHandler.close
    }
  }
}

object KafkaEvent {
  case class KafkaMessageEnvelope[K,V](messages: KeyedMessage[K, V]*)
}