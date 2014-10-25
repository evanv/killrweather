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
import java.util.concurrent.CountDownLatch

import akka.serialization.SerializationExtension
import com.datastax.killrweather.Weather.RawWeatherData

import scala.concurrent.duration._
import akka.actor.Props
import kafka.admin.AdminUtils
import org.apache.spark.SparkContext
import com.datastax.spark.connector.embedded._
import com.datastax.spark.connector.streaming._
import com.datastax.spark.connector.cql.CassandraConnector

class KafkaStreamingActorSpec extends ActorSparkSpec {
  import settings.{CassandraKeyspace => keyspace}
  import Weather._
  import WeatherEvent._
  import settings._
  import SparkContext._

  implicit val ec = system.dispatcher

  val wsid = "010010:99999"
  /* Must be updated with different /data/load files */
  val expected = 8220
  /* Current data file is for one wsid so 1 year's days */
  val dailySize = 360

  val kafka = new EmbeddedKafka
  Thread.sleep(1000)

  // fail fast if you have not run the create and load cql scripts yet :)
  CassandraConnector(conf).withSessionDo { session =>
    // insure for test we are not going to look at existing data, but new from the kafka actor processes
    session.execute(s"DROP TABLE IF EXISTS $keyspace.raw_weather_data")
    session.execute(s"DROP TABLE IF EXISTS $keyspace.daily_aggregate_precip")
    session.execute(s"DROP TABLE IF EXISTS $keyspace.daily_aggregate_temperature")

    session.execute( s"""CREATE TABLE IF NOT EXISTS $keyspace.raw_weather_data (
      weather_station text, year int, month int, day int, hour int,temperature double, dewpoint double, pressure double,
      wind_direction int, wind_speed double,sky_condition int, sky_condition_text text, one_hour_precip double, six_hour_precip double,
      PRIMARY KEY ((weather_station), year, month, day, hour)
     ) WITH CLUSTERING ORDER BY (year DESC, month DESC, day DESC, hour DESC)""")

    session.execute( s"""CREATE TABLE IF NOT EXISTS $keyspace.daily_aggregate_temperature (
       weather_station text,year int,month int,day int,high double,low double,mean double,variance double,stdev double,
       PRIMARY KEY ((weather_station), year, month, day)
    ) WITH CLUSTERING ORDER BY (year DESC, month DESC, day DESC)""")

    session.execute( s"""CREATE TABLE IF NOT EXISTS $keyspace.daily_aggregate_precip (
       weather_station text,year int,month int,day int,precipitation counter,
       PRIMARY KEY ((weather_station), year, month, day)
    ) WITH CLUSTERING ORDER BY (year DESC, month DESC, day DESC)""")
  }

  kafka.createTopic(KafkaTopicRaw)

  //AdminUtils.createTopic(kafka.client, KafkaTopicRaw, partitions = 1, replicationFactor = 1, new Properties())

  val brokers = Set(s"${kafka.kafkaConfig.hostName}:${kafka.kafkaConfig.port}")

  val kafkaActor = system.actorOf(Props(new KafkaStreamingActor(
    kafka.kafkaConfig, kafka.kafkaParams, brokers, ssc, settings, self)), "kafka")

  val latch = new CountDownLatch(expected)
  val consumer = new KafkaTestConsumer(kafka.kafkaConfig.zkConnect, KafkaTopicRaw, KafkaGroupId, 1, latch)

  expectMsgPF(30.seconds) {
    case OutputStreamInitialized => start()
  }

  /* Loads historic data from /data/load files (because this is for a runnable demo.
   * Because we run locally vs against a cluster as a demo app, we keep that file size data small.
   * Using rdd.toLocalIterator will consume as much memory as the largest partition in this RDD,
   * which in this use case is 360 or fewer (if current year before December 31) small Strings. */
  for (partition <- ByYearPartitions) {
    val toActor = (data: RawWeatherData) => kafkaActor ! data

    ssc.sparkContext.textFile(partition.uri)
      .flatMap(_.split("\\n"))
      .map(_.split(","))
      .map(RawWeatherData(_))
      .toLocalIterator
      .foreach(toActor)
  }

  "KafkaStreamingActor" must {
    "transforms annual weather .gz files to line data and publish to a Kafka topic" in {
      awaitCond(latch.getCount > 100, 3.seconds) // assert process of publishing has started
    }
    s"streams in data from kafka, transforms it, and saves it to $CassandraTableRaw" in {
      val tableData = ssc.cassandraTable(keyspace, CassandraTableRaw)
      awaitCond(tableData.toLocalIterator.size == expected, 60.seconds)
    }
    s"transforms it, and saves it to $CassandraTableDailyPrecip" in {
      val tableData = ssc.cassandraTable(keyspace, CassandraTableDailyPrecip)
      awaitCond(tableData.toLocalIterator.size > 200, 160.seconds)

      val raw = ssc.cassandraTable[Double](keyspace, CassandraTableRaw)
        .select("one_hour_precip").collect.toSeq

      raw.foreach(d => println(s"$CassandraTableRaw: $d"))

      val daily = ssc.cassandraTable[DailyPrecipitation](keyspace, CassandraTableDailyPrecip)
        .collect.toSeq

      daily.foreach(d => println(s"$CassandraTableDailyPrecip: $d"))
    }
    "daily temps" in {
      val tableData = ssc.cassandraTable(keyspace, CassandraTableDailyTemp)
      awaitCond(tableData.toLocalIterator.size > 200, 160.seconds)
      tableData.foreach(d => println(s"$CassandraTableDailyTemp: $d"))
    }
  }

  override def afterAll() {
    super.afterAll()
    consumer.shutdown()
    kafka.shutdown()
    Thread.sleep(2000) // hrm, no clean shutdown found yet that doesn't throw
  }
}
