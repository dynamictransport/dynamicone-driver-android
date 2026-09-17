<#
.SYNOPSIS
    Generates the DynamicONE app icon, splash icon and in-app logo from the brand mark (branding/dynamicone-mark.png).

.DESCRIPTION
    The mark is a transparent raster with gradients and soft edges, so the Android resources are rasters as well:
    - mipmap-*/ic_launcher_foreground.png  adaptive icon foreground, 108 dp canvas, mark 54 dp wide (inside the 66 dp safe zone)
    - mipmap-*/ic_launcher_monochrome.png  the same shape in white with the mark's alpha (Android 13 themed icons)
    - drawable-nodpi/splash_icon.png       splash icon without icon background: 288 dp at 4x, mark 144 dp wide (inside the 192 dp circle)
    - drawable-*/dynamicone_logo.png       the mark on a transparent background for in-app use, 96 dp wide per density
    Run from the repository root after replacing branding/dynamicone-mark.png:
        powershell -ExecutionPolicy Bypass -File tools\Generate-AppIcons.ps1
#>
param(
    [string]$Source = (Join-Path $PSScriptRoot '..\branding\dynamicone-mark.png'),
    [string]$Res = (Join-Path $PSScriptRoot '..\app\src\main\res')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$Source = (Resolve-Path $Source).Path
$Res = (Resolve-Path $Res).Path
$mark = [System.Drawing.Image]::FromFile($Source)

# Draws the mark centred on a transparent square canvas; [white] keeps only the alpha, in white.
function Save-Canvas([int]$canvasPx, [double]$markWidthPx, [string]$path, [bool]$white) {
    $bitmap = New-Object System.Drawing.Bitmap $canvasPx, $canvasPx, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

        $markHeightPx = $markWidthPx * $mark.Height / $mark.Width
        $x = ($canvasPx - $markWidthPx) / 2
        $y = ($canvasPx - $markHeightPx) / 2
        $destination = New-Object System.Drawing.RectangleF ([single]$x), ([single]$y), ([single]$markWidthPx), ([single]$markHeightPx)

        $attributes = New-Object System.Drawing.Imaging.ImageAttributes
        # Clamp at the edges so resampling does not blend in transparent black from outside the image.
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        if ($white) {
            $matrix = New-Object System.Drawing.Imaging.ColorMatrix
            $matrix.Matrix00 = 0; $matrix.Matrix11 = 0; $matrix.Matrix22 = 0
            $matrix.Matrix40 = 1; $matrix.Matrix41 = 1; $matrix.Matrix42 = 1
            $attributes.SetColorMatrix($matrix)
        }
        $graphics.DrawImage($mark, [System.Drawing.Rectangle]::Round($destination), 0, 0, $mark.Width, $mark.Height, [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ("{0} ({1}x{1})" -f $path.Substring($Res.Length + 1), $canvasPx)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

# The mark alone at [widthPx], transparent background, aspect ratio kept.
function Save-Scaled([int]$widthPx, [string]$path) {
    $heightPx = [int][Math]::Round($widthPx * $mark.Height / $mark.Width)
    $bitmap = New-Object System.Drawing.Bitmap $widthPx, $heightPx, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $attributes = New-Object System.Drawing.Imaging.ImageAttributes
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($mark, (New-Object System.Drawing.Rectangle 0, 0, $widthPx, $heightPx), 0, 0, $mark.Width, $mark.Height, [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ("{0} ({1}x{2})" -f $path.Substring($Res.Length + 1), $widthPx, $heightPx)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    $densities = [ordered]@{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }
    foreach ($density in $densities.GetEnumerator()) {
        $canvas = [int](108 * $density.Value)
        $width = 54 * $density.Value
        Save-Canvas $canvas $width (Join-Path $Res "mipmap-$($density.Key)\ic_launcher_foreground.png") $false
        Save-Canvas $canvas $width (Join-Path $Res "mipmap-$($density.Key)\ic_launcher_monochrome.png") $true
    }
    Save-Canvas 1152 576 (Join-Path $Res 'drawable-nodpi\splash_icon.png') $false

    # In-app logo, 96 dp wide, pre-scaled per density: scaling the large mark at draw time aliases its small squares.
    foreach ($density in $densities.GetEnumerator()) {
        Save-Scaled ([int][Math]::Round(96 * $density.Value)) (Join-Path $Res "drawable-$($density.Key)\dynamicone_logo.png")
    }
} finally {
    $mark.Dispose()
}
