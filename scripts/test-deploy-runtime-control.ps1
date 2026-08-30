#Requires -Version 5.1

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "deploy-runtime-control.ps1")

$testsPassed = 0

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if ($Expected -ne $Actual) {
        throw "$Message Expected '$Expected' but got '$Actual'."
    }
}

function Assert-Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Operation,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    try {
        & $Operation
    } catch {
        if ($_.Exception.Message -notlike $Pattern) {
            throw "Expected error '$Pattern' but got '$($_.Exception.Message)'."
        }
        return
    }
    throw "Expected operation to fail with '$Pattern'."
}

function Invoke-Test {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Test
    )

    & $Test
    $script:testsPassed++
    Write-Host "PASS $Name"
}

function New-TestProcess {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][int]$ParentProcessId,
        [string]$Repository = "C:\homeServers"
    )

    return [pscustomobject]@{
        ProcessId = $ProcessId
        ParentProcessId = $ParentProcessId
        ExecutablePath = "$Repository\runtime\process-$ProcessId.exe"
        CommandLine = "$Repository\runtime\process-$ProcessId.exe serve"
    }
}

Invoke-Test "selects only the root of one runtime process tree" {
    $processes = @(
        New-TestProcess -ProcessId 100 -ParentProcessId 50
        New-TestProcess -ProcessId 200 -ParentProcessId 100
        New-TestProcess -ProcessId 300 -ParentProcessId 100
    )

    $roots = @(Select-RuntimeProcessRoots -Processes $processes)

    Assert-Equal 1 $roots.Count "Exactly one root should be selected."
    Assert-Equal 100 $roots[0].ProcessId "The parent listener should be the root."
}

Invoke-Test "stops a parent tree only once when it owns child listeners" {
    $processes = @(
        New-TestProcess -ProcessId 100 -ParentProcessId 50
        New-TestProcess -ProcessId 200 -ParentProcessId 100
        New-TestProcess -ProcessId 300 -ParentProcessId 100
    )
    $state = @{
        Existing = @{ 100 = $true; 200 = $true; 300 = $true }
        Calls = [System.Collections.Generic.List[int]]::new()
    }
    $processExists = {
        param([int]$ProcessId)
        return $state.Existing.ContainsKey($ProcessId)
    }.GetNewClosure()
    $stopTree = {
        param([int]$ProcessId)
        $state.Calls.Add($ProcessId)
        $state.Existing.Clear()
        return [pscustomobject]@{ ExitCode = 0; Output = @("stopped") }
    }.GetNewClosure()

    Stop-ProjectRuntimeProcessTrees `
        -Processes $processes `
        -Repository "C:\homeServers" `
        -ProcessExists $processExists `
        -StopProcessTree $stopTree `
        -WriteLog { param([string]$Message) }

    Assert-Equal 1 $state.Calls.Count "Only one tree stop should run."
    Assert-Equal 100 $state.Calls[0] "The root process should be stopped."
}

Invoke-Test "stops a remaining child when its parent already disappeared" {
    $processes = @(
        New-TestProcess -ProcessId 100 -ParentProcessId 50
        New-TestProcess -ProcessId 200 -ParentProcessId 100
    )
    $state = @{
        Existing = @{ 200 = $true }
        Calls = [System.Collections.Generic.List[int]]::new()
    }
    $processExists = {
        param([int]$ProcessId)
        return $state.Existing.ContainsKey($ProcessId)
    }.GetNewClosure()
    $stopTree = {
        param([int]$ProcessId)
        $state.Calls.Add($ProcessId)
        $state.Existing.Remove($ProcessId)
        return [pscustomobject]@{ ExitCode = 0; Output = @("stopped") }
    }.GetNewClosure()

    Stop-ProjectRuntimeProcessTrees `
        -Processes $processes `
        -Repository "C:\homeServers" `
        -ProcessExists $processExists `
        -StopProcessTree $stopTree `
        -WriteLog { param([string]$Message) }

    Assert-Equal 1 $state.Calls.Count "Only the remaining child should be stopped."
    Assert-Equal 200 $state.Calls[0] "The remaining child process should be stopped."
}

Invoke-Test "accepts a nonzero stop result when the process disappeared" {
    $process = New-TestProcess -ProcessId 100 -ParentProcessId 50
    $state = @{ Existing = $true; Calls = 0 }
    $processExists = {
        param([int]$ProcessId)
        return $state.Existing
    }.GetNewClosure()
    $stopTree = {
        param([int]$ProcessId)
        $state.Calls++
        $state.Existing = $false
        return [pscustomobject]@{ ExitCode = 128; Output = @("process not found") }
    }.GetNewClosure()

    Stop-ProjectRuntimeProcessTrees `
        -Processes @($process) `
        -Repository "C:\homeServers" `
        -ProcessExists $processExists `
        -StopProcessTree $stopTree `
        -WriteLog { param([string]$Message) }

    Assert-Equal 1 $state.Calls "The process tree stop should be attempted once."
}

Invoke-Test "captures taskkill stderr and exit code without terminating early" {
    $result = Invoke-TaskKillProcessTree -ProcessId ([int]::MaxValue)

    if ($result.ExitCode -eq 0) {
        throw "taskkill should report a nonzero exit code for a nonexistent PID."
    }
    if (@($result.Output).Count -eq 0) {
        throw "taskkill diagnostic output should be captured."
    }
}

Invoke-Test "rejects a nonzero stop result when the process remains" {
    $process = New-TestProcess -ProcessId 100 -ParentProcessId 50
    $processExists = { param([int]$ProcessId) return $true }
    $stopTree = {
        param([int]$ProcessId)
        return [pscustomobject]@{ ExitCode = 1; Output = @("access denied") }
    }

    Assert-Throws -Pattern "Failed to stop project runtime PID 100.*" -Operation {
        Stop-ProjectRuntimeProcessTrees `
            -Processes @($process) `
            -Repository "C:\homeServers" `
            -ProcessExists $processExists `
            -StopProcessTree $stopTree `
            -WriteLog { param([string]$Message) }
    }
}

Invoke-Test "refuses to stop a process outside the repository" {
    $process = New-TestProcess -ProcessId 100 -ParentProcessId 50 -Repository "C:\other"

    Assert-Throws -Pattern "Refusing to stop PID 100*" -Operation {
        Stop-ProjectRuntimeProcessTrees `
            -Processes @($process) `
            -Repository "C:\homeServers" `
            -ProcessExists { param([int]$ProcessId) return $true } `
            -StopProcessTree { throw "must not be called" } `
            -WriteLog { param([string]$Message) }
    }
}

Invoke-Test "starts a stopped runtime during failure recovery" {
    $state = @{ TaskState = "Ready"; Starts = 0; HealthChecks = 0 }
    $getTaskState = { return $state.TaskState }.GetNewClosure()
    $startTask = {
        $state.Starts++
        $state.TaskState = "Running"
    }.GetNewClosure()
    $testHealth = {
        $state.HealthChecks++
        return $true
    }.GetNewClosure()

    Restore-RuntimeAfterFailure `
        -RuntimeTaskName "HomeSecondBrain" `
        -GetTaskState $getTaskState `
        -StartTask $startTask `
        -TestHealth $testHealth `
        -WriteLog { param([string]$Message) }

    Assert-Equal 1 $state.Starts "A stopped task should be started once."
    Assert-Equal 1 $state.HealthChecks "Recovery should verify health."
}

Invoke-Test "does not start an already running runtime during failure recovery" {
    $state = @{ Starts = 0 }
    $startTask = { $state.Starts++ }.GetNewClosure()

    Restore-RuntimeAfterFailure `
        -RuntimeTaskName "HomeSecondBrain" `
        -GetTaskState { return "Running" } `
        -StartTask $startTask `
        -TestHealth { return $true } `
        -WriteLog { param([string]$Message) }

    Assert-Equal 0 $state.Starts "A running task should not be started again."
}

Invoke-Test "reports recovery failure when health remains unavailable" {
    Assert-Throws -Pattern "Scheduled task 'HomeSecondBrain' recovery did not become healthy.*" -Operation {
        Restore-RuntimeAfterFailure `
            -RuntimeTaskName "HomeSecondBrain" `
            -GetTaskState { return "Ready" } `
            -StartTask { } `
            -TestHealth { return $false } `
            -WriteLog { param([string]$Message) }
    }
}

Write-Host "$testsPassed deployment runtime control tests passed."
