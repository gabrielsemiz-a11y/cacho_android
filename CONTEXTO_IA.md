# CONTEXTO_IA — Cachito Android (v2.44 — Versión Estable Final Rama v2)

> Archivo de continuidad para retomar el proyecto en una futura **v3.0** sin perder el hilo.

## 1. Reglas de trabajo del usuario
- **Idioma y tono:** Castellano rioplatense (voseo natural), directo y conciso.
- **Confirmación antes de código/ZIP:** NO generar código ni archivos ZIP hasta recibir un **GO** explícito.
- **Formato de entrega:** Archivo `.zip` descargable + script PowerShell (`subir_vX.XX.ps1`) + `CONTEXTO_IA.md` actualizado.
- **Dispositivo objetivo:** ZTE Nubia Neo 3 GT 5G (12 GB RAM, Android 16).
- **Repositorio:** `https://github.com/gabrielsemiz-a11y/cacho_android` (carpeta local en PC: `E:\cachito-android-v2.0`).

## 2. Stack técnico exacto (v2.44)
- **Lenguaje / UI:** Kotlin 2.3.21 + Jetpack Compose (BOM `2025.02.00`, Material 3 `1.3.1`).
- **Build:** AGP 8.9.1, Gradle 8.11.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK 21.
- **CI/CD:** GitHub Actions (`.github/workflows/build-apk.yml`) descarga `efficientdet_lite0.tflite` a `app/src/main/assets/`, compila `assembleDebug` y sube `Cachito-Android-v2.44-APK`.
- **IA & Visión (limpio, sin dependencias duplicadas):**
  - `com.google.mediapipe:tasks-genai:0.10.22` (Motor Local Offline `.task` con `GPU` + respaldo automático `CPU` LiteRT).
  - `com.google.mediapipe:tasks-vision:0.10.26` (Radar de Objetos En Vivo con `efficientdet_lite0.tflite` y 80 etiquetas COCO en español + muestras propias).
  - `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0` (Botón opcional `✨ Google` dentro del escáner).
  - `com.google.mlkit:text-recognition:16.0.1` (OCR local de documentos).
  - `com.google.mlkit:genai-prompt:1.0.0-beta4` (AICore — oculto automáticamente si el hardware no lo soporta).
  - Escáner CameraX HD propio (selector automático del sensor de mayor MP + `🔄 Lente` + toque macro + linterna + recorte ajustable + filtro Fotocopia B/N).
  - OkHttp `4.12.0` (OpenRouter con Web Search nativo, Google AI Studio con Search Grounding, Groq Cloud, Google News AR RSS y DuckDuckGo Web).

## 3. Cambios incorporados en v2.44
- **Radar de Objetos en español:** Reemplazado el detector genérico de ML Kit por MediaPipe `ObjectDetector` (`efficientdet_lite0.tflite`) con las 80 clases COCO traducidas al español + muestras propias intactas.
- **Cascada automática entre motores:** Prueba primero exactamente tu motor y modelo elegido; si da error o devuelve `content: null`, salta automáticamente al siguiente motor configurado (`OpenRouter → Groq → Google AI Studio → IA Local Offline`) indicando cuál respondió como respaldo.
- **OpenRouter optimizado:** `↻ Ver` filtra por precio real (`pricing.prompt <= USD 0,50/M`) dejando solo modelos gratis y ultra-baratos (DeepSeek Flash, Inkling 2 Mini, Qwen, Gemini Flash, Llama) y descartando modelos caros (Opus/Sonnet/o3). Activa búsqueda web nativa (`plugins: [{"id":"web"}]`) con caída silenciosa si no hay saldo, y corrige el bug de `"null"` literal.
- **Google AI Studio limpio:** `↻ Ver` descarta variantes experimentales/audio/imagen (`banana`, `nano`, `omni`, `audio`, `live`, `exp`, `aqa`, `learnlm`) y respeta siempre el modelo exacto seleccionado sin cambiarlo a escondidas.
- **Escáner dual:** CameraX HD (con selector de lente y modo fotocopia) + botón **`✨ Google`** para lanzar el escáner ML Kit cuando prefieras.

## 4. Pendientes para futura versión 3.0
- Renderizado de Markdown enriquecido y esquemas visuales (tablas, negritas reales, bloques de código, diagramas).
- Firma fija del APK (`cachito.keystore` + GitHub Secrets) para actualizar sin desinstalar ni perder datos/modelos.
- Ver detalle completo en `CACHITO_V3_CONTEXTO_Y_ROADMAP.md`.
