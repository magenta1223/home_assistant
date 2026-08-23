#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$RepositoryPath = (Join-Path $PSScriptRoot ".."),
    [string]$RuntimeTaskName = "HomeSecondBrain",
    [string]$HealthUrl = "http://127.0.0.1:8080/health",
    [int[]]$RuntimePorts = @(8080, 6333, 11435),
    [ValidateRange(10, 600)][int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repository = (Resolve-Path -LiteralPath $RepositoryPath).Path
$deployDirectory = Join-Path $repository "runtime\deploy"
$logPath = Join-Path $deployDirectory "deploy.log"
$lockPath = Join-Path $deployDirectory "deploy.lock"
$deployedShaPath = Join-Path $deployDirectory "deployed-sha.txt"
$gradle = Join-Path $repository "gradlew.bat"
$lockHandle = $null
$exitCode = 0

New-Item -ItemType Directory -Path $deployDirectory -Force | Out-Null

function Write-DeployLog {
    param([Parameter(Mandatory = $true)][string]$Message)

    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message"
    Write-Host $line
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    Write-DeployLog "Running: $FilePath $($Arguments -join ' ')"
    $output = & $FilePath @Arguments 2>&1
    $commandExitCode = $LASTEXITCODE
    foreach ($line in $output) {
        Write-DeployLog $line.ToString()
    }
    if ($commandExitCode -ne 0) {
        throw "Command failed with exit code ${commandExitCode}: $FilePath $($Arguments -join ' ')"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    return Invoke-LoggedCommand -FilePath "git" -Arguments (@("-C", $repository) + $Arguments)
}

function Test-Health {
    $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
    do {
        try {
            $response = Invoke-RestMethod -Method Get -Uri $HealthUrl -TimeoutSec 5
            if ($response.status -eq "ok") {
                return $true
            }
        } catch {
            Write-DeployLog "Health check pending: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)

    return $false
}

function Get-RuntimeTask {
    return Get-ScheduledTask -TaskName $RuntimeTaskName -TaskPath "\" -ErrorAction Stop
}

function Stop-RuntimeTask {
    $task = Get-RuntimeTask
    if ($task.State -eq "Running") {
        Write-DeployLog "Stopping scheduled task '$RuntimeTaskName'."
        Stop-ScheduledTask -TaskName $RuntimeTaskName -TaskPath "\"

        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 500
            $task = Get-RuntimeTask
        } while ($task.State -eq "Running" -and [DateTime]::UtcNow -lt $deadline)

        if ($task.State -eq "Running") {
            throw "Scheduled task '$RuntimeTaskName' did not stop within 30 seconds."
        }
    }

    Start-Sleep -Seconds 2
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $RuntimePorts })
    $processIds = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $runtimeProcesses = @()

    foreach ($processId in $processIds) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction Stop
        $evidence = "$($process.ExecutablePath) $($process.CommandLine)"
        if ($evidence.IndexOf($repository, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Refusing to stop PID $processId because it is not owned by repository '$repository'."
        }
        $runtimeProcesses += $process
    }

    foreach ($process in $runtimeProcesses) {
        if ($null -eq (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue)) {
            continue
        }
        Write-DeployLog "Stopping project runtime process tree PID $($process.ProcessId)."
        $null = Invoke-LoggedCommand -FilePath "taskkill.exe" -Arguments @(
            "/PID", $process.ProcessId.ToString(), "/T", "/F"
        )
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $RuntimePorts })
        if ($listeners.Count -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($listeners.Count -ne 0) {
        throw "Project runtime ports remained open after shutdown: $($listeners.LocalPort -join ',')."
    }
}

function Start-RuntimeTask {
    Write-DeployLog "Starting scheduled task '$RuntimeTaskName'."
    Start-ScheduledTask -TaskName $RuntimeTaskName -TaskPath "\"
}

try {
    try {
        $lockHandle = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    } catch [System.IO.IOException] {
        Write-Host "Another master deployment is already running."
        return
    }

    Write-DeployLog "Checking origin/master for deployment."

    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        throw "Gradle wrapper was not found: $gradle"
    }

    $repositoryTopLevel = Invoke-Git -Arguments @("rev-parse", "--show-toplevel")
    if ([System.IO.Path]::GetFullPath($repositoryTopLevel) -ne [System.IO.Path]::GetFullPath($repository)) {
        throw "RepositoryPath must point to the Git worktree root: $repository"
    }

    $branch = Invoke-Git -Arguments @("branch", "--show-current")
    if ($branch -ne "master") {
        throw "Automatic deployment requires the master branch; current branch is '$branch'."
    }

    $worktreeStatus = Invoke-Git -Arguments @("status", "--porcelain", "--untracked-files=normal")
    if (-not [string]::IsNullOrWhiteSpace($worktreeStatus)) {
        throw "The worktree is not clean. Commit or remove local changes before automatic deployment."
    }

    $null = Invoke-Git -Arguments @("fetch", "--prune", "origin", "master")
    $localSha = Invoke-Git -Arguments @("rev-parse", "HEAD")
    $remoteSha = Invoke-Git -Arguments @("rev-parse", "origin/master")
    $deployedSha = if (Test-Path -LiteralPath $deployedShaPath) {
        (Get-Content -LiteralPath $deployedShaPath -Raw).Trim()
    } else {
        ""
    }

    if ($localSha -ne $remoteSha) {
        & git -C $repository merge-base --is-ancestor $localSha $remoteSha
        if ($LASTEXITCODE -ne 0) {
            throw "Local master is not a strict fast-forward of origin/master. Resolve it manually."
        }

        $null = Get-RuntimeTask
        $null = Invoke-Git -Arguments @("merge", "--ff-only", "origin/master")
        $localSha = Invoke-Git -Arguments @("rev-parse", "HEAD")
    }

    $null = Get-RuntimeTask
    $requiresDeployment = $deployedSha -ne $localSha
    if ($requiresDeployment) {
        Push-Location -LiteralPath $repository
        try {
            $null = Invoke-LoggedCommand -FilePath $gradle -Arguments @("--no-daemon", "test")
        } finally {
            Pop-Location
        }
    } else {
        Write-DeployLog "No new commit to deploy ($localSha); performing the scheduled daily restart."
    }

    Stop-RuntimeTask

    if ($requiresDeployment) {
        try {
            Push-Location -LiteralPath $repository
            try {
                $null = Invoke-LoggedCommand -FilePath $gradle -Arguments @("--no-daemon", ":app:installDist")
            } finally {
                Pop-Location
            }
        } catch {
            Start-RuntimeTask
            throw
        }
    }

    Start-RuntimeTask

    if (-not (Test-Health)) {
        throw "Scheduled task '$RuntimeTaskName' did not become healthy within $HealthTimeoutSeconds seconds."
    }

    if ($requiresDeployment) {
        $temporaryShaPath = "$deployedShaPath.tmp"
        Set-Content -LiteralPath $temporaryShaPath -Value $localSha -Encoding ASCII
        Move-Item -LiteralPath $temporaryShaPath -Destination $deployedShaPath -Force
        Write-DeployLog "Deployment completed successfully: $localSha"
    } else {
        Write-DeployLog "Scheduled daily restart completed successfully: $localSha"
    }
} catch {
    $exitCode = 1
    Write-DeployLog "Deployment failed: $($_.Exception.Message)"
    Write-Error $_
} finally {
    if ($null -ne $lockHandle) {
        $lockHandle.Dispose()
    }
}

exit $exitCode
