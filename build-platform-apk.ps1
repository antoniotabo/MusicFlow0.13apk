param(
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Java = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$DebugKeystore = "$env:USERPROFILE/.android/debug.keystore",
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '../../work/platform-build')
)
# Fallback build. APK is restricted to Android 13+; Gradle builds support Android 8+ with core library desugaring.
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Java
$env:Path = "$Java/bin;$env:Path"
$platform = Join-Path $Sdk 'platforms/android-35/android.jar'
$buildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools -or !(Test-Path $platform)) { throw 'Android SDK 35 and build-tools are required.' }
$bt = $buildTools.FullName
$runDir = Join-Path $BuildDirectory ([DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force "$runDir/classes", "$runDir/dex", "$runDir/generated" | Out-Null
$sourceRoot = Join-Path $PSScriptRoot 'app/src/main'
[xml]$manifest = Get-Content (Join-Path $sourceRoot 'AndroidManifest.xml')
$manifest.DocumentElement.SetAttribute('package', 'com.musicflow.app')
$manifest.Save((Join-Path $runDir 'AndroidManifest.xml'))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$playerJars = @()
$resourceArgs = @()
$resourcePackages = @()
Get-ChildItem "$PSScriptRoot/app/player-libs" -Filter '*.aar' | ForEach-Object {
    $expanded = Join-Path $runDir $_.BaseName
    [System.IO.Compression.ZipFile]::ExtractToDirectory($_.FullName, $expanded)
    if (Test-Path "$expanded/classes.jar") { $playerJars += "$expanded/classes.jar" }
    if (Test-Path "$expanded/libs") { $playerJars += @(Get-ChildItem "$expanded/libs" -Filter '*.jar' | ForEach-Object FullName) }
    [xml]$libraryManifest = Get-Content "$expanded/AndroidManifest.xml"
    $resourcePackages += [string]$libraryManifest.manifest.package
    if ((Test-Path "$expanded/res") -and (Get-ChildItem "$expanded/res" -File -Recurse | Select-Object -First 1)) {
        & "$bt/aapt2.exe" compile --dir "$expanded/res" -o "$expanded/resources.zip"
        if ($LASTEXITCODE -ne 0) { throw "Library resource compilation failed: $expanded" }
        $resourceArgs += @('-R', "$expanded/resources.zip")
    }
}
& "$bt/aapt2.exe" compile --dir "$sourceRoot/res" -o "$runDir/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
& "$bt/aapt2.exe" link -o "$runDir/base.apk" -I $platform --manifest "$runDir/AndroidManifest.xml" --java "$runDir/generated" --extra-packages ($resourcePackages -join ':') --auto-add-overlay --min-sdk-version 33 --target-sdk-version 35 --version-code 13 --version-name '0.13.0' @resourceArgs -R "$runDir/resources.zip"
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed.' }
$sources = @(Get-ChildItem "$sourceRoot/java" -Filter '*.java' -Recurse | ForEach-Object FullName)
$sources += @(Get-ChildItem "$runDir/generated" -Filter '*.java' -Recurse | ForEach-Object FullName)
$libraries = @(Get-ChildItem "$PSScriptRoot/app/libs" -Filter "*.jar" | ForEach-Object FullName)
$libraries += @(Get-ChildItem "$PSScriptRoot/app/player-libs" -Filter '*.jar' | ForEach-Object FullName)
$libraries += $playerJars
$classPath = (@($platform) + $libraries) -join ";"
& "$Java/bin/javac.exe" --release 17 -classpath $classPath -d "$runDir/classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$classes = @(Get-ChildItem "$runDir/classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
$dexArgs = @('--lib', $platform, '--min-api', '33', '--output', "$runDir/dex") + $classes + $libraries
($dexArgs | ForEach-Object { $_.Replace('\', '/') }) | Set-Content "$runDir/d8.args" -Encoding utf8NoBOM
& "$Java/bin/java.exe" -cp "$bt/lib/d8.jar" com.android.tools.r8.D8 "@$runDir/d8.args"
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open("$runDir/base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    Get-ChildItem "$runDir/dex" -Filter "*.dex" | ForEach-Object { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $_.Name) | Out-Null }
    Get-ChildItem "$sourceRoot/assets" -File | ForEach-Object { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, 'assets/' + $_.Name) | Out-Null }
} finally { $archive.Dispose() }
& "$bt/zipalign.exe" -f -p 4 "$runDir/base.apk" "$runDir/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
$debugKey = $DebugKeystore
if (!(Test-Path $debugKey)) {
    $debugKey = "$runDir/debug.keystore"
    & "$Java/bin/keytool.exe" -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -validity 3650
    if ($LASTEXITCODE -ne 0) { throw 'Debug key generation failed.' }
}
$apk = Join-Path $PSScriptRoot '../MusicFlow-debug.apk'
& "$bt/apksigner.bat" sign --ks $debugKey --ks-pass pass:android --key-pass pass:android --out $apk "$runDir/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& "$bt/apksigner.bat" verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed.' }
Write-Host "APK ready: $apk"

