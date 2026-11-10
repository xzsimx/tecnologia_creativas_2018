# Buds: widget Nothing OS para OnePlus Buds 4

Widget para el **Nothing Phone (2a)** con Nothing OS 4. Cambia los **OnePlus Buds 4** entre
**cancelación de ruido**, **transparencia** y **normal** (ANC apagado) desde la pantalla de
inicio, sin abrir la app de OnePlus.

| Oscuro | Claro |
|---|---|
| ![widget oscuro](docs/preview_dark.png) | ![widget claro](docs/preview_light.png) |

## Qué incluye

- **Widget 4×2**: nombre, batería y los tres modos como botones circulares.
- **Widget 2×2**: el modo actual en un círculo. Cada toque alterna ANC → Transparencia → Normal.
- **Tile de Ajustes rápidos**: el mismo ciclo desde la cortina.
- **App**: batería L/R/estuche, control de ruido y nivel de ANC. La elección de audífonos y la
  consola de protocolo quedan escondidas en *Avanzado*.

![app](docs/preview_app.png)

## Diseño (como Nothing X)

- Solo lo esencial: nombre, batería y los tres modos. Sin textos de relleno.
- **Íconos de línea fina** (trazo de 1,5 dp, extremos redondeados), generados como vectores.
- **Matriz de puntos** solo para los números de batería, como en Nothing X.
- Botones circulares: gris cuando están inactivos y blanco con ícono negro cuando están
  activos (al revés en modo claro).
- Un único acento **rojo Nothing `#D71921`**: el punto de conexión.
- Tipografía del sistema. Las etiquetas de sección van en mono, en mayúsculas espaciadas.

`tools/preview.py` es la fuente única del diseño. Define la geometría de los íconos y genera
los vectores Android y los mockups de `docs/`.

## Cómo funciona (ingeniería inversa)

La app oficial habla con los audífonos por **RFCOMM** (puerto serie Bluetooth). Usa un
protocolo binario propio de OPPO/OnePlus llamado "HeyMelody". Para cambiar de modo basta una
trama de 12 bytes:

```
AA 0A 00 00 04 04 01 03 00 01 01 04   → transparencia
                 └cmd┘    └payload┘
```

El detalle completo está en **[PROTOCOLO.md](PROTOCOLO.md)**: formato de trama, comandos, bits
de cada modo y cómo verificarlo con un log HCI snoop. La implementación vive en
`app/src/main/java/.../protocol/HeyMelody.kt`, con tests en `app/src/test`.

También funciona con otros OnePlus que usan el mismo protocolo: Buds 3, Buds Pro 2/3,
Nord Buds 3/3 Pro/4/4 Pro y Buds Ace. La lista está en `Catalog`.

## Instalar

1. Descarga el APK desde **GitHub → Actions → "Buds widget (APK)" → artefacto
   `buds-widget-apk`**, o compílalo tú (abajo).
2. Instálalo en el teléfono y acepta "instalar apps desconocidas".
3. Abre **Buds** y concede el permiso de *Dispositivos cercanos*.
4. Elige tus audífonos (aparecen primero si se llaman "OnePlus Buds 4") y el nivel de ANC.
5. Pulsa **Añadir widget a la pantalla de inicio**, o mantén presionado el escritorio →
   Widgets → Buds.
6. Opcional: en la cortina, entra al modo de edición y arrastra el tile **Buds**.

### Compilar

```bash
cd 3.Widget-Buds-Nothing
./gradlew testDebugUnitTest   # tests del protocolo
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Requiere JDK 17 y Android SDK 36. El APK de release se firma con la clave debug para poder
instalarlo directamente.

## Limitaciones y notas

- **No se probó con hardware real durante el desarrollo.** Los comandos y bits coinciden con
  BudsControl, que documenta pruebas con Buds 4 (canal 15 y transparencia informada en el
  bit 8), y con la disposición de oppo-pods. Si algo falla, la consola de la app muestra
  exactamente qué responden los audífonos.
- Cada acción abre el canal RFCOMM, envía el comando y lo cierra (≈ 0,5–1,5 s). No queda
  ningún servicio corriendo en segundo plano, así que no gasta batería.
- Si la app oficial de OnePlus/HeyMelody tiene el canal abierto en ese momento, la conexión
  puede fallar. Ciérrala o fuerza su detención.
- Si cambias el modo desde los audífonos (gesto de presión), el widget se actualiza cuando
  lo tocas, al reconectar los audífonos o cada 30 min.
- Proyecto educativo. No está afiliado a Nothing ni a OnePlus.
