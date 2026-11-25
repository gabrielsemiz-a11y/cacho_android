# Cachito Android — Documentación Maestra y Hoja de Ruta (v2.43 → v3.0)

Este documento resume el estado final de la serie **v2.x** de **Cachito Android** (asistente personal con la identidad de Don Cacho) y sirve como punto de partida completo para retomar el desarrollo en una futura **versión 3.0**, incluso en una nueva sesión o con otro entorno de IA.

---

## 1. Datos Generales y Reglas de Trabajo
* **Nombre de la app:** Cachito Android
* **Ubicación / Contexto regional:** Mendoza, Argentina (clima local, cotizaciones de dólar AR, noticias de Argentina).
* **Repositorio GitHub:** `gabrielsemiz-a11y/cacho_android`
* **Carpeta local de trabajo (PC):** `E:\cachito-android-v2.0`
* **Flujo de compilación:** GitHub Actions (`.github/workflows/build-apk.yml`) genera el APK descargable como artefacto en cada `git push`.
* **Reglas de colaboración del autor:**
  1. **No generar código ni archivos ZIP sin un `GO` explícito.**
  2. Cada entrega de versión debe incluir: ZIP descargable con los cambios, script PowerShell de subida (`subir_vX.ps1`) y archivo de contexto actualizado.

---

## 2. Stack Tecnológico Consolidado (Cierre v2.42 / v2.43)
* **Lenguaje:** Kotlin `2.3.21`
* **UI:** Jetpack Compose (Material 3)
* **Android Gradle Plugin (AGP):** `8.9.1`
* **Gradle:** `8.11.1`
* **SDK:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`
* **Cámara y Escaneo:** CameraX (`ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY`) + `PdfDocument` nativo de Android (hasta 2480 px).
* **Visión / OCR:** Google ML Kit (Text Recognition + Object Detection) + comparador visual local por histogramas de color.
* **IA Local Offline:** MediaPipe Tasks GenAI (`tasks-genai`) sobre motor **LiteRT**.
* **Red:** OkHttp3 + org.json.
* **Seguridad:** Android Keystore con cifrado **AES-256-GCM**.

---

## 3. Arquitectura del Proyecto y Archivos Clave

| Archivo / Ruta | Responsabilidad Principal |
| :--- | :--- |
| `build.gradle.kts` (raíz) | Define AGP `8.9.1`, Kotlin `2.3.21` y Compose Compiler `2.3.21`. |
| `app/build.gradle.kts` | Dependencias (Compose, CameraX, ML Kit, MediaPipe, OkHttp) y configuración de SDK. |
| `.github/workflows/build-apk.yml` | Pipeline CI en GitHub Actions para compilar y publicar el APK. |
| `MainActivity.kt` | Interfaz completa en Compose (~70 KB): chat, avatar GIF de Don Cacho, barra superior con badge de versión (`softWrap = false`), ticker en vivo, diálogo de Ajustes con claves enmascaradas y visor **CameraX HD**. |
| `CachitoViewModel.kt` | Manejo de estado reactivo, coordinación del historial, disparadores de cámara/escáner, OCR y consultas a `CachitoBrain`. |
| `CachitoBrain.kt` | Núcleo de IA (~40 KB): orquestación de motores, failover automático, *system prompt* con personalidad y reglas anti-alucinación, carga de modelos MediaPipe/LiteRT y consulta de noticias/web en vivo. |
| `SecureKeyStore.kt` | Cifrado y descifrado de API Keys mediante Android Keystore (AES-256-GCM). |
| `LocalStorage.kt` | Persistencia local de configuración, preferencias de motor, historial y notas guardadas. |
| `VisionHelper.kt` | OCR multipágina con ML Kit y detección/comparación de objetos contra fotos de referencia. |
| `LiveDataClient.kt` | Datos en tiempo real para el ticker inferior: clima de Mendoza (Open-Meteo) y cotizaciones del dólar (DolarAPI). |
| `Models.kt` | Estructuras de datos (`WeatherInfo`, `DollarQuote`, `TickerSnapshot`, `DetectedTarget`, motores IA, mensajes). |
| `res/drawable/` | GIFs animados de Don Cacho (`cacho_neutro.gif`, etc.) y drawables del ícono adaptativo. |
| `res/mipmap-anydpi-v26/` | Íconos adaptativos del launcher (`ic_launcher.xml`, `ic_launcher_round.xml`). |

---

## 4. Motores de IA y Sistema de Failover
Cachito soporta múltiples proveedores seleccionables desde **⚙️ Ajustes**:

1. **OpenRouter:** Acceso a modelos gratuitos y pagos (ej. `openrouter/free`, `nvidia/nemotron-3-...`, Claude). Incluye botón `↻ Ver` para listar modelos disponibles en tiempo real.
2. **Google AI Studio:** Modelos Gemini con **Google Search Grounding** nativo.
3. **Groq Cloud:** Respuestas ultrarrápidas (hardware LPU). Soporta selección dinámica de modelos (`↻ Ver`), ideal para alternar entre velocidad y modelos de mayor razonamiento (`llama-3.3-70b-versatile`, `gpt-oss`, etc.).
4. **IA Local Offline (MediaPipe / LiteRT):**
   * Descarga directa del modelo `.task` (~1.5 GB, por defecto `Qwen2.5-1.5B-Instruct.task`) desde Ajustes.
   * Configurado con `PreferredBackend.GPU` y respaldo automático a `PreferredBackend.CPU` (ruta LiteRT moderna).
5. **App Gemini del teléfono:** Acceso por Intent directo sin necesidad de API Key.
* **Hardware condicional:** Si el teléfono no soporta Gemini Nano / AICore, esa opción se oculta automáticamente en Ajustes.

---

## 5. Funcionalidades Consolidadas y Cierre v2.43

### Ya funcionando y validadas en v2.42:
* **Cifrado de claves:** API Keys protegidas con Android Keystore (AES-256) y ocultas con puntos en Ajustes (revelables con el botón `👁️`).
* **Reglas anti-alucinación:** Prohibición explícita en el *system prompt* de inventar URLs, dominios o programas desconocidos, y de recomendar desactivar el antivirus.
* **Noticias en vivo universales:** Inyección automática de titulares desde Google News Argentina RSS cuando el usuario pregunta por noticias/actualidad (funciona en OpenRouter, Groq y modelo local).
* **Escáner HD CameraX:** Reemplazo del escáner borroso de ML Kit por captura a máxima calidad, enfoque por toque (macro), linterna, captura multipágina y generación de PDF HD + OCR.
* **Estética:** Badge de versión en una sola línea (`softWrap = false`), botón `★ Guardar` alineado y ticker inferior en vivo.

### Alcance definido para el cierre v2.43:
1. **Búsqueda web general en vivo (sin API Key extra):** Consulta vía DuckDuckGo para búsquedas de precios, pasajes o info general, habilitada en **OpenRouter**, **Groq** y **Modelo Local** (versión compacta top 3 para respetar la ventana de 1024 tokens).
2. **Selector de lente en CameraX HD:** Detección automática del sensor trasero de máxima resolución real (Camera2) + botón `🔄 Lente` en el visor para cambiar de cámara en vivo.
3. **Modo Fotocopia propio:** Recorte ajustable del documento + filtro de alto contraste (fondo blanco / texto nítido) antes de armar el PDF multipágina.
4. **Ícono del launcher con la cara de Don Cacho:** Extracción automática del primer fotograma de `cacho_neutro.gif` a PNG adaptativo desde el script de subida.

---

## 6. Lecciones Aprendidas (Errores Resueltos para No Repetir en v3.0)
* **Incompatibilidad de versión Kotlin:** Siempre mantener alineado `org.jetbrains.kotlin.android` y `org.jetbrains.kotlin.plugin.compose` en `build.gradle.kts` raíz con la versión de `kotlin-stdlib` (`2.3.21`).
* **Error de firma en MediaPipe (`legacy CPU path`):** Los modelos `.task` modernos (como Qwen 2.5) fallan si no se especifica explícitamente `PreferredBackend.GPU` o `PreferredBackend.CPU` en `LlmInferenceOptions`.
* **ML Kit Document Scanner borroso:** La pantalla cerrada de ML Kit dispara con baja resolución y no permite elegir lente; usar CameraX con control de resolución y lentes resuelve el problema de raíz.
* **Pérdida del modelo offline al reinstalar:** Al compilar en modo Debug con firmas distintas o desinstalar entre versiones, Android borra los datos internos (incluyendo el archivo `.task` de 1.5 GB).
* **Sobrescritura parcial de ZIPs:** Siempre conservar intacta la carpeta base (archivos raíz de Gradle, `VisionHelper.kt`, `LiveDataClient.kt`, GIFs e íconos) o empaquetar actualizaciones autocontenidas.
* **Strings con saltos de línea en Kotlin:** Verificar siempre que los `\n` queden escapados y no como saltos de línea literales al generar parches automatizados.

---

## 7. Hoja de Ruta Propuesta para Cachito v3.0

Cuando retomes el proyecto para la **v3.0**, estas son las mejoras estructurales de mayor impacto:

### Arquitectura y DevOps
1. **Firma de Release fija en GitHub Actions:** Guardar un `keystore.jks` en GitHub Secrets para que cada APK nuevo actualice directo encima del anterior **sin perder el historial, las claves ni el modelo local de 1.5 GB**.
2. **Modularización de UI:** Dividir `MainActivity.kt` (actualmente >70 KB) en archivos separados por pantalla: `ChatScreen.kt`, `SettingsDialog.kt`, `ScannerScreen.kt`, `TickerBar.kt` y `Theme.kt`.
3. **Persistencia con Room Database:** Reemplazar almacenamiento plano por base de datos SQLite/Room para búsquedas rápidas en historial, conversaciones múltiples (hilos) y notas guardadas.

### Escáner y Visión v3
4. **Detección automática de bordes + perspectiva:** Combinar la captura HD de CameraX (con selector de lente) con corrección de perspectiva automática (4 esquinas arrastrables) para lograr fotocopias perfectas.
5. **Soporte multimodal real en chat:** Adjuntar imágenes desde galería o cámara directamente a modelos con visión (OpenRouter / Google AI Studio) para analizar recibos, remedios, placas electrónicas o fallas mecánicas.

### Inteligencia y Experiencia de Usuario
6. **Memoria a largo plazo (RAG local):** Permitir que Cachito consulte tus notas guardadas y PDFs escaneados anteriores como contexto al responder.
7. **Modo Voz Manos Libres:** Conversación continua por voz (reconocimiento + lectura automática TTS con pausa inteligente).
8. **Widget de escritorio:** Widget liviano con el clima de Mendoza, dólar en vivo y acceso directo por voz/cámara a Don Cacho.

---

## 8. Prompt Sugerido para Retomar en una Futura Sesión
Cuando quieras arrancar la **v3.0**, podés subir este archivo y pegar:
> *"Hola, adjunto `CACHITO_V3_CONTEXTO_Y_ROADMAP.md` con todo el historial de Cachito Android. Quiero iniciar la planificación de la v3.0. Recordá mi regla: no generes código ni ZIPs hasta que te dé el GO explícito. Analizá el documento y propongamos el alcance de la v3.0."*