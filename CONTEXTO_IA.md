# CONTEXTO_IA — Cachito Android (v2.42)

> Archivo de continuidad para retomar el proyecto en un chat nuevo sin perder el hilo.

## 1. Reglas de trabajo del usuario
- **Idioma y tono:** Castellano rioplatense (voseo natural), directo y conciso.
- **Confirmación antes de código/ZIP:** NO generar código ni archivos ZIP hasta recibir un **GO** explícito.
- **Formato de entrega:** Siempre entregar un archivo `.zip` descargable con los archivos actualizados + un script PowerShell (`subir_vX.XX.ps1`) + este `CONTEXTO_IA.md` actualizado.
- **Dispositivo objetivo:** ZTE Nubia Neo 3 GT 5G (12 GB RAM, Android 16).
- **Repositorio:** `https://github.com/gabrielsemiz-a11y/cacho_android` (carpeta local en PC: `E:\cachito-android-v2.0`).

## 2. Stack técnico exacto
- **Lenguaje / UI:** Kotlin 2.3.21 + Jetpack Compose (BOM `2025.02.00`, Material 3 `1.3.1`).
- **Build:** AGP 8.9.1, Gradle 8.11.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK 21.
- **CI/CD:** GitHub Actions (`.github/workflows/build-apk.yml`) compila `assembleDebug` y sube el artefacto `Cachito-Android-v2.42-APK`.
- **IA & Visión:**
  - `com.google.mediapipe:tasks-genai:0.10.22` (Motor Local Offline `.task` / `.bin` con `PreferredBackend.GPU` + fallback automático a `PreferredBackend.CPU` LiteRT).
  - `com.google.mlkit:genai-prompt:1.0.0-beta4` (AICore — oculto automáticamente si el hardware no lo soporta).
  - Escáner de Documentos **CameraX HD** propio (`ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY` + toque para enfocar macro + flash/linterna + multipágina + `PdfDocument` HD hasta 2480 px).
  - `com.google.mlkit:text-recognition:16.0.1` + `object-detection:17.0.2`.
  - OkHttp `4.12.0` (OpenRouter, Google AI Studio con Search Grounding, Groq Cloud y feed Google News AR en vivo).

## 3. Arquitectura de archivos clave
- `app/src/main/java/ar/com/cachito/assistant/model/Models.kt`: Modelos de datos, `AiEngineMode` (`LOCAL_OFFLINE`, `OPENROUTER`, `GOOGLE_AI_STUDIO`, `GROQ_API`, `GEMINI_APP_INTENT`, `GEMINI_NANO`), `AppSettings`.
- `app/src/main/java/ar/com/cachito/assistant/data/SecureKeyStore.kt`: Cifrado hardware **AES-256-GCM** con Android Keystore para las API Keys.
- `app/src/main/java/ar/com/cachito/assistant/data/LocalStorage.kt`: Persistencia local (`config.json`, `secrets_enc.json` cifrado, `finanzas.json`, RAG local).
- `app/src/main/java/ar/com/cachito/assistant/ai/CachitoBrain.kt`: Orquestador de motores IA, `LlmInferenceOptions` con `PreferredBackend` LiteRT (GPU -> CPU), failover automático OpenRouter ↔ Google AI Studio ↔ Groq, búsqueda de noticias en vivo (Google News AR) para todos los motores y reglas anti-alucinación.
- `app/src/main/java/ar/com/cachito/assistant/viewmodel/CachitoViewModel.kt`: Estado Reactivo (`StateFlow`), TTS con voz masculina pausada (`rate=0.85`, `pitch=0.85`), generador PDF HD (`PdfDocument` hasta 2480 px).
- `app/src/main/java/ar/com/cachito/assistant/MainActivity.kt`: UI Compose completa, **Escáner CameraX HD** multipágina con toque de enfoque y linterna, Radar de Objetos CameraX en vivo, Ajustes con claves enmascaradas (`••••`), AICore oculto si no es compatible y panel de descarga del modelo offline.

## 4. Historial de versiones
- **v2.2:** Failover Google/Groq, compartir PDF con `FileProvider` + `ClipData`, botón reiniciar charla.
- **v2.3:** OpenRouter (NVIDIA Nemotron gratis), Google Search Grounding, cámara en vivo CameraX para objetos, PDF HD con `PdfDocument`, voz masculina TTS, fix botón `Guardar`, regla anti-cirílico.
- **v2.41:** Motor IA Local Offline descargable (MediaPipe `tasks-genai` `.task`), cifrado AES-256-GCM (Android Keystore), ocultar Gemini Nano / AICore si no hay soporte, `CONTEXTO_IA.md`.
- **v2.42 (Actual):**
  - **Fix IA Local Offline (LiteRT):** Configurado `setPreferredBackend(LlmInference.Backend.GPU)` con respaldo automático a `LlmInference.Backend.CPU` para evitar el error `TfLitePrefillDecoderRunnerCalculator` / legacy CPU path con Qwen 2.5.
  - **Escáner CameraX HD propio:** Reemplaza el escáner borroso de ML Kit por captura a máxima calidad (`CAPTURE_MODE_MAXIMIZE_QUALITY`), toque en pantalla para enfocar de cerca (macro), botón de linterna y armado multipágina en `PdfDocument` HD + OCR.
  - **Noticias en vivo universales:** Consulta automática al feed de Google News AR cuando se piden noticias o actualidad para que Groq, OpenRouter y el modelo local respondan con titulares reales.
  - **Regla anti-invento de URLs/apps:** Prohibición estricta de inventar dominios, programas o sugerir desactivar el antivirus.
  - **Ícono del launcher incluido** (`ic_launcher` / `ic_launcher_round` adaptativo en `mipmap-anydpi-v26` + `drawable`) y badge de versión en una sola línea (`softWrap = false`).

## 5. Pendientes para futuras versiones (v2.5 / v3.0)
- **Firma fija del APK (`cachito.keystore` + GitHub Secrets):** Pendiente. Permite instalar actualizaciones encima sin desinstalar la versión anterior (conservando datos, claves cifradas y modelo offline). Requiere generar `cachito.keystore` con `keytool` (JDK) y cargar los 4 Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) en GitHub.
