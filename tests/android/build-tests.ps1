param([string]$Sdk, [string]$Java='C:/Program Files/Android/Android Studio/jbr', [string]$DebugKeystore="$env:USERPROFILE/.android/debug.keystore")
$ErrorActionPreference = 'Stop'
$sdkRoot = $Sdk
$javaRoot = $Java
$env:JAVA_HOME = $javaRoot
$bt = "$sdkRoot/build-tools/36.0.0"
$source = $PSScriptRoot
$out = Join-Path $source 'build'
New-Item -ItemType Directory -Force "$out/classes", "$out/dex" | Out-Null
& "$bt/aapt2.exe" link -o "$out/base.apk" -I "$sdkRoot/platforms/android-35/android.jar" --manifest "$source/AndroidManifest.xml"
if ($LASTEXITCODE -ne 0) { throw 'Test manifest failed.' }
& "$javaRoot/bin/javac.exe" --release 17 -classpath "$sdkRoot/platforms/android-35/android.jar" -d "$out/classes" "$source/AudioSmoke.java"
if ($LASTEXITCODE -ne 0) { throw 'Test Java failed.' }
$classes = @(Get-ChildItem "$out/classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
& "$bt/d8.bat" --lib "$sdkRoot/platforms/android-35/android.jar" --min-api 26 --output "$out/dex" @classes
if ($LASTEXITCODE -ne 0) { throw 'Test DEX failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out/base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out/dex/classes.dex", 'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$bt/zipalign.exe" -f 4 "$out/base.apk" "$out/aligned.apk"
& "$bt/apksigner.bat" sign --ks $DebugKeystore --ks-pass pass:android --key-pass pass:android --out "$out/test.apk" "$out/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Test signing failed.' }
