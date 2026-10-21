param([switch]$InstallTools)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$toolsRoot = Join-Path $project '.tools'
if ($InstallTools) {
    New-Item -ItemType Directory -Force $toolsRoot | Out-Null
    if (!(Test-Path "$toolsRoot/jdk")) {
        Write-Host 'Downloading Java 17...'
        Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile "$toolsRoot/java.zip"
        Expand-Archive "$toolsRoot/java.zip" "$toolsRoot/java" -Force
        $jdkFolder = Get-ChildItem "$toolsRoot/java" -Directory | Select-Object -First 1
        Move-Item -LiteralPath $jdkFolder.FullName -Destination "$toolsRoot/jdk"
    }
    if (!(Test-Path "$toolsRoot/gradle-8.9/bin/gradle.bat")) {
        Write-Host 'Downloading Gradle 8.9...'
        Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile "$toolsRoot/gradle.zip"
        Expand-Archive "$toolsRoot/gradle.zip" $toolsRoot -Force
    }
    if (!(Test-Path "$toolsRoot/android/cmdline-tools/latest/bin/sdkmanager.bat")) {
        Write-Host 'Downloading Android command-line tools...'
        Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$toolsRoot/android.zip"
        Expand-Archive "$toolsRoot/android.zip" "$toolsRoot/android-unpack" -Force
        New-Item -ItemType Directory -Force "$toolsRoot/android/cmdline-tools" | Out-Null
        Move-Item -LiteralPath "$toolsRoot/android-unpack/cmdline-tools" -Destination "$toolsRoot/android/cmdline-tools/latest"
    }
}
if (Test-Path "$toolsRoot/jdk") { $env:JAVA_HOME = "$toolsRoot/jdk" }
if (Test-Path "$toolsRoot/android") { $env:ANDROID_HOME = "$toolsRoot/android" }
if (!$env:JAVA_HOME) { throw 'Install JDK 17 or run with -InstallTools.' }
if (!$env:ANDROID_HOME) { throw 'Install Android SDK or run with -InstallTools.' }
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:GRADLE_USER_HOME = "$toolsRoot/gradle-cache"
$sdkManager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
if ($InstallTools) {
    Write-Host 'Android SDK licenses must be accepted by the developer. Review the prompts.'
    & $sdkManager --licenses
    & $sdkManager 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
}
$gradleCommand = if (Test-Path "$toolsRoot/gradle-8.9/bin/gradle.bat") { "$toolsRoot/gradle-8.9/bin/gradle.bat" } else { 'gradle' }
Push-Location $project
try {
    & $gradleCommand --no-daemon assembleDebug lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    Copy-Item 'app/build/outputs/apk/debug/app-debug.apk' '../MusicFlow-debug.apk' -Force
    Write-Host 'APK ready: ../MusicFlow-debug.apk'
} finally { Pop-Location }
