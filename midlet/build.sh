#!/usr/bin/env bash
# Builds midlet/dist/BtRelay.jar + BtRelay.jad
# Needs: JDK 11+ (javac, jar), curl, unzip.
set -e
cd "$(dirname "$0")"
mkdir -p lib build/classes build/out dist
rm -rf build/classes/* build/out/* build/raw.jar build/pg.jar dist/*

# fetch <file> <url> [fallback urls...]  -> tries each url until one works
fetch() {
  out="$1"; shift
  [ -s "$out" ] && return 0
  for u in "$@"; do
    if curl -fsSL -o "$out" "$u"; then echo "OK   $out <- $u"; return 0; fi
    echo "FAIL $u"
  done
  rm -f "$out"
  echo "Could not download $out" >&2
  return 1
}

C=https://repo1.maven.org/maven2
fetch lib/cldc.jar  $C/org/microemu/microemu-cldc/2.0.4/microemu-cldc-2.0.4.jar \
                    $C/org/microemu/microemu-cldc/2.0.3/microemu-cldc-2.0.3.jar
fetch lib/midp.jar  $C/org/microemu/microemu-midp/2.0.4/microemu-midp-2.0.4.jar \
                    $C/org/microemu/microemu-midp/2.0.3/microemu-midp-2.0.3.jar
fetch lib/jsr82.jar $C/org/microemu/microemu-jsr-82/2.0.4/microemu-jsr-82-2.0.4.jar \
                    $C/net/sf/bluecove/bluecove/2.1.0/bluecove-2.1.0.jar \
                    $C/net/sf/bluecove/bluecove/2.1.1/bluecove-2.1.1.jar

if [ ! -f lib/proguard.jar ]; then
  for v in 7.5.0 7.4.2 7.3.2; do
    if curl -fsSL -o build/pg.zip "https://github.com/Guardsquare/proguard/releases/download/v$v/proguard-$v.zip"; then
      unzip -q -j build/pg.zip "proguard-$v/lib/proguard.jar" -d lib
      rm -f build/pg.zip
      echo "OK   lib/proguard.jar (ProGuard $v)"
      break
    fi
    echo "FAIL ProGuard $v"
  done
fi
[ -f lib/proguard.jar ] || { echo "ProGuard download failed" >&2; exit 1; }

# 1. compile against the CLDC/MIDP/JSR-82 stubs
javac --release 8 -Xlint:-options \
  -cp "lib/cldc.jar:lib/midp.jar:lib/jsr82.jar" \
  -d build/classes BtRelay.java
jar cf build/raw.jar -C build/classes .

# 2. downgrade to old class format and preverify for Java ME
java -jar lib/proguard.jar @proguard.cfg

# 3. repack with the MIDlet manifest
(cd build/out && unzip -q ../pg.jar -x 'META-INF/*')
jar cfm dist/BtRelay.jar MANIFEST.MF -C build/out .

# 4. write the JAD with the real jar size
SIZE=$(wc -c < dist/BtRelay.jar | tr -d ' ')
sed "s/@SIZE@/$SIZE/" BtRelay.jad.template > dist/BtRelay.jad
echo "Built dist/BtRelay.jar ($SIZE bytes) and dist/BtRelay.jad"
