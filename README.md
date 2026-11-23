# Cachito Android v2.0

Asistente técnico argentino nativo (**Kotlin + Jetpack Compose + Gradle**) optimizado para **Android 16 (12 GB RAM)** con compilación automática en **GitHub Actions**.

## Características principales
- **Motor IA primario offline (0 API key):** **Gemini Nano** vía SDK oficial **ML Kit GenAI Prompt API** (`com.google.mlkit:genai-prompt:1.0.0-beta4`) sobre **Android AICore**.
- **Fallbacks sin clave personal:** Intent directo a la **app oficial de Gemini** instalada en el teléfono (usa tu cuenta activa), conexión opcional a **Cacho PC** (Wi-Fi local / Tailscale), o API Key propia gratuita de **Groq** / **Google AI Studio** (`x-goog-api-key`).
- **Avatar Don Cacho:** 6 GIFs animados (`160x160`) + insignia de clima de Mendoza en tiempo real con alerta amarilla/roja.
- **Barra inferior tipo ticker:** Clima Mendoza + Dólar Argentina (`Blue`, `Oficial`, `MEP`, `CCL`) cada 5 min + Índices/Acciones/WTI (`^GSPC`, `QQQ`, `SMH`, `NVDA`, `GOOGL`, `AAPL`, `MSFT`, `META`, `AMZN`, `MELI`, `AMD`, `O`, `SHEL`, `CL=F`) de Lunes a Viernes de 9:00 a 17:00 hs.
- **Escáner PDF multipágina + OCR:** ML Kit Document Scanner + extracción de texto directa para Cachito.
- **Buscador de pertenencias YOLO26 / YOLOE-26:** Soporte nativo para modelos `yolo26n.tflite` / `yoloe26.tflite` en `app/src/main/assets/models/` + pipeline ML Kit Object Detection con guardado de fotos de muestra (llaves, control, billetera).
- **Memoria y Cacho Contador:** RAG JSON/JSONL local + botón **Vaciar RAG** + libro contable `finanzas.json` en ARS y USD.

## Compilar APK en GitHub Actions
1. Subí esta carpeta completa a un repositorio de GitHub (`main` o `master`).
2. GitHub Actions ejecutará `.github/workflows/build-apk.yml` automáticamente y dejará listo el artefacto **`Cachito-Android-v2.0.apk`**.
