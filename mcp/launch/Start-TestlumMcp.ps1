<#
Starts the Testlum MCP server for one client session and removes it when the session ends.
Point the MCP client config at this script. Never leave the container running.

  Start-TestlumMcp.ps1 [-TestsDir <folder>]   TestsDir is optional and defaults to the current folder, which is where the MCP client starts it; it is mounted at /work in the container
  Start-TestlumMcp.ps1 -Cleanup               remove any leftover testlum-mcp containers

Env: TESTLUM_MCP_IMAGE (default testlum-mcp:0.2.0), TESTLUM_PASS_ENV (comma list of env var names to
forward, no values here), TESTLUM_ENGINE_JAR_HOST (engine jar to mount instead of the image's),
TESTLUM_MCP_IDLE_SECONDS (default 900), TESTLUM_MCP_JAR (server jar for the no-Docker path).
Without a usable Docker it runs the jar as a plain stdio process.
#>
param(
    [string]$TestsDir = (Get-Location).Path,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'
$image = if ($env:TESTLUM_MCP_IMAGE) { $env:TESTLUM_MCP_IMAGE } else { 'testlum-mcp:0.2.0' }
function Say([string]$m) { [Console]::Error.WriteLine("testlum-mcp: $m") }

$docker = Get-Command docker -ErrorAction SilentlyContinue

if ($Cleanup) {
    if (-not $docker) { Say 'docker not found, nothing to clean'; exit 0 }
    $ids = @(& docker ps -aq --filter 'label=app=testlum-mcp' 2>$null)
    if ($ids.Count -gt 0) { & docker rm -f $ids 2>&1 | ForEach-Object { Say $_ } } else { Say 'no leftover containers' }
    exit 0
}

function Test-DockerReady {
    if (-not $docker) { return $false }
    $job = Start-Job { docker info *> $null; $LASTEXITCODE }
    if (Wait-Job $job -Timeout 10) { $code = Receive-Job $job; Remove-Job $job -Force; return ($code -eq 0) }
    Stop-Job $job; Remove-Job $job -Force; return $false
}

if (Test-DockerReady) {
    & docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) {
        Say "image $image not found, building it"
        & docker build -t $image (Join-Path $PSScriptRoot '..') 2>&1 | ForEach-Object { Say $_ }
        if ($LASTEXITCODE -ne 0) { Say 'build failed'; exit 1 }
    }
    $idle = if ($env:TESTLUM_MCP_IDLE_SECONDS) { $env:TESTLUM_MCP_IDLE_SECONDS } else { '900' }
    $runArgs = @('run', '--rm', '-i', '--name', "testlum-mcp-$PID", '--label', 'app=testlum-mcp',
        '--memory', '2g', '--cpus', '2', '--add-host=host.docker.internal:host-gateway', '-e', "TESTLUM_MCP_IDLE_SECONDS=$idle", '-v', "${TestsDir}:/work")
    if ($env:TESTLUM_ENGINE_JAR_HOST) { $runArgs += @('-v', "$($env:TESTLUM_ENGINE_JAR_HOST):/testlum/testlum-final.jar:ro") }
    if ($env:TESTLUM_PASS_ENV) {
        foreach ($n in $env:TESTLUM_PASS_ENV.Split(',')) { if ($n.Trim()) { $runArgs += @('-e', $n.Trim()) } }
    }
    $runArgs += $image
    & docker @runArgs
    exit $LASTEXITCODE
}

$jar = $env:TESTLUM_MCP_JAR
if (-not $jar -or -not (Test-Path $jar)) { $jar = Join-Path $PSScriptRoot '..\target\testlum-mcp.jar' }
if (-not (Test-Path $jar)) { Say 'Docker is not available and no server jar was found (set TESTLUM_MCP_JAR)'; exit 1 }
$jar = (Resolve-Path $jar).Path
Say "Docker not available, running $jar as a plain process"
Set-Location $TestsDir
& java -jar $jar
exit $LASTEXITCODE
