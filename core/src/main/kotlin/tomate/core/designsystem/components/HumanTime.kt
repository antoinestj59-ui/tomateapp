package tomate.core.designsystem.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Horodatage lisible, comme dans les maquettes : « à l'instant », « il y a 12 min »,
 * « il y a 3 h », « Hier », « lun. », puis la date.
 *
 * Aucune valeur inventée : le calcul part de l'horloge de l'appareil et de l'instant réel
 * du contenu. Un horodatage absent (0) reste affiché « — ».
 */
fun humanTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return "—"
    val delta = now - millis
    if (delta < 0) return "à l'instant"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        else -> {
            val then = Calendar.getInstance().apply { timeInMillis = millis }
            val today = Calendar.getInstance().apply { timeInMillis = now }
            val days = dayDistance(then, today)
            when {
                days <= 1 -> "Hier"
                days < 7 -> dayName(then)
                else -> dayAndMonth(then)
            }
        }
    }
}

/** Heure courte (« 9:40 »), utilisée à droite des conversations et des messages. */
fun clockTime(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("H:mm", Locale.FRANCE).format(Date(millis))

private fun dayDistance(then: Calendar, now: Calendar): Int {
    val a = then.clone() as Calendar
    val b = now.clone() as Calendar
    a.set(Calendar.HOUR_OF_DAY, 0)
    a.set(Calendar.MINUTE, 0)
    a.set(Calendar.SECOND, 0)
    b.set(Calendar.HOUR_OF_DAY, 0)
    b.set(Calendar.MINUTE, 0)
    b.set(Calendar.SECOND, 0)
    return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
}

private fun dayName(calendar: Calendar): String {
    val name = SimpleDateFormat("EEE", Locale.FRANCE).format(calendar.time)
    return name.replaceFirstChar { it.uppercase() }
}

private fun dayAndMonth(calendar: Calendar): String =
    SimpleDateFormat("d MMM", Locale.FRANCE).format(calendar.time)

/** Nombre compact façon maquette : 1 240 → « 1,2 k », 12 400 → « 12,4 k ». */
fun compactCount(value: Int): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(Locale.FRANCE, "%.1f k", value / 1_000f).replace(".0", "")
    else -> String.format(Locale.FRANCE, "%.1f M", value / 1_000_000f).replace(".0", "")
}
