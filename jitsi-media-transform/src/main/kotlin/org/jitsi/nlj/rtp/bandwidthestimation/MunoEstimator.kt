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

import khttp.post
import kotlinx.coroutines.runBlocking
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import java.time.Instant
import kotlin.math.sqrt

class MunoEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
    GoogleCcEstimator(diagnosticContext, parentLogger) {
    val useGrpc = System.getenv("MUNO_USE_GRPC")?.toBoolean() ?: throw NullPointerException("MUNO_USE_GRPC env not set (set it to true or false)!")
    val munoPredRestEp = System.getenv("MUNO_PRED_REST_EP") ?: throw NullPointerException("MUNO_PRED_REST_EP env not set!")
    val getBitrateStatsFunc = diagnosticContext["getBitrateStatsFunc"] as () -> Array<Long>
    val munoClientService: MunoClientService by lazy { MunoClientService.getService() }

    override fun doFeedbackComplete(now: Instant) {
        if(Instant.now().toEpochMilli() - lastUpdateBwe.toEpochMilli() > updateBwePeriodMs) {
            sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
            val epID = diagnosticContext["endpoint_id"] as String
            val (idealBps, targetBps) = getBitrateStatsFunc()
            val gccBwe = sendSideBandwidthEstimation.latestEstimate.bps.bps
            val lossRate = pktLossCnt / (pktLossCnt + pktRecvCnt + 0.001)
            if (!useGrpc) {
                val json = arrayOf(epID,
                        Instant.now().toEpochMilli(),
                        bitrateEstimatorAbsSendTime.incomingBitrate.getRateBps(),
                        lossRate,
                        sendSideBandwidthEstimation.rttMs,
                        sqrt(bitrateEstimatorAbsSendTime.noiseVar),
                        bitrateEstimatorAbsSendTime.bwState,
                        idealBps,
                        targetBps,
                        gccBwe,
                        bitrateEstimatorAbsSendTime.delayGrad)
                val rep = post(url = munoPredRestEp, json = json)

                latestBwe = Bandwidth(rep.jsonObject["bwe"].toString().toDouble())
            } else {
                latestBwe = Bandwidth(runBlocking {
                    munoClientService.munoGetBwe(epID, gccBwe.toFloat(),
                                                lossRate.toFloat(),
                                                bitrateEstimatorAbsSendTime.delayGrad.toFloat())
                }.toDouble())
            }
            reportBandwidthEstimate(now, latestBwe)
            lastUpdateBwe = Instant.now()

            clear_stats()
        }
    }
}
