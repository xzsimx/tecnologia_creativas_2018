package cl.tecnocreativas.budswidget

import android.content.Context
import android.content.SharedPreferences
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.Battery
import cl.tecnocreativas.budswidget.protocol.Identity
import cl.tecnocreativas.budswidget.protocol.NoiseMode

/** Último estado conocido de los audífonos, compartido por widget, tile y la app. */
class BudsState(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("buds_state", Context.MODE_PRIVATE)

    /** Audífonos con los que se habló por última vez. */
    var address: String?
        get() = p.getString("address", null)
        set(v) = p.edit().putString("address", v).apply()

    /** Audífonos fijados a mano en Avanzado; null = automático (los conectados). */
    var pinned: String?
        get() = p.getString("pinned", null)
        set(v) = p.edit().putString("pinned", v).apply()

    var deviceName: String?
        get() = p.getString("device_name", null)
        set(v) = p.edit().putString("device_name", v).apply()

    /** Nombre del modelo identificado por protocolo ("" si no se conoce). */
    var modelName: String
        get() = p.getString("model_name", "").orEmpty()
        set(v) = p.edit().putString("model_name", v).apply()

    /** Niveles de cancelación que ofrece el modelo actual. */
    var ancLevels: List<AncLevel>
        get() = p.getString("anc_levels", null)?.split(',')
            ?.mapNotNull { runCatching { AncLevel.valueOf(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEVELS
        set(v) = p.edit().putString("anc_levels", v.joinToString(",") { it.name }).apply()

    /** true = audífonos de diadema: una sola batería (se guarda en [batteryLeft]). */
    var singleBattery: Boolean
        get() = p.getBoolean("single_battery", false)
        set(v) = p.edit().putBoolean("single_battery", v).apply()

    /** Id de modelo ya leído para unos audífonos (no se vuelve a preguntar). */
    fun productIdFor(address: String): String? = p.getString("pid_$address", null)

    fun saveIdentity(address: String, id: Identity) {
        p.edit().apply { id.id?.let { putString("pid_$address", it) } }
            .putString("model_name", id.name)
            .putString("anc_levels", id.ancLevels.joinToString(",") { it.name })
            .apply()
    }

    /** Pasa a otros audífonos: lo conocido de los anteriores deja de valer. */
    fun switchTo(address: String, name: String?) {
        if (address == this.address) {
            if (name != null) deviceName = name
            return
        }
        p.edit()
            .putString("address", address)
            .putString("device_name", name)
            .putString("model_name", "")
            .remove("anc_levels").remove("mode").remove("reported_level").remove("link_up")
            .putInt("bat_l", -1).putInt("bat_r", -1).putInt("bat_c", -1)
            .putBoolean("single_battery", false)
            .apply()
    }

    fun setBattery(b: Battery) {
        val single = b.single
        p.edit()
            .putBoolean("single_battery", single != null)
            .putInt("bat_l", single ?: b.left ?: -1)
            .putInt("bat_r", if (single != null) -1 else b.right ?: -1)
            .putInt("bat_c", if (single != null) -1 else b.case ?: -1)
            .apply()
    }

    /** Nombre para mostrar: el del modelo, si no el de Bluetooth. */
    val displayName: String get() = modelName.ifEmpty { deviceName ?: "Audífonos" }

    var mode: NoiseMode?
        get() = p.getString("mode", null)?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
        set(v) = p.edit().putString("mode", v?.name).apply()

    /** Nivel informado por los audífonos (si están en ANC). */
    var reportedLevel: AncLevel?
        get() = p.getString("reported_level", null)?.let { runCatching { AncLevel.valueOf(it) }.getOrNull() }
        set(v) = p.edit().putString("reported_level", v?.name).apply()

    /** Nivel que se envía al pulsar ANC (elegido en la app). */
    var ancLevel: AncLevel
        get() = p.getString("anc_level", null)?.let { runCatching { AncLevel.valueOf(it) }.getOrNull() } ?: AncLevel.MAX
        set(v) = p.edit().putString("anc_level", v.name).apply()

    /** Modo que se está aplicando (para pintarlo de inmediato en el widget). */
    var pending: NoiseMode?
        get() = p.getString("pending", null)?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
        set(v) = p.edit().putString("pending", v?.name).apply()

    var batteryLeft: Int
        get() = p.getInt("bat_l", -1)
        set(v) = p.edit().putInt("bat_l", v).apply()

    var batteryRight: Int
        get() = p.getInt("bat_r", -1)
        set(v) = p.edit().putInt("bat_r", v).apply()

    var batteryCase: Int
        get() = p.getInt("bat_c", -1)
        set(v) = p.edit().putInt("bat_c", v).apply()

    /** ¿El enlace ACL con los audífonos está activo? (null = desconocido) */
    var linkUp: Boolean?
        get() = if (p.contains("link_up")) p.getBoolean("link_up", false) else null
        set(v) = p.edit().apply { if (v == null) remove("link_up") else putBoolean("link_up", v) }.apply()

    var error: String?
        get() = p.getString("error", null)
        set(v) = p.edit().putString("error", v).apply()

    var updatedAt: Long
        get() = p.getLong("updated_at", 0)
        set(v) = p.edit().putLong("updated_at", v).apply()

    /** Modo a mostrar: el pendiente mientras se aplica, si no el último confirmado. */
    val shownMode: NoiseMode? get() = pending ?: mode

    companion object {
        val DEFAULT_LEVELS = listOf(AncLevel.MAX, AncLevel.MODERATE, AncLevel.MILD)
    }
}
