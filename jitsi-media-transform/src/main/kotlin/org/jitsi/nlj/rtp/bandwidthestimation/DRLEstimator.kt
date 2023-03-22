/*
 * Copyright @ 2019 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj.rtp.bandwidthestimation

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import java.time.Instant
import khttp.post
import org.jitsi.nlj.util.bps
import kotlin.math.sqrt

class DRLEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
    GoogleCcEstimator(diagnosticContext, parentLogger) {
    val getBitrateStatsFunc = diagnosticContext["getBitrateStatsFunc"] as () -> Array<Long>

    override fun doFeedbackComplete(now: Instant) {
        if(Instant.now().toEpochMilli() - lastUpdateBwe.toEpochMilli() > updateBwePeriodMs) {
            sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
            val epID = diagnosticContext["endpoint_id"]
            val (idealBps, targetBps) = getBitrateStatsFunc()
            val json = arrayOf(epID,
                    Instant.now().toEpochMilli(),
                    bitrateEstimatorAbsSendTime.incomingBitrate.getRateBps(),
                    pktLossCnt/(pktLossCnt + pktRecvCnt + 0.001),
                    sendSideBandwidthEstimation.rttMs,
                    sqrt(bitrateEstimatorAbsSendTime.noiseVar),
                    bitrateEstimatorAbsSendTime.bwState,
                    idealBps,
                    targetBps,
                    sendSideBandwidthEstimation.latestEstimate.bps.bps,
                    bitrateEstimatorAbsSendTime.bwUsage)
            val rep = post(url = "http://141.223.181.174:9005/predict", json = json)

            latestBwe = Bandwidth(rep.jsonObject["bwe"].toString().toDouble())
            reportBandwidthEstimate(now, latestBwe)
            lastUpdateBwe = Instant.now()

            clear_stats()
        }
    }
}
