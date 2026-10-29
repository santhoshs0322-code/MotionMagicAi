param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$savedLocation = Get-Location
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'ANDROID_PREFS_ROOT', 'ANDROID_SDK_HOME', 'GRADLE_USER_HOME', 'GRADLE_RO_DEP_CACHE', 'DEBUG')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    Set-Location -LiteralPath $projectRoot
    $javaCandidates = @(
        $env:JAVA_HOME,
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        (Join-Path $env:ProgramFiles 'Java\jdk-21')
    )
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaCandidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    $selectedJava = $null
    foreach ($candidate in $javaCandidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ((Split-Path -Leaf $candidate) -eq 'bin') { $candidate = Split-Path -Parent $candidate }
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) { $selectedJava = $candidate; break }
    }
    if (-not $selectedJava) { throw 'JDK 17 or newer is required. Install Android Studio or set JAVA_HOME to the JDK directory.' }
    $env:JAVA_HOME = $selectedJava

    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $selectedSdk = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms')) } | Select-Object -First 1
    if (-not $selectedSdk) { throw 'Android SDK was not found. Set ANDROID_HOME to an SDK with API 36 and Build Tools 36.0.0.' }
    $env:ANDROID_HOME = $selectedSdk
    $env:ANDROID_SDK_ROOT = $selectedSdk
    $env:ANDROID_PREFS_ROOT = Join-Path $projectRoot '.android-user-home'
    $env:ANDROID_SDK_HOME = $env:ANDROID_PREFS_ROOT
    $env:ANDROID_USER_HOME = Join-Path $env:ANDROID_PREFS_ROOT '.android'
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    $env:DEBUG = $null
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.build-tools') | Out-Null
    $debugStore = Join-Path $projectRoot '.build-tools\debug.keystore'
    if (-not (Test-Path -LiteralPath $debugStore)) {
        & (Join-Path $selectedJava 'bin\keytool.exe') -genkeypair -keystore $debugStore -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the local debug signing key.' }
    }

    # Reuse an existing artifact cache without allowing Gradle to modify it.
    $existingCache = Join-Path $env:USERPROFILE '.gradle\caches'
    if (Test-Path -LiteralPath (Join-Path $existingCache 'modules-2')) { $env:GRADLE_RO_DEP_CACHE = $existingCache }

    $localGradle = Join-Path $projectRoot '.build-tools\gradle-8.13\bin\gradle.bat'
    $launcher = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' }
    & $launcher @Tasks --no-daemon --console=plain
    $buildExitCode = $LASTEXITCODE
}
finally {
    Set-Location -LiteralPath $savedLocation
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}

exit $buildExitCode
