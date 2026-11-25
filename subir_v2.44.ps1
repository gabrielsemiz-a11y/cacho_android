# Ejecutar dentro de E:\cachito-android-v2.0 tras descomprimir el ZIP
$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

# 1. Extraer primer cuadro de cacho_neutro.gif como icono adaptativo PNG (con margen seguro)
$gifPath = "$PWD\app\src\main\res\drawable\cacho_neutro.gif"
$pngOut  = "$PWD\app\src\main\res\drawable\ic_launcher_cacho.png"

if (Test-Path $gifPath) {
    try {
        Add-Type -AssemblyName System.Drawing
        $img = [System.Drawing.Image]::FromFile($gifPath)
        $canvas = New-Object System.Drawing.Bitmap(432, 432)
        $g = [System.Drawing.Graphics]::FromImage($canvas)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.Clear([System.Drawing.Color]::Transparent)
        # Margen seguro Android Adaptive Icon (zona central 288x288)
        $g.DrawImage($img, 72, 72, 288, 288)
        $canvas.Save($pngOut, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $canvas.Dispose(); $img.Dispose()

        $adaptiveCacho = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_cacho" />
</adaptive-icon>
'@
        [IO.File]::WriteAllText("$PWD\app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml", $adaptiveCacho, $utf8NoBom)
        [IO.File]::WriteAllText("$PWD\app\src\main\res\mipmap-anydpi-v26\ic_launcher_round.xml", $adaptiveCacho, $utf8NoBom)
        Write-Host "OK: Icono de Don Cacho generado en ic_launcher_cacho.png" -ForegroundColor Green
    } catch {
        Write-Host "Aviso: no se pudo convertir el GIF, se usa icono vectorial azul ($($_.Exception.Message))" -ForegroundColor Yellow
    }
}

# 2. Limpiar iconos legacy PNG viejos y scripts antiguos
Get-ChildItem "$PWD\app\src\main\res\mipmap-*" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne "mipmap-anydpi-v26" } |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }

# 3. Subir a GitHub para compilar APK v2.44
git add -A
git commit -m "Cachito Android v2.44: Radar en español (EfficientDet-Lite0), cascada automatica entre motores, OpenRouter gratis/baratos + web nativa + fix null, Google AI limpio y boton ML Kit"
git push -u origin main --force
