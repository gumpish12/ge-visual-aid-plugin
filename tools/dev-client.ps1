<#
===============================================================================
  dev-client.ps1 - start, stop and inspect the IntelliJ dev RuneLite from a
  command line, so neither Josh nor Claude has to drive the IDE to test a
  plugin change.

  WHY THIS EXISTS
  Every plugin change needs the dev client restarted before it can be seen in
  127.0.0.1:8081. Going through IntelliJ's Run button meant Josh doing it by
  hand each time and then telling Claude it was done - which on 2026-10-18
  happened six times in one session, and twice the check that followed was
  run against a client that had not been restarted yet.

  WHAT IT LAUNCHES
  Exactly what IntelliJ's run configuration launches: the "run" task in
  build.gradle, which is a JavaExec of com.ge.gevisualaid.GEVisualAidTest with
  --developer-mode --debug --insecure-skip-tls-verification. Same classpath,
  same JVM args, same credentials file. This is NOT a second way to build the
  plugin - it is the same way, from a different button.

  HOW IT FINDS THE CLIENT (and why it cannot kill the wrong thing)
  There are normally FOUR java.exe processes on this PC: three Gradle daemons
  and the client. They are told apart by the classpath: only the client has
  this repo's build\classes on it. A daemon's command line has
  gradle-launcher.jar and never the repo. Stop matches on the repo path AND
  prints the command line it matched BEFORE killing anything, so a wrong
  match is visible rather than silent. IntelliJ itself is idea64.exe and is
  not a candidate at all.

  READY MEANS ANSWERING, NOT LAUNCHED
  Start polls 127.0.0.1:8081/state until the plugin replies, and reports the
  version that answered. "I started it" and "it is up" are different claims
  and this suite has been bitten by the difference - a VM running month-old
  plugin source publishes a perfectly healthy /state.

  USAGE
    tools\dev-client.ps1 status
    tools\dev-client.ps1 start      [-TimeoutSec 180] [-Pinned]
    tools\dev-client.ps1 stop
    tools\dev-client.ps1 restart
    tools\dev-client.ps1 log        [-Tail 60]

  THE LOG LIVES OUTSIDE THE REPO, deliberately - same reason launch.bat's
  does. A file written inside the working tree dirties it and makes the next
  "git pull --ff-only" refuse.
===============================================================================
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'start', 'stop', 'restart', 'log')]
    [string]$Action = 'status',

    # How long to wait for /state to answer before calling it a failure. A
    # cold Gradle daemon plus a client launch is routinely over a minute.
    [int]$TimeoutSec = 180,

    # Launch against the known-good RuneLite instead of latest.release, the
    # same escape hatch launch.bat has. Use when a release breaks the build.
    [switch]$Pinned,

    [int]$Tail = 60
)

$ErrorActionPreference = 'Stop'

$Repo    = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $Repo 'gradlew.bat'
$LogFile = Join-Path $env:USERPROFILE 'ge-visual-aid-devclient.log'
$StateUrl = 'http://127.0.0.1:8081/state'

# A LAUNCH IN FLIGHT IS NOT A RUNNING CLIENT, AND BOTH HAVE TO BLOCK A START.
# For the thirty-odd seconds gradle spends building there is no client
# process, so a guard that asks only "is a client running?" says no and
# launches a second build. That is how two clients appeared on 2026-10-19:
# the loser of the race for port 8081 runs with no HTTP server at all -
# alive, and invisible to every check that matters. The lock records the
# launching process so the window is covered.
$LockFile = Join-Path $env:USERPROFILE 'ge-visual-aid-devclient.lock'
$LockMaxAgeMin = 15

# Keep in step with launch.bat's PINNED. It is a known-good - a version
# watched working - not a preference.
$PinnedVersion = '1.13.2'

# The one string that separates the client from the Gradle daemons.
$ClientMarker = (Join-Path $Repo 'build\classes')

function Write-Note([string]$msg) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Write-Host $line
    try { Add-Content -Path $LogFile -Value $line -Encoding utf8 } catch { }
}

function Get-DevClient {
    # Win32_Process, not Get-Process: only CIM exposes the command line, and
    # the command line is the whole discriminator here.
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue
    if (-not $procs) { return @() }
    @($procs | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ClientMarker) })
}

# Any gradle/cmd process launching THIS repo. Used by stop: a build left
# running after a kill will cheerfully produce a client a minute later, so a
# "stopped" client comes back by itself and nothing says why.
function Get-LaunchProcs {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and
            $_.CommandLine -match [regex]::Escape($Repo) -and
            $_.CommandLine -match 'gradlew' -and
            $_.ProcessId -ne $PID
        }
    @($procs)
}

# The lock is honoured only while its process is ALIVE and it is recent. A
# stale lock from a script that was interrupted must not block every future
# start - a safety that latches on is a fault of its own.
function Get-ActiveLock {
    if (-not (Test-Path $LockFile)) { return $null }
    try {
        $raw = (Get-Content $LockFile -Raw).Trim() -split ','
        $lockPid = [int]$raw[0]
        $when    = [datetime]::Parse($raw[1])
        if (((Get-Date) - $when).TotalMinutes -gt $LockMaxAgeMin) { Remove-Item $LockFile -Force; return $null }
        $p = Get-Process -Id $lockPid -ErrorAction SilentlyContinue
        if ($null -eq $p) { Remove-Item $LockFile -Force; return $null }
        return [pscustomobject]@{ Pid = $lockPid; Started = $when }
    } catch { Remove-Item $LockFile -Force -ErrorAction SilentlyContinue; return $null }
}

function Clear-Lock { Remove-Item $LockFile -Force -ErrorAction SilentlyContinue }

function Get-PluginState {
    try {
        $r = Invoke-WebRequest -Uri $StateUrl -TimeoutSec 4 -UseBasicParsing
        $h = @{}
        foreach ($line in ($r.Content -split "`n")) {
            $i = $line.IndexOf('=')
            if ($i -gt 0) { $h[$line.Substring(0, $i)] = $line.Substring($i + 1).Trim() }
        }
        return $h
    } catch { return $null }
}

function Show-Status {
    $procs = Get-DevClient
    if ($procs.Count -eq 0) {
        Write-Host "process : not running"
    } else {
        foreach ($p in $procs) {
            $up = (Get-Date) - $p.CreationDate
            Write-Host ("process : pid {0}, up {1:hh\:mm\:ss}" -f $p.ProcessId, $up)
        }
        if ($procs.Count -gt 1) {
            Write-Host "WARNING : more than one dev client is running. That should not happen;"
            Write-Host "          'stop' will end all of them."
        }
    }
    $s = Get-PluginState
    if ($null -eq $s) {
        Write-Host "feed    : not answering on 127.0.0.1:8081"
        if ($procs.Count -gt 0) {
            Write-Host "          (the process is up but the HTTP server is not - still starting,"
            Write-Host "           or the HTTP Server setting is switched off in the plugin)"
        }
    } else {
        Write-Host ("feed    : plugin {0}   {1}   RuneLite {2}" -f `
            $s['plugin_output_version'], $s['game_state'], $s['runelite_version'])
        if ($s['slayer_state']) { Write-Host ("slayer  : {0}  {1}" -f $s['slayer_state'], $s['slayer_task']) }
    }
}

function Stop-DevClient {
    # THE BUILD GOES FIRST. Killing the client while a gradle run is still in
    # flight gets you a client again a minute later, out of nowhere, with
    # nothing to point at. Stop means stopped.
    foreach ($g in Get-LaunchProcs) {
        Write-Note "stop: killing in-flight launcher pid $($g.ProcessId)"
        try { Stop-Process -Id $g.ProcessId -Force -ErrorAction Stop } catch { }
    }
    Clear-Lock

    $procs = Get-DevClient
    if ($procs.Count -eq 0) { Write-Note "stop: no dev client running"; return $true }
    if ($procs.Count -gt 1) { Write-Note "stop: $($procs.Count) clients found - killing all of them" }
    foreach ($p in $procs) {
        # PRINT WHAT IS ABOUT TO DIE, BEFORE KILLING IT. A wrong match has to
        # be visible in the log afterwards, not inferred from what went
        # missing.
        $cmd = $p.CommandLine
        if ($cmd.Length -gt 200) { $cmd = $cmd.Substring(0, 200) + '...' }
        Write-Note "stop: killing pid $($p.ProcessId)"
        Write-Note "stop: matched -> $cmd"
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop }
        catch { Write-Note "stop: FAILED on pid $($p.ProcessId) - $($_.Exception.Message)" }
    }
    # Confirm the OUTCOME, never the intent - the /plugin lesson from 2.73.
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 300
        if ((Get-DevClient).Count -eq 0) { Write-Note "stop: confirmed gone"; return $true }
    }
    Write-Note "stop: STILL RUNNING after 6s - something is holding it"
    return $false
}

function Start-DevClient {
    if ((Get-DevClient).Count -gt 0) {
        # Never launch a second one. launch.bat carries a long comment about
        # how a stray retry starts a second client hours later; the same
        # mistake here would have two plugins fighting over port 8081, and
        # the loser fails quietly.
        Write-Note "start: a dev client is ALREADY running - refusing to start a second"
        Show-Status
        return $false
    }
    # ...AND REFUSE WHILE ONE IS STILL BEING BUILT. This is the case that
    # actually bit: the check above is false for the whole build window, so
    # without this a second start during those thirty seconds produces two
    # clients. Both the lock and a live gradle count, because either alone
    # can be missing - the lock if the script was interrupted, the process
    # match if gradle has handed off to a daemon.
    $lock = Get-ActiveLock
    $busy = Get-LaunchProcs
    if ($lock -or $busy.Count -gt 0) {
        $why = if ($lock) { "a launch started $($lock.Started.ToString('HH:mm:ss')) is still in flight (pid $($lock.Pid))" }
               else       { "gradle is still building (pid $($busy[0].ProcessId))" }
        Write-Note "start: REFUSING - $why"
        Write-Note "start: wait for it, or 'stop' first to clear it"
        return $false
    }

    $argList = @('run')
    if ($Pinned) { $argList += "-PruneliteVersion=$PinnedVersion" }
    Write-Note "start: $Gradlew $($argList -join ' ')"
    Write-Note "start: log -> $LogFile"

    # Detached, with output captured. Without redirection the client's stdout
    # dies with the launching shell and a crash leaves nothing to read.
    $outLog = Join-Path $env:USERPROFILE 'ge-visual-aid-devclient.out.log'
    # -PassThru because the GRADLE process is the thing that tells us whether
    # we are still building or have actually failed. Without it the loop
    # below can only see "no client yet", which is true for the first thirty
    # seconds of every successful launch.
    $gradle = Start-Process -FilePath $Gradlew -ArgumentList $argList -WorkingDirectory $Repo `
        -WindowStyle Minimized -RedirectStandardOutput $outLog `
        -RedirectStandardError (Join-Path $env:USERPROFILE 'ge-visual-aid-devclient.err.log') -PassThru
    Set-Content -Path $LockFile -Value ("{0},{1}" -f $gradle.Id, (Get-Date).ToString('o')) -Encoding utf8

    # READY MEANS ANSWERING. Gradle has to resolve, compile and launch before
    # the HTTP server exists, which is routinely a minute from cold.
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $s = Get-PluginState
        if ($null -ne $s) {
            Write-Note ("start: UP - plugin {0}, {1}, RuneLite {2}" -f `
                $s['plugin_output_version'], $s['game_state'], $s['runelite_version'])
            # EVERY exit path clears the lock. A safety that latches on after
            # one failure blocks every later start and becomes its own fault -
            # which is worse than the thing it was guarding, because the
            # guard looks like it is working.
            Clear-Lock
            return $true
        }
        # ONLY GRADLE EXITING MEANS FAILURE. The first version of this checked
        # "no client process yet" and declared failure five seconds in, while
        # the build was still printing "Task :classes UP-TO-DATE" - it killed
        # a healthy client and then reported a compile error that had not
        # happened. "Not there yet" and "not coming" look identical from one
        # sample; gradlew still running is what tells them apart.
        if ($gradle.HasExited -and (Get-DevClient).Count -eq 0) {
            Write-Note "start: FAILED - gradle exited ($($gradle.ExitCode)) and no client is running."
            Write-Note "start: see $outLog"
            if (Test-Path $outLog) { Get-Content $outLog -Tail 25 | ForEach-Object { Write-Host "   $_" } }
            Clear-Lock
            return $false
        }
    }
    Write-Note "start: TIMED OUT after ${TimeoutSec}s - process is up but the feed never answered"
    Write-Note "start: check the HTTP Server setting in the plugin panel, and $outLog"
    Clear-Lock
    return $false
}

switch ($Action) {
    'status' { Show-Status }
    'stop'   { if (-not (Stop-DevClient)) { exit 1 } }
    'start'  { if (-not (Start-DevClient)) { exit 1 } }
    'restart' {
        if (-not (Stop-DevClient)) { exit 1 }
        # Give the OS a moment to release port 8081. Binding before it is
        # free fails with "address already in use" and the plugin then has no
        # HTTP server for the whole session - running, and unreachable.
        Start-Sleep -Seconds 2
        if (-not (Start-DevClient)) { exit 1 }
    }
    'log' {
        if (Test-Path $LogFile) { Get-Content $LogFile -Tail $Tail }
        else { Write-Host "no log yet at $LogFile" }
        $outLog = Join-Path $env:USERPROFILE 'ge-visual-aid-devclient.out.log'
        if (Test-Path $outLog) {
            Write-Host "`n---- gradle output (last $Tail) ----"
            Get-Content $outLog -Tail $Tail
        }
    }
}
