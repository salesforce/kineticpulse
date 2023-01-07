/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.kineticpulse

import java.io.StringWriter
import javax.inject.{Inject, Singleton}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.ConfigFactory
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Summary}
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{RequestHeader, Result}

trait Metric {

  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def baseName: String = "com.salesforce.mce.kineticpulse"

  lazy val config = new Configuration(ConfigFactory.load())

  val isMetricDisabled: Boolean =
    !config.getOptional[Boolean](s"$baseName.metrics.enabled").getOrElse(false)

  if (isMetricDisabled) {
    logger.warn(s"kineticpulse parse requests for metrics collection is disabled.")
  }

  val bypassPaths: Set[String] =
    config.getOptional[Seq[String]](s"$baseName.metrics.bypass.paths") match {
      case Some(paths) => paths.toSet
      case _ => Set[String]()
    }

  val routesToTrack: Set[String] =
    config.getOptional[Seq[String]](s"$baseName.metrics.routes-to-track").getOrElse(Nil).toSet

  private lazy val routesToTrackList: Set[Seq[String]] =
    routesToTrack.map(_.stripPrefix("/").stripSuffix("/").split("/").toSeq.reverse)

  val delimiter: String = config.getOptional[String](s"$baseName.metrics.delimiter").getOrElse("-")

  val unmatchedPathValue: String =
    config.getOptional[String](s"$baseName.metrics.unmatched-path").getOrElse("")

  /**
   * parse request to identify labels for metrics
   * @param request  http request
   * @return Seq of Tuples of (method, path, argument)
   */
  def parseRequest(request: RequestHeader): Seq[(String, String, String)] = {
    parseSubSegments(request, getPathLadder)
  }

  /**
   * Example:  "/api/v1/spotme/latest" is transformed to
   * ["api/v1/spotme/latest", "api/v1/spotme", "api/v1", "api"]
   * @param str  request path
   * @return
   */
  private def getPathLadder(str: String): immutable.Seq[String] = {
    val strList = str.stripPrefix("/").stripSuffix("/").split("/").toList
    val ladder = strList.indices
      .foldLeft(List(List[String]()))((xs, i) => {
        strList.dropRight(i) :: xs
      })
      .dropRight(1)
      .map(_.mkString("/"))
      .reverse
    logger.debug(s"path ladder=$ladder")
    ladder
  }

  /**
   * From RequestHeader path,
   * check bypass path and if metric logic is enabled,
   * transform path based on config to info to be used for metrics labels
   *
   * Example:
   * routesToTrack is ["spotme", "spotme/latest"]
   * request.path is "/api/v1/spotme/latest/sometype"
   * function returns
   * [("GET", "spotme", ""), ("GET", "spotme-latest", "")]
   * @param request  http request
   * @param permutePathFunc  function to give permutations of request path
   * @return
   */
  private def parseSubSegments(
    request: RequestHeader,
    permutePathFunc: String => Seq[String]
  ): Seq[(String, String, String)] = {
    if (isMetricDisabled || isBypassPath(request.path)) {
      logger.debug(s"Metric parseRequest returning None for ${request.path}")
      Nil
    } else {
      val pathMatches = for {
        pathSegments <- permutePathFunc(request.path)
        reference <- routesToTrackList
      } yield {
        val pathList = pathSegments.split("/").toSeq.reverse
        val optMatches =
          if (pathList.take(reference.length).equals(reference))
            Some(reference.reverse.mkString(delimiter))
          else None
        logger.debug(
          s"parseSubSegments optMatches=${optMatches.toSeq} pathList=$pathList reference=$reference"
        )
        optMatches.toSeq
      }
      logger.debug(
        s"parseSubSegments pathMatches.distinct=${pathMatches.distinct}"
      )
      // matches: seq of tuple of (method, path, argument)
      val matches = pathMatches.distinct.flatten.map { (p: String) =>
        // The cardinality of the labels increases the number of time series in the metric,
        // thus affecting the memory and compute resources for maintaining the Summary.
        // This default trait function leaves argument as an empty String.
        (request.method, p, "")
      }
      if (matches.isEmpty)
        Seq((request.method, unmatchedPathValue, "")) // single element seq to collect metric
      else matches
    }
  }

  def isBypassPath(path: String): Boolean = { bypassPaths.contains(path) }

  def timeRequest(requestHeader: RequestHeader, resultOpt: Option[Result]): Seq[() => Unit]
  def timeRequest(method: String, staticPath: String, argument: String): Seq[() => Unit]

  def countRequest(requestHeader: RequestHeader, resultOpt: Option[Result]): Unit
  def countRequest(
    method: String,
    staticPath: String,
    argument: String,
    resultOpt: Option[Result]
  ): Unit

  def gaugeRequest(requestHeader: RequestHeader, resultOpt: Option[Result]): Unit

  def collect: Future[String]

  def onCollect(): Unit

}

@Singleton
class PrometheusMetric @Inject() (implicit ec: ExecutionContext) extends Metric {

  DefaultExports.initialize()

  override def timeRequest(
    requestHeader: RequestHeader,
    resultOpt: Option[Result]
  ): Seq[() => Unit] = {
    parseRequest(requestHeader) match {
      case ms: Seq[(String, String, String)] =>
        ms.flatMap { x => timeRequest(x._1, x._2, x._3) }
      case _ => // either metric is not enabled, or the request path is bypassed
        logger.debug(s"timeRequest Nil")
        Nil
    }
  }

  override def timeRequest(
    method: String,
    staticPath: String,
    argument: String
  ): Seq[() => Unit] = {
    logger.debug(s"timeRequest $method $staticPath $argument")
    Seq(
      PrometheusMetric.apiLatency.labels(method, staticPath, argument).startTimer()
    ).map(t => () => t.close())
  }

  override def countRequest(requestHeader: RequestHeader, resultOpt: Option[Result]): Unit = {
    parseRequest(requestHeader) match {
      case ms: Seq[(String, String, String)] =>
        PrometheusMetric.httpStatusCount
          .labels(
            // none result -> Internal Server Error 500 status
            resultOpt.fold("500")(_.header.status.toString),
            requestHeader.method
          )
          .inc()
        ms.foreach { x => countRequest(x._1, x._2, x._3, resultOpt) }
      case _ =>
    }
  }

  override def countRequest(
    method: String,
    staticPath: String,
    argument: String,
    resultOpt: Option[Result]
  ): Unit = {
    logger.debug(s"countRequest $method $staticPath $argument")
    val resultStatus = resultOpt.fold("500")(_.header.status.toString)
    PrometheusMetric.apiStatusCount.labels(resultStatus, method, staticPath, argument).inc()
  }

  override def gaugeRequest(requestHeader: RequestHeader, resultOpt: Option[Result]): Unit = {
    logger.debug(s"gaugeRequest ${requestHeader.method} ${requestHeader.path}}")
  }

  /**
   * Use Prometheus client utility to serialize metrics for scraping.
   *
   * Even for additional custom metrics that are defined in other classes,
   * so long as they are registered to the default registry, those custom
   * metrics will be serialized for scraping also.
   *
   * @return Future String serialized in Prometheus metrics format
   *         on the metrics collected
   */
  override def collect: Future[String] = Future {
    val writer = new StringWriter()
    TextFormat.write004(writer, PrometheusMetric.registry.metricFamilySamples())
    writer.toString
  }

  override def onCollect(): Unit = {}

}

object PrometheusMetric {

  val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  // count all requests except the routes specified in bypass.paths in config
  val httpStatusCount: Counter = Counter.build
    .name("http_requests_total")
    .help("Total HTTP Requests Count")
    .labelNames("status", "method")
    .register(registry)

  // count all request duration seconds by percentile except the bypass.paths
  val httpDurationPercentiles: Summary = Summary
    .build()
    .name("http_requests_percentile_seconds")
    .help("Total HTTP Requests Duration Percentile in seconds")
    .quantile(0.5d, 0.001d)
    .quantile(0.95d, 0.001d)
    .quantile(0.99d, 0.001d)
    .register()

  // count requests with focus on routes-to-track config derived metrics labels
  val apiStatusCount: Counter = Counter.build
    .name("api_requests_total")
    .help("Total API HTTP Requests Count")
    .labelNames("status", "method", "path", "arguments")
    .register(registry)

  // match http request path to routes-to-track config for more detailed duration latency metrics
  val apiLatency: Summary = Summary
    .build()
    .name("api_duration_seconds_summary")
    .labelNames("method", "path", "arguments")
    .help("Profile API response time in seconds summary")
    .quantile(0.5d, 0.001d)
    .quantile(0.95d, 0.001d)
    .quantile(0.99d, 0.001d)
    .register(registry)

}
