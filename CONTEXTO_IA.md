# CONTEXTO_IA — Cachito Android (v2.43 — Versión Final Rama v2)

> Archivo de continuidad para retomar el proyecto en una futura **v3.0** sin perder el hilo.

## 1. Reglas de trabajo del usuario
- **Idioma y tono:** Castellano rioplatense (voseo natural), directo y conciso.
- **Confirmación antes de código/ZIP:** NO generar código ni archivos ZIP hasta recibir un **GO** explícito.
- **Formato de entrega:** Archivo `.zip` descargable + script PowerShell (`subir_vX.XX.ps1`) + `CONTEXTO_IA.md` actualizado.
- **Dispositivo objetivo:** ZTE Nubia Neo 3 GT 5G (12 GB RAM, Android 16).
- **Repositorio:** `https://github.com/gabrielsemiz-a11y/cacho_android` (carpeta local en PC: `E:\cachito-android-v2.0`).

## 2. Stack técnico exacto
- **Lenguaje / UI:** Kotlin 2.3.21 + Jetpack Compose (BOM `2025.02.00`, Material 3 `1.3.1`).
- **Build:** AGP 8.9.1, Gradle 8.11.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK 21.
- **CI/CD:** GitHub Actions (`.github/workflows/build-apk.yml`) compila `assembleDebug` y sube `Cachito-Android-v2.43-APK`.
- **IA & Visión:**
  - `com.google.mediapipe:tasks-genai:0.10.22` (Motor Local Offline `.task` con `PreferredBackend.GPU` + respaldo automático a `CPU` LiteRT).
  - `com.google.mlkit:genai-prompt:1.0.0-beta4` (AICore — oculto automáticamente si el hardware no lo soporta).
  - **Escáner CameraX HD + Modo Fotocopia propio:** Selección automática del sensor trasero de mayor resolución (Camera2 interop) + botón `🔄 Lente` + toque para enfocar macro + linterna + recorte ajustable + filtro Fotocopia B/N alto contraste + `PdfDocument` HD (hasta 2480 px).
  - `com.google.mlkit:text-recognition:16.0.1` + `object-detection:17.0.2`.
  - OkHttp `4.12.0` (OpenRouter, Google AI Studio con Search Grounding, Groq Cloud, DuckDuckGo Web Search y Google News AR RSS).

## 3. Cambios de cierre en v2.43 (Versión Final v2)
- **Búsqueda web general + noticias en vivo para TODOS los motores (incluido IA Local Offline):**
  - Consulta en vivo **Google News AR** (noticias/actualidad) y **DuckDuckGo Web** (pasajes, precios, productos, búsquedas generales).
  - Para el modelo local (`LOCAL_OFFLINE`) inyecta un resumen compacto (top 3 resultados) cuando hay señal, y si estás sin internet sigue funcionando offline sin error.
- **Escáner CameraX HD con Selector de Lente y Modo Fotocopia:**
  - Detecta todos los lentes traseros vía Camera2, elige por defecto el de **mayor resolución (MP)** y agrega botón **`🔄 Lente`** en el visor.
  - Paso de vista previa por página con **recorte ajustable** (márgenes con recuadro en vivo) y switch **Filtro Fotocopia (B/N alto contraste)** antes de armar el PDF multipágina.
- **Ícono de Don Cacho en el launcher:** `subir_v2.43.ps1` extrae automáticamente el primer cuadro de `cacho_neutro.gif` a PNG adaptativo con margen seguro y limpia íconos legacy de `mipmap-*`.

## 4. Pendientes para futura versión 3.0
- **Firma fija del APK (`cachito.keystore` + GitHub Secrets):** Para instalar actualizaciones encima sin desinstalar ni perder datos/modelos.
- Ver detalle completo en `CACHITO_V3_CONTEXTO_Y_ROADMAP.md`.
