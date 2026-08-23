#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$RepositoryPath = (Join-Path $PSScriptRoot ".."),
    [string]$ServiceName = "HomeSecondBrain",
    [string]$HealthUrl = "http://127.0.0.1:8080/health",
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

        $null = Get-Service -Name $ServiceName -ErrorAction Stop
        $null = Invoke-Git -Arguments @("merge", "--ff-only", "origin/master")
        $localSha = Invoke-Git -Arguments @("rev-parse", "HEAD")
    }

    if ($deployedSha -eq $localSha) {
        Write-DeployLog "No new commit to deploy ($localSha)."
        return
    }

    $service = Get-Service -Name $ServiceName -ErrorAction Stop

    Push-Location -LiteralPath $repository
    try {
        $null = Invoke-LoggedCommand -FilePath $gradle -Arguments @("--no-daemon", "test")
    } finally {
        Pop-Location
    }

    $service.Refresh()
    if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Write-DeployLog "Starting Windows service '$ServiceName'."
        Start-Service -Name $ServiceName
    } else {
        Write-DeployLog "Restarting Windows service '$ServiceName'."
        Restart-Service -Name $ServiceName -Force
    }

    if (-not (Test-Health)) {
        throw "Service '$ServiceName' did not become healthy within $HealthTimeoutSeconds seconds."
    }

    $temporaryShaPath = "$deployedShaPath.tmp"
    Set-Content -LiteralPath $temporaryShaPath -Value $localSha -Encoding ASCII
    Move-Item -LiteralPath $temporaryShaPath -Destination $deployedShaPath -Force
    Write-DeployLog "Deployment completed successfully: $localSha"
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
