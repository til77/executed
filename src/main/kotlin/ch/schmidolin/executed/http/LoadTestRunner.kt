package ch.schmidolin.executed.http

import ch.schmidolin.executed.model.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class LoadTestRunner(private val executor: HttpExecutor) {

    val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    fun runLoadTest(
        spec: HttpRequestSpec,
        documentVariables: Map<String, String>,
        config: LoadTestConfig,
        onProgress: (LoadTestStatistics, HttpResponseResult?) -> Unit
    ): LoadTestStatistics {
        isCancelled.set(false)

        val totalRequests = config.totalRequests.coerceAtLeast(1)
        val parallelism = config.parallelism.coerceIn(1, totalRequests)
        val rampUpMs = (config.rampUpSeconds.coerceAtLeast(0.0) * 1000.0)

        val resolvedUrl = executor.resolvePreviewUrl(spec, documentVariables)
        val sharedClient = if (config.reuseConnectionPool) {
            executor.buildClientForSpec(spec, maxConcurrency = parallelism * 2)
        } else {
            null
        }

        val samples = CopyOnWriteArrayList<LoadSampleResult>()
        val nextRequestCounter = AtomicInteger(0)
        val activeThreads = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        var firstResponse: HttpResponseResult? = null
        val firstResponseLock = Any()

        val testStartNs = System.nanoTime()
        val lastProgressNs = AtomicLong(0L)
        val progressLock = Any()

        // Emit initial 0-completed snapshot
        onProgress(
            computeStatistics(
                config = config,
                spec = spec,
                resolvedUrl = resolvedUrl,
                totalPlanned = totalRequests,
                samples = emptyList(),
                activeThreads = 0,
                elapsedMs = 0.1,
                totalBytes = 0L,
                isFinished = false,
                isCancelled = false
            ),
            null
        )

        val pool = Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "executed-load-worker").apply { isDaemon = true }
        }

        try {
            val futures = (0 until parallelism).map { workerIdx ->
                pool.submit {
                    // Ramp-up delay for threads after the first one
                    if (rampUpMs > 0.0 && parallelism > 1 && workerIdx > 0) {
                        val workerDelayMs = ((workerIdx.toDouble() / (parallelism - 1).toDouble()) * rampUpMs).toLong()
                        var waited = 0L
                        while (waited < workerDelayMs && !isCancelled.get()) {
                            val step = min(50L, workerDelayMs - waited)
                            Thread.sleep(step)
                            waited += step
                        }
                    }

                    if (isCancelled.get()) return@submit

                    activeThreads.incrementAndGet()
                    try {
                        while (!isCancelled.get()) {
                            val reqIdx = nextRequestCounter.incrementAndGet()
                            if (reqIdx > totalRequests) break

                            val reqStartNs = System.nanoTime()
                            val startOffsetMs = (reqStartNs - testStartNs) / 1_000_000.0

                            val result = executor.execute(
                                spec = spec,
                                documentVariables = documentVariables,
                                sharedClient = sharedClient,
                                saveFiles = (reqIdx == 1)
                            )

                            val reqEndNs = System.nanoTime()
                            val completedOffsetMs = (reqEndNs - testStartNs) / 1_000_000.0

                            synchronized(firstResponseLock) {
                                if (firstResponse == null) {
                                    firstResponse = result
                                }
                            }

                            val payloadLen = result.bodyBytes.size
                            totalBytes.addAndGet(payloadLen.toLong())

                            val sample = LoadSampleResult(
                                requestIndex = reqIdx,
                                threadId = workerIdx + 1,
                                startOffsetMs = startOffsetMs,
                                completedOffsetMs = completedOffsetMs,
                                statusCode = result.statusCode,
                                isSuccess = result.isSuccess,
                                payloadBytes = payloadLen,
                                timings = result.timings,
                                errorMessage = result.errorMessage,
                                responseResult = result
                            )
                            samples.add(sample)

                            // Throttle UI progress callbacks to ~15-20 updates/sec or on last sample
                            val nowNs = System.nanoTime()
                            val prevNs = lastProgressNs.get()
                            val completedNow = samples.size
                            if (completedNow == totalRequests || completedNow == 1 || (nowNs - prevNs >= 55_000_000L && lastProgressNs.compareAndSet(prevNs, nowNs))) {
                                synchronized(progressLock) {
                                    val elapsedMs = ((System.nanoTime() - testStartNs) / 1_000_000.0).coerceAtLeast(1.0)
                                    val snapshot = samples.toList()
                                    val stats = computeStatistics(
                                        config = config,
                                        spec = spec,
                                        resolvedUrl = resolvedUrl,
                                        totalPlanned = totalRequests,
                                        samples = snapshot,
                                        activeThreads = activeThreads.get(),
                                        elapsedMs = elapsedMs,
                                        totalBytes = totalBytes.get(),
                                        isFinished = false,
                                        isCancelled = isCancelled.get()
                                    )
                                    onProgress(stats, firstResponse)
                                }
                            }

                            if (config.delayBetweenRequestsMs > 0L && !isCancelled.get()) {
                                Thread.sleep(config.delayBetweenRequestsMs)
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        activeThreads.decrementAndGet()
                    }
                }
            }

            for (f in futures) {
                try {
                    f.get()
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }

        val totalElapsedMs = ((System.nanoTime() - testStartNs) / 1_000_000.0).coerceAtLeast(1.0)
        val finalSamples = samples.sortedBy { it.requestIndex }
        val finalStats = computeStatistics(
            config = config,
            spec = spec,
            resolvedUrl = resolvedUrl,
            totalPlanned = totalRequests,
            samples = finalSamples,
            activeThreads = 0,
            elapsedMs = totalElapsedMs,
            totalBytes = totalBytes.get(),
            isFinished = true,
            isCancelled = isCancelled.get()
        )
        onProgress(finalStats, firstResponse)
        return finalStats
    }

    companion object {
        fun computeStatistics(
            config: LoadTestConfig,
            spec: HttpRequestSpec,
            resolvedUrl: String,
            totalPlanned: Int,
            samples: List<LoadSampleResult>,
            activeThreads: Int,
            elapsedMs: Double,
            totalBytes: Long,
            isFinished: Boolean,
            isCancelled: Boolean
        ): LoadTestStatistics {
            val completed = samples.size
            val successes = samples.count { it.isSuccess }
            val failures = completed - successes
            val elapsedSec = (elapsedMs / 1000.0).coerceAtLeast(0.001)
            val rps = completed / elapsedSec
            val bytesPerSec = totalBytes / elapsedSec

            val totalDurations = samples.map { it.timings.totalDurationMs }
            val ttfbs = samples.map { it.timings.timeUntilServerAcceptsMs }
            val serverWaits = samples.map { it.timings.serverAcceptAndWaitDurationMs }
            val transfers = samples.map { it.timings.responseTransferDurationMs }
            val connects = samples.map {
                it.timings.dnsDurationMs + it.timings.tcpConnectDurationMs + it.timings.tlsHandshakeDurationMs
            }

            val statusCodes = LinkedHashMap<Int, Int>()
            val errorMap = LinkedHashMap<String, Int>()
            for (s in samples) {
                statusCodes[s.statusCode] = (statusCodes[s.statusCode] ?: 0) + 1
                if (s.errorMessage != null) {
                    errorMap[s.errorMessage] = (errorMap[s.errorMessage] ?: 0) + 1
                }
            }

            return LoadTestStatistics(
                config = config,
                requestSpec = spec,
                resolvedUrl = resolvedUrl,
                totalPlanned = totalPlanned,
                completedCount = completed,
                successCount = successes,
                failureCount = failures,
                activeThreads = activeThreads,
                elapsedDurationMs = elapsedMs,
                requestsPerSecond = rps,
                throughputBytesPerSecond = bytesPerSec,
                totalBytesReceived = totalBytes,
                totalDurationStats = computeMetricStats(totalDurations),
                ttfbStats = computeMetricStats(ttfbs),
                serverWaitStats = computeMetricStats(serverWaits),
                transferStats = computeMetricStats(transfers),
                connectStats = computeMetricStats(connects),
                statusCodeCounts = statusCodes.toSortedMap(),
                errorCounts = errorMap,
                histogram = computeHistogram(totalDurations, bucketCount = 10),
                samples = samples,
                isFinished = isFinished,
                isCancelled = isCancelled
            )
        }

        fun computeMetricStats(values: List<Double>): MetricStats {
            if (values.isEmpty()) {
                return MetricStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
            val sorted = values.sorted()
            val minVal = sorted.first()
            val maxVal = sorted.last()
            val mean = sorted.average()
            val variance = sorted.map { (it - mean) * (it - mean) }.average()
            val stdDev = sqrt(variance)

            fun percentile(p: Double): Double {
                if (sorted.size == 1) return sorted[0]
                val rank = (p / 100.0) * (sorted.size - 1)
                val low = rank.toInt().coerceIn(0, sorted.size - 1)
                val high = ceil(rank).toInt().coerceIn(0, sorted.size - 1)
                val weight = rank - low
                return sorted[low] * (1.0 - weight) + sorted[high] * weight
            }

            val p50 = percentile(50.0)
            return MetricStats(
                minMs = minVal,
                maxMs = maxVal,
                meanMs = mean,
                medianMs = p50,
                stdDevMs = stdDev,
                p50Ms = p50,
                p75Ms = percentile(75.0),
                p90Ms = percentile(90.0),
                p95Ms = percentile(95.0),
                p99Ms = percentile(99.0)
            )
        }

        fun computeHistogram(values: List<Double>, bucketCount: Int = 10): List<HistogramBucket> {
            if (values.isEmpty()) return emptyList()
            val minVal = values.minOrNull() ?: 0.0
            val maxVal = values.maxOrNull() ?: 0.0
            val effectiveBuckets = bucketCount.coerceIn(4, 20)

            if (maxVal - minVal < 0.01) {
                return listOf(
                    HistogramBucket(
                        rangeLabel = String.format("%.1f ms", minVal),
                        minMs = minVal,
                        maxMs = maxVal,
                        count = values.size,
                        fraction = 1.0
                    )
                )
            }

            val step = (maxVal - minVal) / effectiveBuckets.toDouble()
            val counts = IntArray(effectiveBuckets)
            for (v in values) {
                val idx = ((v - minVal) / step).toInt().coerceIn(0, effectiveBuckets - 1)
                counts[idx]++
            }

            return (0 until effectiveBuckets).map { i ->
                val bMin = minVal + i * step
                val bMax = if (i == effectiveBuckets - 1) maxVal else minVal + (i + 1) * step
                val c = counts[i]
                HistogramBucket(
                    rangeLabel = String.format("%.0f–%.0f ms", bMin, bMax),
                    minMs = bMin,
                    maxMs = bMax,
                    count = c,
                    fraction = c.toDouble() / max(1, values.size)
                )
            }
        }
    }
}
