#!/usr/bin/env bash
# Rebuild EatIn-debug.apk from ../index.html. Needs Java 11+ and Python 3.
# Usage: ./build.sh [versionName]   (versionCode is bumped automatically)
set -euo pipefail
cd "$(dirname "$0")"

python3 sync.py

# Bump versionCode so the new APK installs over the old one.
code=$(grep -o 'versionCode: [0-9]*' app/apktool.yml | grep -o '[0-9]*')
code=$((code + 1))
name=${1:-1.$code}
sed -i.bak "s/versionCode: [0-9]*/versionCode: $code/; s/versionName: .*/versionName: '$name'/" app/apktool.yml
sed -i.bak "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$code\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$name\"/" app/AndroidManifest.xml
rm -f app/apktool.yml.bak app/AndroidManifest.xml.bak

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Eat In Debug,O=Eat In,C=US"
fi

mkdir -p build
java -jar tools/apktool.jar --frame-path build/framework b app -o build/EatIn-unsigned.apk
java -jar tools/uber-apk-signer.jar -a build/EatIn-unsigned.apk -o build/signed \
  --ks debug.keystore --ksAlias androiddebugkey --ksPass android --ksKeyPass android
cp build/signed/*-aligned-signed.apk EatIn-debug.apk
rm -rf build app/build
echo "Built $(pwd)/EatIn-debug.apk (versionCode $code, versionName $name)"
