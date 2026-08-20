$ErrorActionPreference = 'Stop'

$tectonicRoot = 'D:\TeX\Tectonic'
$tectonic = Join-Path $tectonicRoot 'bin\tectonic.exe'

if (-not (Test-Path -LiteralPath $tectonic)) {
    throw "Tectonic not found: $tectonic"
}

$env:TEMP = Join-Path $tectonicRoot 'temp'
$env:TMP = Join-Path $tectonicRoot 'temp'
$env:XDG_CACHE_HOME = Join-Path $tectonicRoot 'cache'
$env:XDG_CONFIG_HOME = Join-Path $tectonicRoot 'config'
$env:TECTONIC_CACHE_DIR = Join-Path $tectonicRoot 'cache'

& $tectonic -X compile (Join-Path $PSScriptRoot 'resume.tex') --outdir $PSScriptRoot
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
