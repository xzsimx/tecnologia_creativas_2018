# Protocolo de control de los OnePlus Buds 4 (ingeniería inversa)

OnePlus no publica cómo su app habla con los audífonos. Los Buds 4 usan el mismo protocolo
que OPPO usa en **HeyMelody** (OPPO, OnePlus y realme comparten firmware). Lo que sigue
reúne la ingeniería inversa publicada por Gadgetbridge, BudsControl (macOS),
oppo-pods (Noctalia) y RawS-Music. Contrasté las fuentes entre sí; los tests de
`app/src/test` fijan estos bytes.

## 1. Transporte

| | |
|---|---|
| Capa | Bluetooth clásico, **RFCOMM** (puerto serie), no BLE |
| Servicio SDP | `0000079a-d102-11e1-9b23-00025b00a5a5` (en los Buds 4 queda en el **canal 15**) |
| Alternativo | `00001107-d102-11e1-9b23-00025b00a5a5` (otras generaciones), SPP `0x1101` (algunos realme) |
| Seguridad | Necesita que los audífonos estén **emparejados**. No pide autenticación extra. |

El canal RFCOMM es independiente del audio (A2DP / LE Audio): abrirlo no corta la música.

## 2. Trama

Todo va en *little endian*:

```
AA  LEN  00 00  CMDlo CMDhi  SEQ  PLENlo PLENhi  PAYLOAD…
```

| Campo | Tamaño | Significado |
|---|---|---|
| `AA` | 1 | preámbulo |
| `LEN` | 1 (LEB128) | bytes que siguen tras este campo = `PLEN + 7` |
| `00 00` | 2 | reservado (`04 00` en algunos realme) |
| `CMD` | 2 | comando. La respuesta usa el mismo con el bit 15 encendido: `0x0404 → 0x8404` |
| `SEQ` | 1 | número de secuencia; la respuesta lo repite |
| `PLEN` | 2 | largo del payload |

## 3. Comandos usados por el widget

| CMD | Nombre | Payload enviado | Respuesta |
|---|---|---|---|
| `0x0103` | Product ID | — | `00 b1 b2 b3` → id `b3b2b1` (Buds 4 = **`065414`**) |
| `0x0106` | Batería | — | `00 n (id nivel)*`; id 1 = izq., 2 = der., 3 = estuche; bit 7 del nivel = cargando |
| `0x010C` | Leer modo de ruido | `01 01` | `… 01 01 <máscara LE>` |
| `0x0404` | **Cambiar modo de ruido** | `01 01 <máscara LE>` | ack `0x8404` |
| `0x0204` | Push del audífono | (lo envía él) | 1.er byte = tipo: 01 batería, 02 uso, 03 modo de ruido… |
| `0x0205` | Suscribirse a pushes | `03 01 02 03` | — |

## 4. La máscara del modo de ruido

El modo se elige encendiendo **un solo bit** de una máscara. Qué significa cada bit depende
del modelo. Para los **OnePlus Buds 4**:

| Bit | Máscara (payload completo) | Modo |
|---|---|---|
| 0 | `01 01 01` | **Normal** (ANC apagado) |
| 2 | `01 01 04` | **Transparencia** |
| 4 | `01 01 10` | ANC · Máxima *(lo que usa el widget por defecto)* |
| 5 | `01 01 20` | ANC · Moderada |
| 6 | `01 01 40` | ANC · Suave |
| 7 | `01 01 80` | ANC · Inteligente |
| 11 | `01 01 00 08` | ANC · Adaptativa (solo Buds 4) |

Detalle: al **leer** el modo, los Buds 4 informan la transparencia en el **bit 8**
(`… 01 01 00 01`), aunque se active con el bit 2.

### Ejemplo: tramas completas (seq = 1)

```
Transparencia  AA 0A 00 00 04 04 01 03 00 01 01 04
ANC Máxima     AA 0A 00 00 04 04 01 03 00 01 01 10
Normal         AA 0A 00 00 04 04 01 03 00 01 01 01
Leer modo      AA 09 00 00 0C 01 01 02 00 01 01
```

## 5. Verificarlo tú mismo en el Nothing Phone (2a)

### Opción A: consola de la app

La app trae una **consola de protocolo**. Escribe `CMD payload` en hex:

- `010C 0101` lee el modo actual
- `0404 010104` pone transparencia
- `0103` lee el product ID

Cada byte enviado y recibido aparece en pantalla.

### Opción B: captura HCI de la app oficial

1. En **Ajustes → Sistema → Opciones de desarrollador**, activa *Registro de Bluetooth HCI snoop*.
2. Apaga y enciende el Bluetooth. Luego cambia de modo varias veces desde la app oficial
   (HeyMelody / OnePlus Buds).
3. Genera un informe de errores con `adb bugreport bug.zip`. El log queda en
   `FS/data/log/bt/btsnoop_hci.log`.
4. Ábrelo en Wireshark con el filtro `btrfcomm && btrfcomm.channel == 15`. Cada cambio de
   modo debería aparecer como una trama `aa 0a 00 00 04 04 …`.

Si un firmware nuevo cambiara los bits, basta con editar `Catalog.BUDS_4` en
`protocol/HeyMelody.kt`.

## Créditos

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), `service/devices/oppo` (AGPL-3.0): formato de trama, batería, firmware.
- [BudsControl](https://github.com/amarsakore/BudsControl) (MIT): comandos de ruido, UUID y canal de los Buds 4.
- [oppo-pods](https://github.com/noctalia-dev/community-plugins/tree/main/oppo-pods) (MIT): disposición de bits probada en audífonos reales.
- [RawS-Music](https://github.com/QFDY-GZC/RawS-Music) (Apache-2.0): perfiles de dispositivos HeyMelody.

Este proyecto no copia código de esos repositorios. Reimplementa el protocolo a partir de
los datos que documentan.
