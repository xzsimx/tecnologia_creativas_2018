# En la versión publicada se eliminan los logs de depuración: no dejan en el registro del
# sistema la dirección Bluetooth ni las tramas, y ahorran trabajo en cada conexión.
# (La consola de "Avanzado" usa su propia bitácora en memoria, así que sigue funcionando.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
