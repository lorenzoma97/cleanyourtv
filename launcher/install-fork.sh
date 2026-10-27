#!/usr/bin/env bash
# Fork lorenzoma97: one command for the whole home after a new build (apk-latest).
#   1. launcher update (install-prebuilt.sh: checksum, install, READ_TV_LISTINGS)
#   2. Casa add-on: checksum, install, TMDB key from ~/.cleanyourtv/tmdb.txt (never in the repo or the APK)
#   3. theme lorenzo, first Casa refresh, content probe, Netflix title-link test
# Usage: TV_HOST=192.168.1.47 launcher/install-fork.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
export TV_HOST
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
tv() { "$PY" -I ../tools/tv.py "$@"; }
REL=https://github.com/lorenzoma97/cleanyourtv/releases/download/apk-latest

echo "== 1. launcher"
bash ./install-prebuilt.sh

echo "== 2. Casa"
mkdir -p app/build/outputs/apk/release
CASA=app/build/outputs/apk/release/casa-release.apk
curl -sSL --ssl-no-revoke -o "$CASA" "$REL/casa-release.apk"
WANT=$(curl -sSL --ssl-no-revoke "$REL/SHA256SUMS" | awk '$2 == "casa-release.apk" {print $1}')
GOT=$(sha256sum "$CASA" | awk '{print $1}')
[ -n "$WANT" ] && [ "$WANT" = "$GOT" ] || { echo "ERROR: Casa checksum mismatch ($GOT vs $WANT)" >&2; exit 1; }
"$PY" -I ../tools/b64push.py "$CASA" /data/local/tmp/casa.apk
tv 'pm install -r /data/local/tmp/casa.apk; rm -f /data/local/tmp/casa.apk'
# Windows Python cannot open a Git Bash path (/c/Users/...) with MSYS_NO_PATHCONV=1: hand it C:/Users/...
KEY="$HOME/.cleanyourtv/tmdb.txt"
command -v cygpath >/dev/null && KEY="$(cygpath -m "$KEY")"
if [ -f "$KEY" ]; then
  tv 'mkdir -p /sdcard/Android/data/it.lorenzoma97.casa/files'
  tv --push "$KEY" /sdcard/Android/data/it.lorenzoma97.casa/files/tmdb.txt
  echo "TMDB key copied to the TV"
else
  echo "WARNING: $KEY missing: Casa cannot download titles and pictures" >&2
fi

echo "== 3. theme, first refresh, probe"
(cd .. && bash tools/theme.sh themes/lorenzo)
# Touching the provider starts Casa: it schedules the 6-hourly refresh and one right now.
tv 'content query --uri content://it.lorenzoma97.casa.hero/items/com.netflix.ninja' >/dev/null || true
echo "waiting 60 s for Casa's first download..."
sleep 60
tv 'content query --uri content://it.lorenzoma97.casa.hero/items/com.netflix.ninja --projection title:line' | head -12 || true
tv 'logcat -d -s TvHomeProbe:I Casa:* | tail -40' || true
# The Netflix title link was tested on 2026-10-27 (works on a clean start); no need to open Netflix on every install.
tv 'input keyevent KEYCODE_HOME' >/dev/null || true
