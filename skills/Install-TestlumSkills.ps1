<#
Copies the Testlum skills to Claude Code (~/.claude/skills) and Codex (~/.agents/skills).
  Install-TestlumSkills.ps1                 install the skills
  Install-TestlumSkills.ps1 -CodexMcp       also add the testlum server to ~/.codex/config.toml if it is not there
  Install-TestlumSkills.ps1 -RemoveOld      also remove the old single "testlum" skill
#>
param(
    [switch]$CodexMcp,
    [switch]$RemoveOld
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path (Split-Path -Parent $here) 'mcp\launch\Start-TestlumMcp.ps1'

foreach ($dest in @((Join-Path $HOME '.claude\skills'), (Join-Path $HOME '.agents\skills'))) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    foreach ($s in Get-ChildItem -Directory -Path $here -Filter 'testlum-*') {
        $target = Join-Path $dest $s.Name
        if (Test-Path $target) { Remove-Item -Recurse -Force $target }
        Copy-Item -Recurse $s.FullName $target
    }
    Write-Host "installed to $dest"
}

if ($RemoveOld) {
    foreach ($old in @((Join-Path $HOME '.claude\skills\testlum'), (Join-Path $HOME '.agents\skills\testlum'))) {
        if (Test-Path $old) { Remove-Item -Recurse -Force $old }
    }
    Write-Host 'removed the old testlum skill'
}

if ($CodexMcp) {
    $dir = Join-Path $HOME '.codex'
    $cfg = Join-Path $dir 'config.toml'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if ((Test-Path $cfg) -and (Select-String -Path $cfg -Pattern '^\[mcp_servers\.testlum\]' -Quiet)) {
        Write-Host 'codex config already has testlum'
    } else {
        $path = $launcher.Replace('\', '/')
        $block = "`n[mcp_servers.testlum]`ncommand = `"pwsh`"`nargs = [`"-NoProfile`", `"-File`", `"$path`"]`nstartup_timeout_sec = 120`ntool_timeout_sec = 600`n"
        Add-Content -Path $cfg -Value $block
        Write-Host "added testlum to $cfg"
    }
}
