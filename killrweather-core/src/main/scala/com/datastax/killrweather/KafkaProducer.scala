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

import java.util.Properties

import akka.actor.{ActorSystem, ActorLogging}
import akka.serialization.SerializationExtension
import scalaz.concurrent.Task
import kafka.message.MessageAndMetadata
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.serializer.{DefaultEncoder, StringEncoder}
import kafka.server.KafkaConfig
import org.apache.spark.streaming.StreamingContext
import com.datastax.spark.connector.util.Logging

abstract class StreamingKafkaProducer[K, V](brokers: Set[String],
                                            ssc: StreamingContext,
                                            batchSize: Option[Int] = None)
                                           extends Logging {
  import KafkaProducer._

  def system: ActorSystem

  implicit lazy val serialization = SerializationExtension(system)

  private val conf = producerConfig(brokers, batchSize.getOrElse(100))

  private val producer = new Producer[K,V](conf)

  def send(message: MessageAndMetadata[K,V]): Unit =
    producer.send(new KeyedMessage(message.topic, message.key, message.message))

  def send(topic: String, key: K, message: V): Unit = {}
   /* try producer.send(KeyedMessage(topic, key,  message)) catch {
      case e: Throwable => logError(s"Error sending message $message", e)
    }*/

  def close(): Task[Unit] = Task.delay {
    logInfo(s"Closing producer.")
    producer.close()
  }
}

/** Simple producer for an Akka Actor. */
trait KafkaProducer extends WeatherActor with ActorLogging {
  import KafkaProducer._

  def config: KafkaConfig

  private val producer = new Producer[String, String](producerConfig(config, 360))

  def send(topic : String, key : String, message : String): Unit =
    producer.send(new KeyedMessage[String, String](topic, key, message))

  def batchSend(topic: String, key: String, batch: Seq[String]): Unit = {
    val messages = batch map (msg => new KeyedMessage[String, String](topic, key, msg))
    producer.send(messages.toArray: _*)
    log.debug(s"Published ${batch.size} messages to kafka topic '$topic'")
  }

  def batchSend(topic: String, key: String, batchSize: Int, lines: Seq[String]): Unit =
    if (lines.nonEmpty) {
      val (toSend, unsent) = lines.toSeq.splitAt(batchSize)
      val messages = toSend map (msg => new KeyedMessage[String, String](topic, key, msg))
      producer.send(messages.toArray: _*)
      if(unsent.size > 0)log.debug(s"Published batch messages to kafka topic '$topic'. Batching remaining ${unsent.size}")
      batchSend(topic, key, batchSize, unsent)
    }

  override def postStop(): Unit = producer.close()

}

private[killrweather] object KafkaProducer {

  // string serializer
  def producerConfig(config: KafkaConfig, batchSize: Int): ProducerConfig = {
    val brokers = Set(s"${config.hostName}:${config.port}")
    val props = new Properties()
    props.put("metadata.broker.list", brokers)
    props.put("serializer.class", classOf[StringEncoder].getName)
    props.put("partitioner.class", "kafka.producer.DefaultPartitioner")
    props.put("producer.type", "async")
    props.put("request.required.acks", "1")
    props.put("batch.num.messages", batchSize.toString)
    new ProducerConfig(props)
  }

  def producerProps(brokers: Set[String], batchSize: Int = 100): Properties = {
   /* val p = new Properties()
    p.put("metadata.broker.list", kafkaConfig.hostName + ":" + kafkaConfig.port)
    p.put("serializer.class", classOf[StringEncoder].getName)
    */
    val props = new Properties()
    props.put("metadata.broker.list", brokers)
    props.put("serializer.class", "com.datastax.killrweather.KillrWeatherEncoder")
    props.put("partitioner.class", "kafka.producer.DefaultPartitioner")
    props.put("producer.type", "async")
    props.put("request.required.acks", "1")
    //props.put("batch.num.messages", batchSize.toString)
    props
  }

  def producerConfig(brokers: Set[String], batchSize: Int = 100): ProducerConfig =
    new ProducerConfig(producerProps(brokers, batchSize))

}
