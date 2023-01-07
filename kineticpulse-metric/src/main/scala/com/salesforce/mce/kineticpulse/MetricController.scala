/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.kineticpulse

import javax.inject._

import scala.concurrent.ExecutionContext

import play.api.mvc._

@Singleton
class MetricController @Inject() (
  cc: ControllerComponents,
  metric: Metric
)(implicit
  ec: ExecutionContext
) extends AbstractController(cc) {

  def collect: Action[AnyContent] = Action.async { _: Request[AnyContent] =>
    val metricResults = metric.collect.map(output => Ok(output))
    metricResults.onComplete(_ => metric.onCollect())
    metricResults
  }

}
