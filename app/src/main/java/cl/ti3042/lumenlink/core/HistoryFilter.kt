package cl.ti3042.lumenlink.core

enum class HistoryFilter(val label: String) {
    ALL("Todas"), LIGHT_ON("Luz ON"), LIGHT_OFF("Luz OFF"), AUTOMATIC("Auto"), MANUAL("Manual");
    fun matches(r: Reading): Boolean = when (this) {
        ALL -> true
        LIGHT_ON -> r.lightOn
        LIGHT_OFF -> !r.lightOn
        AUTOMATIC -> r.automatic
        MANUAL -> !r.automatic
    }
}
