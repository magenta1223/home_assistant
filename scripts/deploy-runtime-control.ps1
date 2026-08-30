function Select-RuntimeProcessRoots {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Processes)

    $processIds = @{}
    foreach ($process in $Processes) {
        $processIds[[int]$process.ProcessId] = $true
    }

    return @($Processes | Where-Object {
        -not $processIds.ContainsKey([int]$_.ParentProcessId)
    })
}

function Invoke-TaskKillProcessTree {
    param([Parameter(Mandatory = $true)][int]$ProcessId)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& taskkill.exe /PID $ProcessId /T /F 2>&1)
        $commandExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        ExitCode = $commandExitCode
        Output = $output
    }
}

function Stop-ProjectRuntimeProcessTrees {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Processes,
        [Parameter(Mandatory = $true)][string]$Repository,
        [scriptblock]$ProcessExists = {
            param([int]$ProcessId)
            return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
        },
        [scriptblock]$StopProcessTree = {
            param([int]$ProcessId)
            return Invoke-TaskKillProcessTree -ProcessId $ProcessId
        },
        [scriptblock]$WriteLog = {
            param([string]$Message)
            Write-Host $Message
        }
    )

    foreach ($process in $Processes) {
        $evidence = "$($process.ExecutablePath) $($process.CommandLine)"
        if ($evidence.IndexOf($Repository, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Refusing to stop PID $($process.ProcessId) because it is not owned by repository '$Repository'."
        }
    }

    $rootProcesses = @(Select-RuntimeProcessRoots -Processes $Processes)
    $rootProcessIds = @{}
    foreach ($process in $rootProcesses) {
        $rootProcessIds[[int]$process.ProcessId] = $true
    }
    $orderedProcesses = @($rootProcesses) + @($Processes | Where-Object {
        -not $rootProcessIds.ContainsKey([int]$_.ProcessId)
    })

    foreach ($process in $orderedProcesses) {
        $processId = [int]$process.ProcessId
        if (-not (& $ProcessExists $processId)) {
            continue
        }

        & $WriteLog "Stopping project runtime process tree PID $processId."
        $result = & $StopProcessTree $processId
        foreach ($line in @($result.Output)) {
            & $WriteLog $line.ToString()
        }
        if ($result.ExitCode -ne 0 -and (& $ProcessExists $processId)) {
            throw "Failed to stop project runtime PID $processId."
        }
    }
}

function Restore-RuntimeAfterFailure {
    param(
        [Parameter(Mandatory = $true)][string]$RuntimeTaskName,
        [Parameter(Mandatory = $true)][scriptblock]$GetTaskState,
        [Parameter(Mandatory = $true)][scriptblock]$StartTask,
        [Parameter(Mandatory = $true)][scriptblock]$TestHealth,
        [scriptblock]$WriteLog = {
            param([string]$Message)
            Write-Host $Message
        }
    )

    $state = & $GetTaskState
    if ($state -ne "Running") {
        & $WriteLog "Recovering scheduled task '$RuntimeTaskName' after deployment failure."
        & $StartTask
    }
    if (-not (& $TestHealth)) {
        throw "Scheduled task '$RuntimeTaskName' recovery did not become healthy."
    }
    & $WriteLog "Scheduled task '$RuntimeTaskName' recovered after deployment failure."
}
