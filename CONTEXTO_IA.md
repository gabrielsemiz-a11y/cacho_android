# CONTEXTO_IA — Cachito Android (v2.41)

> Archivo de continuidad para retomar el proyecto en un chat nuevo sin perder el hilo.

## 1. Reglas de trabajo del usuario
- **Idioma y tono:** Castellano rioplatense (voseo natural), directo y conciso.
- **Confirmación antes de código/ZIP:** NO generar código ni archivos ZIP hasta recibir un **GO** explícito.
- **Formato de entrega:** Siempre entregar un archivo `.zip` descargable con los archivos actualizados + un script PowerShell (`subir_vX.XX.ps1`) + este `CONTEXTO_IA.md` actualizado.
- **Dispositivo objetivo:** Nubia Neo 3 GT 5G (12 GB RAM, Android 16).
- **Repositorio:** `https://github.com/gabrielsemiz-a11y/cacho_android` (carpeta local en PC: `E:\cachito-android-v2.0`).

## 2. Stack técnico exacto
- **Lenguaje / UI:** Kotlin 2.3.21 + Jetpack Compose (BOM `2025.02.00`, Material 3 `1.3.1`).
- **Build:** AGP 8.9.1, Gradle 8.11.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK 21.
- **CI/CD:** GitHub Actions (`.github/workflows/build-apk.yml`) compila `assembleDebug` y sube el artefacto `Cachito-Android-v2.41-APK`.
- **IA & Visión:**
  - `com.google.mediapipe:tasks-genai:0.10.22` (Motor Local Offline `.task` / `.bin`).
  - `com.google.mlkit:genai-prompt:1.0.0-beta4` (AICore — oculto automáticamente si el hardware no lo soporta).
  - `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1` + `PdfDocument` HD.
  - `com.google.mlkit:text-recognition:16.0.1` + `object-detection:17.0.2`.
  - CameraX `1.4.1` (visor en vivo para radar de objetos).
  - OkHttp `4.12.0` (OpenRouter, Google AI Studio con Search Grounding, Groq Cloud).

## 3. Arquitectura de archivos clave
- `app/src/main/java/ar/com/cachito/assistant/model/Models.kt`: Modelos de datos, `AiEngineMode` (incluye `LOCAL_OFFLINE`, `OPENROUTER`, `GOOGLE_AI_STUDIO`, `GROQ_API`, `GEMINI_APP_INTENT`, `GEMINI_NANO`), `AppSettings`.
- `app/src/main/java/ar/com/cachito/assistant/data/SecureKeyStore.kt`: Cifrado hardware **AES-256-GCM** con Android Keystore para las API Keys (OpenRouter, Google, Groq).
- `app/src/main/java/ar/com/cachito/assistant/data/LocalStorage.kt`: Persistencia local (`config.json` sin claves en texto plano, `secrets_enc.json` cifrado, `finanzas.json`, RAG local en `memoria_charlas.jsonl` y `soluciones_verificadas.json`, migración automática de claves viejas).
- `app/src/main/java/ar/com/cachito/assistant/ai/CachitoBrain.kt`: Orquestador de motores IA, failover automático OpenRouter ↔ Google AI Studio (con Search Grounding) ↔ Groq, y ejecución 100% offline con MediaPipe `LlmInference` + descargador HTTP con progreso.
- `app/src/main/java/ar/com/cachito/assistant/viewmodel/CachitoViewModel.kt`: Estado Reactivo (`StateFlow`), TTS con voz masculina pausada (`rate=0.85`, `pitch=0.85`), generador PDF HD (`PdfDocument` hasta 2480 px), gestión de descarga/borrado del modelo local offline.
- `app/src/main/java/ar/com/cachito/assistant/MainActivity.kt`: UI Compose completa, visor CameraX en vivo con recuadro verde y vibración, Ajustes con claves enmascaradas (`••••`), AICore oculto si no es compatible, y panel de descarga del modelo offline.

## 4. Historial de versiones
- **v2.2:** Failover Google/Groq, compartir PDF con `FileProvider` + `ClipData`, botón reiniciar charla.
- **v2.3:** OpenRouter (NVIDIA Nemotron gratis), Google Search Grounding, cámara en vivo CameraX para objetos, PDF HD con `PdfDocument`, voz masculina TTS, fix botón `Guardar`, regla anti-cirílico.
- **v2.41 (Actual):**
  - Motor **IA Local Offline** descargable (MediaPipe `tasks-genai` `.task` con descarga directa desde Ajustes + opción de elegir archivo local o URL personalizada).
  - **Cifrado AES-256-GCM (Android Keystore)** para todas las API Keys + campos enmascarados con botón mostrar/ocultar + migración automática desde texto plano.
  - **Ocultar Gemini Nano / AICore** automáticamente si el teléfono no tiene soporte de hardware.
  - Inclusión estándar de `CONTEXTO_IA.md`.

## 5. Pendientes para futuras versiones (v2.5 / v3.0)
- **Firma fija del APK (`cachito.keystore` + GitHub Secrets):** Pendiente. Permite instalar actualizaciones encima sin desinstalar la versión anterior (conservando datos, claves cifradas y modelo offline). Requiere generar `cachito.keystore` con `keytool` (JDK) y cargar los 4 Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) en GitHub.
