#!/usr/bin/env bash
# Builds midlet/dist/BtRelay.jar + BtRelay.jad
# Needs: JDK 11+ (javac, jar), curl, unzip.
set -e
cd "$(dirname "$0")"
mkdir -p lib build/classes build/out dist
rm -rf build/classes/* build/out/* build/raw.jar build/pg.jar dist/*

M=https://repo1.maven.org/maven2/org/microemu
[ -f lib/microemu-cldc.jar ]   || curl -fsSL -o lib/microemu-cldc.jar   $M/microemu-cldc/2.0.4/microemu-cldc-2.0.4.jar
[ -f lib/microemu-midp.jar ]   || curl -fsSL -o lib/microemu-midp.jar   $M/microemu-midp/2.0.4/microemu-midp-2.0.4.jar
[ -f lib/microemu-jsr-82.jar ] || curl -fsSL -o lib/microemu-jsr-82.jar $M/microemu-jsr-82/2.0.4/microemu-jsr-82-2.0.4.jar
if [ ! -f lib/proguard.jar ]; then
  curl -fsSL -o build/pg.zip https://github.com/Guardsquare/proguard/releases/download/v7.5.0/proguard-7.5.0.zip
  unzip -q -j build/pg.zip 'proguard-7.5.0/lib/proguard.jar' -d lib
  rm build/pg.zip
fi

# 1. compile against the CLDC/MIDP/JSR-82 stubs
javac --release 8 -Xlint:-options \
  -cp "lib/microemu-cldc.jar:lib/microemu-midp.jar:lib/microemu-jsr-82.jar" \
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
