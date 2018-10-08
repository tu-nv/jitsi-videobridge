/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.util

import org.jitsi.util.RTPUtils
import toUInt
import unsigned.toUint
import java.nio.ByteBuffer
import java.time.Duration


/**
 * Returns true if getting to [otherSeqNum] from the current sequence number involves wrapping around
 */
infix fun Int.rolledOverTo(otherSeqNum: Int): Boolean {
    /**
     * If, according to [RTPUtils.isOlderSequenceNumberThan], [this] is older than [otherSeqNum] and
     * yet [otherSeqNum] is less than [this], then we wrapped around to get from [this] to
     * [otherSeqNum]
     */
    return RTPUtils.isOlderSequenceNumberThan(this, otherSeqNum) && otherSeqNum < this
}

/**
 * Returns true if [this] is sequentially after [otherSeqNum], according to the rules of RTP sequence
 * numbers
 */
infix fun Int.isNextAfter(otherSeqNum: Int): Boolean = RTPUtils.getSequenceNumberDelta(this, otherSeqNum) == -1

/**
 * Returns true if the RTP sequence number represented by [this] represents a more recent RTP packet than the one
 * represented by [otherSeqNum]
 */
infix fun Int.isNewerThan(otherSeqNum: Int): Boolean = RTPUtils.isOlderSequenceNumberThan(otherSeqNum, this)

/**
 * Return the amount of packets between the RTP sequence number represented by [this] and the [otherSeqNum].  NOTE:
 * [this] must represent an older RTP sequence number than [otherSeqNum] (TODO: validate/enforce that)
 */
infix fun Int.numPacketsTo(otherSeqNum: Int): Int = -RTPUtils.getSequenceNumberDelta(this, otherSeqNum) - 1

class RtpUtils {
    companion object {
        /**
         * Given [timestampMs] (a timestamp in milliseconds), convert it to an NTP timestamp represented
         * as a pair of ints: the first one being the most significant word and the second being the least
         * significant word
         * http://lists.ntp.org/pipermail/questions/2006-July/010866.html
         * TODO: have i implemented this right?
         */
        private fun millisToNtpTimestampMswLsw(timestampMs: Long): Pair<Int, Int> {
            val seconds = Duration.ofMillis(timestampMs).seconds + 2208988800
            val remainingMillis = timestampMs - Duration.ofSeconds(seconds).toMillis()

            val msw = seconds.toUInt()
            val lsw = (remainingMillis / 1000 * 4294967296).toUInt()
            return Pair(msw, lsw)
        }

        fun millisToNtpTimestamp(timestampMs: Long): Long {
            val (msw, lsw) = millisToNtpTimestampMswLsw(timestampMs)
            val buf = ByteBuffer.allocate(8)
            buf.putInt(msw)
            buf.putInt(lsw)
            buf.flip()

            return buf.getLong()
        }

        fun convertRtpTimestampToMs(rtpTimestamp: Int, ticksPerSecond: Double): Int {
            return ((rtpTimestamp / ticksPerSecond) * 1000).toInt()
        }
    }
}
