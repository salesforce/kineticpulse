/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.kineticpulse

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import akka.stream.Materializer
import org.slf4j.LoggerFactory
import play.api.mvc.{Filter, RequestHeader, Result}

class MetricFilter @Inject() (
  metric: Metric
)(implicit
  val mat: Materializer,
  ec: ExecutionContext
) extends Filter {

  private val logger = LoggerFactory.getLogger(getClass)

  def apply(
    nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    metric.parseRequest(requestHeader) match {
      case Nil => nextFilter(requestHeader)
      case ms: Seq[(String, String, String)] =>
        val httpTimer = PrometheusMetric.httpDurationPercentiles.startTimer()
        val stopTimers = ms.flatMap { x => metric.timeRequest(x._1, x._2, x._3) }
        nextFilter(requestHeader)
          .transform(
            result => {
              logger.debug(s"MetricFilter apply ${requestHeader.path} => ${result.header.status}")
              httpTimer.close()
              stopTimers.foreach(_.apply())
              PrometheusMetric.httpStatusCount
                .labels(result.header.status.toString, requestHeader.method)
                .inc()
              ms.foreach { x => metric.countRequest(x._1, x._2, x._3, Option(result)) }
              metric.gaugeRequest(requestHeader, Option(result))
              result
            },
            exception => {
              logger.debug(s"MetricFilter apply ${requestHeader.path} => $exception")
              httpTimer.close()
              stopTimers.foreach(_.apply())
              // none result -> Internal Server Error 500 status
              PrometheusMetric.httpStatusCount.labels("500", requestHeader.method).inc()
              ms.foreach { x => metric.countRequest(x._1, x._2, x._3, None) }
              metric.gaugeRequest(requestHeader, None)
              exception
            }
          )
    }
  }

}
