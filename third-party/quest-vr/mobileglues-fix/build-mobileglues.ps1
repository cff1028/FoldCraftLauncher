param(
    [Parameter(Mandatory = $true)] [string]$SdkRoot,
    [Parameter(Mandatory = $true)] [string]$SourceRoot,
    [string]$BuildDirectory = '',
    [string]$OutputDirectory = '',
    [int]$Jobs = 8
)
$ErrorActionPreference = 'Stop'
$ndkRoot = Join-Path $SdkRoot 'ndk/27.0.12077973'
$cmakeRoot = Join-Path $SdkRoot 'cmake/3.22.1/bin'
$cmake = Join-Path $cmakeRoot 'cmake.exe'
$ninja = Join-Path $cmakeRoot 'ninja.exe'
$strip = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
foreach ($tool in @($cmake, $ninja, $strip)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool missing: $tool" }
}
$sourceFullPath = [IO.Path]::GetFullPath($SourceRoot)
$workRoot = Split-Path -Parent (Split-Path -Parent $sourceFullPath)
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $workRoot 'build-arm64' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $workRoot 'dist' }
$buildFullPath = [IO.Path]::GetFullPath($BuildDirectory)
$outputFullPath = [IO.Path]::GetFullPath($OutputDirectory)
$provenance = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'provenance.json') -Raw | ConvertFrom-Json
if (-not $provenance.patched_files) {
    throw 'provenance.json does not contain reviewed source hashes.'
}
foreach ($changedFile in $provenance.patched_files) {
    $file = Join-Path $sourceFullPath $changedFile.path
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $changedFile.sha256) {
        throw "Source hash mismatch: $($changedFile.path)"
    }
}
$oldEpoch = $env:SOURCE_DATE_EPOCH
$oldTimezone = $env:TZ
try {
    $env:SOURCE_DATE_EPOCH = $provenance.build.SOURCE_DATE_EPOCH
    $env:TZ = 'UTC'
    New-Item -ItemType Directory -Force -Path $buildFullPath, $outputFullPath | Out-Null
    & $cmake -S $sourceFullPath -B $buildFullPath -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$(Join-Path $ndkRoot 'build/cmake/android.toolchain.cmake')" `
        '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-21' `
        '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release' `
        '-DCMAKE_DISABLE_FIND_PACKAGE_Git=ON' 2>&1 |
        Tee-Object (Join-Path $outputFullPath 'configure.log')
    if ($LASTEXITCODE -ne 0) { throw 'CMake configuration failed.' }
    & $cmake --build $buildFullPath --target mobileglues --parallel $Jobs 2>&1 |
        Tee-Object (Join-Path $outputFullPath 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'MobileGlues build failed.' }
    $library = Join-Path $outputFullPath 'libmobileglues.so'
    & $strip --strip-unneeded -o $library (Join-Path $buildFullPath 'libmobileglues.so')
    if ($LASTEXITCODE -ne 0) { throw 'Stripping MobileGlues failed.' }
    $hash = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $outputFullPath 'libmobileglues.so.sha256'),
        "$hash  libmobileglues.so`n", [Text.UTF8Encoding]::new($false))
    Get-Item -LiteralPath $library | Select-Object FullName, Length
    Write-Output "SHA256: $hash"
} finally {
    $env:SOURCE_DATE_EPOCH = $oldEpoch
    $env:TZ = $oldTimezone
}
