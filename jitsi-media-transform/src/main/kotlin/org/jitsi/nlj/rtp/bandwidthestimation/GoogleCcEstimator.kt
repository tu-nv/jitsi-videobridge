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
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.mbps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation
import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates
import khttp.post
import kotlin.math.sqrt

open class GoogleCcEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
    BandwidthEstimator(diagnosticContext) {
    private val defaultInitBw: Bandwidth = 2.5.mbps
    var rttCnt = 0
    var pktRecvCnt = 0
    var pktLossCnt = 0

    var lastUpdateBwe = Instant.now()
    val updateBwePeriodMs = 200
    var latestBwe = Bandwidth(2500000.0)
    val munoStatsRestEp = System.getenv("MUNO_STATS_REST_EP") ?: throw NullPointerException("MUNO_STATS_REST_EP env not set!")

    override val algorithmName = "Google CC"

    /* TODO: Use configuration service to set this default value. */
    override var initBw: Bandwidth = defaultInitBw
    /* TODO: observable which sets the components' values if we're in initial state. */

    override var minBw: Bandwidth by Delegates.observable(GoogleCcEstimatorConfig.minBw) {
            _, _, newValue ->
        bitrateEstimatorAbsSendTime.setMinBitrate(newValue.bps.toInt())
        sendSideBandwidthEstimation.setMinMaxBitrate(newValue.bps.toInt(), maxBw.bps.toInt())
    }

    override var maxBw: Bandwidth by Delegates.observable(GoogleCcEstimatorConfig.maxBw) {
            _, _, newValue ->
        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), newValue.bps.toInt())
    }

    private val logger = createChildLogger(parentLogger)

    /**
     * Implements the delay-based part of Google CC.
     */
    val bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger).also {
        it.setMinBitrate(minBw.bps.toInt())
    }

    /**
     * Implements the loss-based part of Google CC.
     */
    val sendSideBandwidthEstimation =
        SendSideBandwidthEstimation(diagnosticContext, initBw.bps.toLong(), logger).also {
            it.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
        }

    override fun doProcessPacketArrival(
        now: Instant,
        sendTime: Instant?,
        recvTime: Instant?,
        seq: Int,
        size: DataSize,
        ecn: Byte,
        previouslyReportedLost: Boolean
    ) {
        if (sendTime != null && recvTime != null) {
            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                now.toEpochMilli(),
                sendTime.toEpochMilli(), recvTime.toEpochMilli(), size.bytes.toInt()
            )
        }
        sendSideBandwidthEstimation.updateReceiverEstimate(bitrateEstimatorAbsSendTime.latestEstimate)
        sendSideBandwidthEstimation.reportPacketArrived(now.toEpochMilli(), previouslyReportedLost)
        pktRecvCnt++
    }

    override fun doProcessPacketLoss(now: Instant, sendTime: Instant?, seq: Int) {
        sendSideBandwidthEstimation.reportPacketLost(now.toEpochMilli())
        pktLossCnt++
    }

    fun clear_stats() {
        rttCnt = 0
        pktRecvCnt = 0
        pktLossCnt = 0
    }
    override fun doFeedbackComplete(now: Instant) {
        if(Instant.now().toEpochMilli() - lastUpdateBwe.toEpochMilli() > updateBwePeriodMs) {
            sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
            val epID = diagnosticContext["endpoint_id"]
            // prevent divide by 0
            val json = arrayOf(epID, Instant.now().toEpochMilli(), bitrateEstimatorAbsSendTime.incomingBitrate.getRateBps(), pktLossCnt/(pktLossCnt + pktRecvCnt + 0.001), sendSideBandwidthEstimation.rttMs, sqrt(bitrateEstimatorAbsSendTime.noiseVar))
            post(url = munoStatsRestEp, json = json)

            latestBwe = sendSideBandwidthEstimation.latestEstimate.bps
            reportBandwidthEstimate(now, latestBwe)
            lastUpdateBwe = Instant.now()

            clear_stats()
        }
    }

    override fun doRttUpdate(now: Instant, newRtt: Duration) {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis())
        sendSideBandwidthEstimation.onRttUpdate(newRtt)
    }

    override fun getCurrentBw(now: Instant): Bandwidth {
        return latestBwe
    }

    override fun getStats(now: Instant): StatisticsSnapshot = StatisticsSnapshot(
        "GoogleCcEstimator", getCurrentBw(now)
    ).apply {
        addNumber("incomingEstimateExpirations", bitrateEstimatorAbsSendTime.incomingEstimateExpirations)
        addNumber("latestDelayEstimate", sendSideBandwidthEstimation.latestREMB)
        addNumber("latestLossFraction", sendSideBandwidthEstimation.latestFractionLoss / 256.0)
        with(sendSideBandwidthEstimation.statistics) {
            update(now.toEpochMilli())
            addNumber("lossDegradedMs", lossDegradedMs)
            addNumber("lossFreeMs", lossFreeMs)
            addNumber("lossLimitedMs", lossLimitedMs)
        }
    }

    override fun reset() {
        initBw = defaultInitBw
        minBw = GoogleCcEstimatorConfig.minBw
        maxBw = GoogleCcEstimatorConfig.maxBw

        bitrateEstimatorAbsSendTime.reset()
        sendSideBandwidthEstimation.reset(initBw.bps.toLong())

        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
    }
}
