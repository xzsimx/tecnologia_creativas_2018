package cl.tecnocreativas.budswidget

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import cl.tecnocreativas.budswidget.bt.BudsClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Escucha cuando los audífonos se conectan/desconectan (ACL) para mantener el widget al día.
 * ACTION_ACL_* está exento de las restricciones de broadcasts implícitos, así que funciona
 * declarado en el manifest aunque la app no esté abierta.
 */
class BluetoothEventsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (!BudsActions.hasPermission(ctx)) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }) ?: return

        val state = BudsState(ctx)
        val pinned = state.pinned
        val ours = if (pinned != null) device.address == pinned else BudsClient.target(device) != null
        if (!ours) return
        val current = device.address == state.address

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Si son otros audífonos compatibles, el refresco cambia a ellos solo.
                if (current) state.linkUp = true
                state.error = null
                BudsActions.notifyUi(ctx)
                // Los audífonos tardan un poco en levantar sus servicios tras conectar.
                val result = goAsync()
                val finished = AtomicBoolean(false)
                val finish = { if (finished.compareAndSet(false, true)) result.finish() }
                val main = Handler(Looper.getMainLooper())
                main.postDelayed({ BudsActions.refresh(ctx) { finish() } }, 3000)
                main.postDelayed({ finish() }, 9000) // nunca pasar el límite del sistema
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (current) {
                state.linkUp = false
                BudsActions.notifyUi(ctx)
            }
        }
    }
}
