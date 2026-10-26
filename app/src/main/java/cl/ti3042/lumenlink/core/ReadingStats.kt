package cl.ti3042.lumenlink.core

data class ReadingStats(val count: Int, val avgDistanceCm: Double, val minDistanceCm: Double, val maxDistanceCm: Double,
    val avgLdrPercent: Double, val lightOnPercent: Double, val automaticPercent: Double) {
    companion object {
        fun of(rows: List<Reading>): ReadingStats? {
            if (rows.isEmpty()) return null
            val n = rows.size.toDouble()
            return ReadingStats(rows.size, rows.sumOf { it.distanceCm } / n, rows.minOf { it.distanceCm },
                rows.maxOf { it.distanceCm }, rows.sumOf { it.ldrPercent } / n,
                rows.count { it.lightOn } * 100.0 / n, rows.count { it.automatic } * 100.0 / n)
        }
    }
}
