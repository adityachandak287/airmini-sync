package com.airmini.sync

import org.json.JSONObject
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MetricStats(
    val min: Double,
    val avg: Double,
    val median: Double,
    val p95: Double,
    val max: Double
)

data class TherapyStats(
    val firstDate: String,
    val lastDate: String,
    val totalUsageDurationFormatted: String,
    val maskSessionsCount: Int,
    val respiratoryEventsCount: Int,
    val avgApneaDurationSeconds: Double,
    val eventCountsBreakdown: Map<String, Int>,
    val pressureStats: MetricStats?,
    val leakStats: MetricStats?
)

object TherapyStatsCalculator {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun computeStats(data: JSONObject): TherapyStats? {
        val allInstants = mutableListOf<Instant>()

        // 1. Gather all timestamps & events
        val usageEvents = mutableListOf<JSONObject>()
        val usageEventsArray = data.optJSONArray("UsageEvents-TherapyStatusEvent")
        if (usageEventsArray != null) {
            for (i in 0 until usageEventsArray.length()) {
                val item = usageEventsArray.getJSONObject(i)
                val events = item.optJSONArray("events") ?: continue
                for (j in 0 until events.length()) {
                    val ev = events.getJSONObject(j)
                    usageEvents.add(ev)
                    parseInstant(ev.optString("time"))?.let { allInstants.add(it) }
                }
            }
        }

        val respEvents = mutableListOf<JSONObject>()
        val respEventsArray = data.optJSONArray("TherapyEvents-RespiratoryEvent")
        if (respEventsArray != null) {
            for (i in 0 until respEventsArray.length()) {
                val item = respEventsArray.getJSONObject(i)
                val events = item.optJSONArray("events") ?: continue
                for (j in 0 until events.length()) {
                    val ev = events.getJSONObject(j)
                    respEvents.add(ev)
                    parseInstant(ev.optString("time"))?.let { allInstants.add(it) }
                }
            }
        }

        val pressureValues = mutableListOf<Double>()
        val pressureArray = data.optJSONArray("TherapyOneMinutePeriodic-InspiratoryPressure")
        if (pressureArray != null) {
            for (i in 0 until pressureArray.length()) {
                val item = pressureArray.getJSONObject(i)
                val periodic = item.optJSONObject("periodic") ?: continue
                parseInstant(periodic.optString("startTime"))?.let { allInstants.add(it) }
                val values = periodic.optJSONArray("values") ?: continue
                for (j in 0 until values.length()) {
                    val v = values.optDouble(j)
                    if (v > 0) pressureValues.add(v)
                }
            }
        }

        val leakValues = mutableListOf<Double>()
        val leakArray = data.optJSONArray("TherapyOneMinutePeriodic-Leak")
        if (leakArray != null) {
            for (i in 0 until leakArray.length()) {
                val item = leakArray.getJSONObject(i)
                val periodic = item.optJSONObject("periodic") ?: continue
                parseInstant(periodic.optString("startTime"))?.let { allInstants.add(it) }
                val values = periodic.optJSONArray("values") ?: continue
                for (j in 0 until values.length()) {
                    val v = values.optDouble(j)
                    if (v >= 0) leakValues.add(v)
                }
            }
        }

        if (allInstants.isEmpty()) return null

        val firstDateInstant = allInstants.minOrNull() ?: return null
        val lastDateInstant = allInstants.maxOrNull() ?: return null

        // 2. Compute Therapy Sessions (MaskOn -> MaskOff)
        val sortedUsage = usageEvents.filter { it.optString("time").isNotEmpty() }
            .sortedBy { parseInstant(it.optString("time")) }

        var totalUsageSeconds = 0L
        var currentMaskOn: Instant? = null
        var maskOnsCount = 0

        for (ev in sortedUsage) {
            val eventType = ev.optString("event")
            val eventTime = parseInstant(ev.optString("time")) ?: continue

            if (eventType == "MaskOn") {
                currentMaskOn = eventTime
                maskOnsCount++
            } else if (eventType == "MaskOff" && currentMaskOn != null) {
                val diff = Duration.between(currentMaskOn, eventTime).seconds
                if (diff in 1..43199) { // filter out sessions longer than 12h
                    totalUsageSeconds += diff
                }
                currentMaskOn = null
            }
        }

        // 3. Respiratory Event Breakdown
        val eventCounts = mutableMapOf<String, Int>()
        var totalApneaDuration = 0.0
        var durCount = 0

        for (ev in respEvents) {
            val evType = ev.optString("event", "Unknown")
            eventCounts[evType] = eventCounts.getOrDefault(evType, 0) + 1
            if (ev.has("durationSeconds")) {
                totalApneaDuration += ev.optDouble("durationSeconds")
                durCount++
            }
        }

        // 4. Calculate Metric Stats (Min, Avg, P95, Max)
        val pressureStats = calculateMetricStats(pressureValues)
        val leakStats = calculateMetricStats(leakValues)

        val hours = totalUsageSeconds / 3600
        val minutes = (totalUsageSeconds % 3600) / 60
        val totalUsageFormatted = String.format(Locale.US, "%dh %dm", hours, minutes)

        return TherapyStats(
            firstDate = dateFormatter.format(firstDateInstant),
            lastDate = dateFormatter.format(lastDateInstant),
            totalUsageDurationFormatted = totalUsageFormatted,
            maskSessionsCount = maskOnsCount,
            respiratoryEventsCount = respEvents.size,
            avgApneaDurationSeconds = if (durCount > 0) totalApneaDuration / durCount else 0.0,
            eventCountsBreakdown = eventCounts,
            pressureStats = pressureStats,
            leakStats = leakStats
        )
    }

    private fun parseInstant(timeStr: String?): Instant? {
        if (timeStr.isNullOrEmpty()) return null
        return try {
            Instant.parse(timeStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateMetricStats(values: List<Double>): MetricStats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val avg = values.average()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        val p95Index = (sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)
        val p95 = sorted[p95Index]

        return MetricStats(min, avg, median, p95, max)
    }
}
