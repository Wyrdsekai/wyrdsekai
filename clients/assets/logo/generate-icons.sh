#!/usr/bin/env bash
# Slice the Gemini logo masters into every platform app-icon size and wire both
# clients (KMP + RN, Android + iOS). Idempotent — re-run when the art changes.
#
#   clients/assets/logo/master-1024.png       full-bleed, opaque  (REQUIRED)
#   clients/assets/logo/foreground-1024.png   transparent, safe-zone (optional)
#   clients/assets/logo/monochrome-1024.png   transparent silhouette (optional)
#
# Env: BG_COLOR (adaptive background, default brand teal #00796B)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"            # repo root
BG_COLOR="${BG_COLOR:-#00796B}"

# IM7 ships `magick`; IM6 ships `convert`. Prefer magick if present.
if command -v magick >/dev/null 2>&1; then IM="magick"; else IM="convert"; fi

MASTER="$HERE/master-1024.png"
FG="$HERE/foreground-1024.png"
MONO="$HERE/monochrome-1024.png"
[ -f "$MASTER" ] || { echo "ERROR: $MASTER missing — drop the 1024 full-bleed master here first."; exit 1; }
HAVE_FG=0;   [ -f "$FG" ]   && HAVE_FG=1
HAVE_MONO=0; [ -f "$MONO" ] && HAVE_MONO=1
echo "IM=$IM  bg=$BG_COLOR  foreground=$HAVE_FG  monochrome=$HAVE_MONO"

# Density → px.  Legacy square launcher = 48dp base; adaptive foreground = 108dp.
DENS="mdpi hdpi xhdpi xxhdpi xxxhdpi"
leg_px(){ case $1 in mdpi)echo 48;;hdpi)echo 72;;xhdpi)echo 96;;xxhdpi)echo 144;;xxxhdpi)echo 192;;esac; }
fg_px(){  case $1 in mdpi)echo 108;;hdpi)echo 162;;xhdpi)echo 216;;xxhdpi)echo 324;;xxxhdpi)echo 432;;esac; }

# --- helpers ---------------------------------------------------------------
sq(){ # square legacy icon at $2 px from master → $1
  "$IM" "$MASTER" -resize "${2}x${2}" -background white -alpha remove -alpha off "$1"; }
round(){ # circular legacy icon at $2 px from master → $1
  local s="$2"; local r=$((s/2)); local m; m="$(mktemp --suffix=.png)"
  "$IM" -size "${s}x${s}" xc:none -fill white -draw "circle $r,$r $r,0" "$m"
  "$IM" "$MASTER" -resize "${s}x${s}" -background white -alpha remove "$m" \
        -alpha off -compose CopyOpacity -composite "$1"
  rm -f "$m"; }
fg(){ # adaptive foreground at $2 px → $1 (use real fg if given, else centre-fit master 66%)
  local s="$2"
  if [ "$HAVE_FG" = 1 ]; then "$IM" "$FG" -resize "${s}x${s}" "$1"
  else local inner=$((s*66/100))
       "$IM" -size "${s}x${s}" xc:none \( "$MASTER" -resize "${inner}x${inner}" \) -gravity center -composite "$1"; fi; }
mono(){ "$IM" "$MONO" -resize "${2}x${2}" "$1"; }

adaptive_xml(){ # $1 = path
  { echo '<?xml version="1.0" encoding="utf-8"?>'
    echo '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">'
    echo '    <background android:drawable="@color/ic_launcher_background"/>'
    echo '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>'
    [ "$HAVE_MONO" = 1 ] && echo '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>'
    echo '</adaptive-icon>'; } > "$1"; }

gen_android(){ # $1 = android res root
  local RES="$1"
  for d in $DENS; do
    local dir="$RES/mipmap-${d}"; mkdir -p "$dir"
    rm -f "$dir/ic_launcher.webp" "$dir/ic_launcher_round.webp"   # avoid png/webp dup-resource clash
    sq    "$dir/ic_launcher.png"            "$(leg_px "$d")"
    round "$dir/ic_launcher_round.png"      "$(leg_px "$d")"
    fg    "$dir/ic_launcher_foreground.png" "$(fg_px "$d")"
    [ "$HAVE_MONO" = 1 ] && mono "$dir/ic_launcher_monochrome.png" "$(fg_px "$d")"
  done
  mkdir -p "$RES/mipmap-anydpi-v26" "$RES/values"
  adaptive_xml "$RES/mipmap-anydpi-v26/ic_launcher.xml"
  adaptive_xml "$RES/mipmap-anydpi-v26/ic_launcher_round.xml"
  printf '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">%s</color>\n</resources>\n' \
    "$BG_COLOR" > "$RES/values/ic_launcher_background.xml"
  echo "  android icons → $RES"; }

# --- KMP Android -----------------------------------------------------------
gen_android "$ROOT/clients/kmp/androidApp/src/androidMain/res"
python3 - "$ROOT/clients/kmp/androidApp/src/androidMain/AndroidManifest.xml" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read()
if 'android:icon=' not in s:
    s=re.sub(r'(<application\b)', r'\1\n        android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"', s, count=1)
    open(p,'w').write(s); print("  wired KMP manifest android:icon")
else: print("  KMP manifest already has android:icon")
PY

# --- RN Android + Expo app.json -------------------------------------------
gen_android "$ROOT/clients/rn/android/app/src/main/res"
mkdir -p "$ROOT/clients/rn/assets"
"$IM" "$MASTER" -resize 1024x1024 "$ROOT/clients/rn/assets/icon.png"
if [ "$HAVE_FG" = 1 ]; then "$IM" "$FG" -resize 1024x1024 "$ROOT/clients/rn/assets/adaptive-icon.png"
else "$IM" -size 1024x1024 xc:none \( "$MASTER" -resize 676x676 \) -gravity center -composite "$ROOT/clients/rn/assets/adaptive-icon.png"; fi
python3 - "$ROOT/clients/rn/app.json" "$BG_COLOR" <<'PY'
import json,sys
p,bg=sys.argv[1],sys.argv[2]; d=json.load(open(p)); e=d["expo"]
e["icon"]="./assets/icon.png"
e.setdefault("android",{}).setdefault("adaptiveIcon",{})
e["android"]["adaptiveIcon"]["foregroundImage"]="./assets/adaptive-icon.png"
e["android"]["adaptiveIcon"]["backgroundColor"]=bg
json.dump(d,open(p,"w"),indent=2); open(p,"a").write("\n"); print("  wired RN app.json icon/adaptiveIcon")
PY

# --- KMP iOS AppIcon -------------------------------------------------------
ICON_SET="$ROOT/clients/kmp/iosApp/WyrdsekaiApp/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ICON_SET"
"$IM" "$MASTER" -resize 1024x1024 -background white -alpha remove -alpha off "$ICON_SET/AppIcon.png"
cat > "$ICON_SET/Contents.json" <<'JSON'
{
  "images" : [
    { "filename" : "AppIcon.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
JSON
echo "  iOS AppIcon → $ICON_SET/AppIcon.png"
echo "DONE. Rebuild to see it: (cd clients/kmp && ./gradlew :androidApp:assembleDebug)"
