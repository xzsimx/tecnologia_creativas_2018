package cl.tecnocreativas.budswidget

import android.content.Context
import android.content.SharedPreferences
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.Catalog
import cl.tecnocreativas.budswidget.protocol.DeviceModel
import cl.tecnocreativas.budswidget.protocol.NoiseMode

/** Último estado conocido de los audífonos, compartido por widget, tile y la app. */
class BudsState(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("buds_state", Context.MODE_PRIVATE)

    var address: String?
        get() = p.getString("address", null)
        set(v) = p.edit().putString("address", v).apply()

    var deviceName: String?
        get() = p.getString("device_name", null)
        set(v) = p.edit().putString("device_name", v).apply()

    var productId: String?
        get() = p.getString("product_id", null)
        set(v) = p.edit().putString("product_id", v).apply()

    val model: DeviceModel get() = Catalog.model(productId)

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
}
