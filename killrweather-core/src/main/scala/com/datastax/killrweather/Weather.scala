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

import org.apache.spark.util.StatCounter

object Weather {

  /** Base marker trait. */
  @SerialVersionUID(1L)
  sealed trait WeatherModel extends Serializable

  /**
   * @param id Composite of Air Force Datsav3 station number and NCDC WBAN number
   * @param name Name of reporting station
   * @param countryCode 2 letter ISO Country ID // TODO restrict
   * @param callSign International station call sign
   * @param lat Latitude in decimal degrees
   * @param long Longitude in decimal degrees
   * @param elevation Elevation in meters
   */
  case class WeatherStation(
    id: String,
    name: String,
    countryCode: String,
    callSign: String,
    lat: Double,
    long: Double,
    elevation: Double) extends WeatherModel

  /**
   * @param wsid Composite of Air Force Datsav3 station number and NCDC WBAN number
   * @param year Year collected
   * @param month Month collected
   * @param day Day collected
   * @param hour Hour collected
   * @param temperature Air temperature (degrees Celsius)
   * @param dewpoint Dew point temperature (degrees Celsius)
   * @param pressure Sea level pressure (hectopascals)
   * @param windDirection Wind direction in degrees. 0-359
   * @param windSpeed Wind speed (meters per second)
   * @param skyCondition Total cloud cover (coded, see format documentation)
   * @param skyConditionText Non-coded sky conditions
   * @param oneHourPrecip One-hour accumulated liquid precipitation (millimeters)
   * @param sixHourPrecip Six-hour accumulated liquid precipitation (millimeters)
   */
  case class RawWeatherData(
    weather_station: String,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    temperature: Double,
    dewpoint: Double,
    pressure: Double,
    windDirection: Int,
    windSpeed: Double,
    skyCondition: Int,
    skyConditionText: String,
    oneHourPrecip: Double,
    sixHourPrecip: Double) extends WeatherModel

  object RawWeatherData {
    /** Tech debt - don't do it this way ;) */
    def apply(array: Array[String]): RawWeatherData = {
      RawWeatherData(
        weather_station = array(0),
        year = array(1).toInt,
        month = array(2).toInt,
        day = array(3).toInt,
        hour = array(4).toInt,
        temperature = array(5).toDouble,
        dewpoint = array(6).toDouble,
        pressure = array(7).toDouble,
        windDirection = array(8).toInt,
        windSpeed = array(9).toDouble,
        skyCondition = array(10).toInt,
        skyConditionText = array(11),
        oneHourPrecip = array(11).toDouble,
        sixHourPrecip = Option(array(12).toDouble).getOrElse(0))
    }
  }

  trait WeatherAggregate extends WeatherModel

  case class HourKey(wsid: String, year: Int, month: Int, day: Int, hour: Int) extends WeatherAggregate
  case class DayKey(wsid: String, year: Int, month: Int, day: Int) extends WeatherAggregate
  object DayKey {
    def apply(t: RawWeatherData): DayKey =
      DayKey(t.weather_station, t.year, t.month, t.day)
    def apply(t: (String, Int, Int, Int)): DayKey =
      DayKey(t._1,t._2,t._3,t._4)
  }
  case class DailyPrecipitation(weather_station: String, year: Int, month: Int, day: Int, precipitation: Double) extends WeatherAggregate

  object DailyPrecipitation {
    def apply(key:DayKey, precipitation: Double): DailyPrecipitation =
      DailyPrecipitation(key.wsid, key.year, key.month, key.day, precipitation)
  }

  case class Precipitation(weather_station: String, year: Int, annual: Double) extends WeatherAggregate

  case class AnnualPrecipitation(sid: String, year: Int, total: Double)

  case class DailyTemperature(weather_station: String, year: Int, month: Int, day: Int,
                              high: Double, low: Double, mean: Double, variance: Double, stdev: Double) extends WeatherAggregate

  object DailyTemperature {
    def apply(key:DayKey, v: StatCounter): DailyTemperature =
      DailyTemperature(key.wsid, key.year, key.month, key.day,
        high = v.max, low = v.min, mean = v.mean, variance = v.variance, stdev = v.stdev)
  }

  case class UriYearPartition(year: Int, uri: String) extends WeatherModel
}
