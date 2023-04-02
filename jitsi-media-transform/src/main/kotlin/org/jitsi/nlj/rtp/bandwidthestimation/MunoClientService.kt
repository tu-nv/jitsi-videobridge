package org.jitsi.nlj.rtp.bandwidthestimation

import io.grpc.ManagedChannel
import org.jitsi.muno.munoStats
import org.jitsi.muno.MunoGrpcKt.MunoCoroutineStub
import java.io.Closeable
import java.util.concurrent.TimeUnit

class MunoClientService private constructor(private val channel: ManagedChannel) : Closeable {
    companion object {
        @Volatile
        private lateinit var instance: MunoClientService

        fun getService(): MunoClientService {
            synchronized(this) {
                if (::instance.isInitialized) {
                    return instance
                }
                throw NullPointerException("MunoClientService is not initialized yet!")
            }
        }

        fun init(channel: ManagedChannel) {
            synchronized(this) {
                if (!::instance.isInitialized) {
                    instance = MunoClientService(channel)
                }
            }
        }
    }

    private val stub: MunoCoroutineStub = MunoCoroutineStub(channel)

    suspend fun munoGetBwe(epID: String, gccBwe: Float, lossRate: Float, delayGrad: Float) : Float {
        val request = munoStats {
            this.epID = epID
            this.gccBwe = gccBwe
            this.lossRate = lossRate
            this.delayGrad = delayGrad
        }
        val response = stub.sendStats(request)
//        println("Received: ${response.bwe}")
        return response.bwe
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
