#!/usr/bin/env bash
# Builds the Android launcher + notification icons from assets/icons/icons.png.
# Needs ImageMagick. The glyph (brackets + nodes) is cut out of the artwork by colour
# (see step 1) and re-composited on an adaptive-icon background.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/assets/icons/icons.png"
res="$root/android/app/src/main/res"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# 1. glyph with soft alpha, trimmed tight.
#    luminance: glyph >= 35% vs background <= 28%; plus red >= 250 (pink node) vs background <= 130.
magick "$src" -crop 1010x500+120+370 +repage "$tmp/crop.png"
magick "$tmp/crop.png" -colorspace Gray -level 30%,35.5% "$tmp/mg.png"
magick "$tmp/crop.png" -channel R -separate +channel -level 75%,90% "$tmp/mr.png"
magick "$tmp/mg.png" "$tmp/mr.png" -compose Lighten -composite "$tmp/mask.png"
magick "$tmp/crop.png" "$tmp/mask.png" -alpha off -compose CopyOpacity -composite -trim +repage "$tmp/glyph.png"

# 2. adaptive foreground: 108dp canvas, glyph 58dp wide (inside the 66dp safe circle)
declare -A fg=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
for d in "${!fg[@]}"; do
  s=${fg[$d]}; w=$(( s * 58 / 108 ))
  mkdir -p "$res/mipmap-$d"
  magick "$tmp/glyph.png" -resize "${w}x" -background none -gravity center -extent "${s}x${s}" \
    "$res/mipmap-$d/ic_launcher_foreground.png"
done

# 3. notification small icon: white silhouette, 24dp
declare -A st=( [mdpi]=24 [hdpi]=36 [xhdpi]=48 [xxhdpi]=72 [xxxhdpi]=96 )
for d in "${!st[@]}"; do
  s=${st[$d]}
  mkdir -p "$res/drawable-$d"
  magick "$tmp/glyph.png" -resize "$(( s * 90 / 100 ))x" -fill white -colorize 100 \
    -background none -gravity center -extent "${s}x${s}" "$res/drawable-$d/ic_stat_synapse.png"
done

# 4. background + adaptive icon definition
mkdir -p "$res/drawable" "$res/mipmap-anydpi"
cat > "$res/drawable/ic_launcher_background.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="315"
        android:startColor="#6A1FC8"
        android:centerColor="#2B0587"
        android:endColor="#14037A" />
</shape>
XML
cat > "$res/mipmap-anydpi/ic_launcher.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
XML
echo "icons generated under $res"
