/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.kineticpulse

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer
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
      case ms: Seq[Metric.Label] =>
        val httpTimer = PrometheusMetric.httpDurationPercentiles.startTimer()
        val stopTimers = ms.flatMap { l => metric.timeRequest(l.method, l.path, l.argument) }
        nextFilter(requestHeader)
          .transform(
            result => {
              logger.debug(s"MetricFilter apply ${requestHeader.path} => ${result.header.status}")
              httpTimer.close()
              stopTimers.foreach(_.apply())
              PrometheusMetric.httpStatusCount
                .labels(result.header.status.toString, requestHeader.method)
                .inc()
              ms.foreach { l => metric.countRequest(l.method, l.path, l.argument, Option(result)) }
              metric.gaugeRequest(requestHeader, Option(result))
              result
            },
            exception => {
              logger.debug(s"MetricFilter apply ${requestHeader.path} => $exception")
              httpTimer.close()
              stopTimers.foreach(_.apply())
              // none result -> Internal Server Error 500 status
              PrometheusMetric.httpStatusCount.labels("500", requestHeader.method).inc()
              ms.foreach { l => metric.countRequest(l.method, l.path, l.argument, None) }
              metric.gaugeRequest(requestHeader, None)
              exception
            }
          )
    }
  }

}
