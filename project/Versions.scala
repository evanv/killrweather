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

object Versions {

  val Akka           = "2.2.3" // binary incompat with akka and config latest 2.3.6 with spark :(
  val Config         = "1.2.1"
  val JDK            = "1.7"
  val JodaConvert    = "1.7"
  val JodaTime       = "2.4"
  val Json4s         = "3.2.10"
  val Kafka          = "0.8.0"// TODO issues w encoder/decoder ATM "0.8.1.1"
  val Kryo           = "3.0.0"
  val Lzf            = "0.8.4"
  val Scala          = "2.10.4"
  val ScalaTest      = "2.2.1"
  val Scalaz         = "7.1.0"
  val ScalazContrib  = "0.1.5"
  val ScalazStream   = "0.1"
  val Sigar          = "1.6.4"
  val Slf4j          = "1.7.7"
  val Spark          = "1.1.0"
  val SparkCassandra = "1.1.0-alpha4"
}
