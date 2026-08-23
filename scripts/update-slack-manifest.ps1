param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\slack-app-manifest.json"),
    [ValidateRange(5, 300)][int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

$configToken = $env:SLACK_CONFIG_TOKEN
$appId = $env:SLACK_APP_ID
if ([string]::IsNullOrWhiteSpace($configToken)) {
    throw "SLACK_CONFIG_TOKEN is required"
}
if ([string]::IsNullOrWhiteSpace($appId)) {
    throw "SLACK_APP_ID is required"
}

$resolvedManifestPath = (Resolve-Path -LiteralPath $ManifestPath).Path
$manifest = Get-Content -LiteralPath $resolvedManifestPath -Raw -Encoding UTF8
$null = $manifest | ConvertFrom-Json
$curl = (Get-Command curl.exe -ErrorAction Stop).Source
Add-Type -AssemblyName System.Web.Extensions
$jsonSerializer = [System.Web.Script.Serialization.JavaScriptSerializer]::new()
$jsonSerializer.MaxJsonLength = 10MB

function Invoke-SlackManifestApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Payload
    )

    Write-Host "Calling Slack API $Method."
    $serializablePayload = [System.Collections.Generic.Dictionary[string,object]]::new()
    foreach ($entry in $Payload.GetEnumerator()) {
        $serializablePayload.Add([string]$entry.Key, [string]$entry.Value)
    }
    $body = $jsonSerializer.Serialize($serializablePayload)
    $bodyPath = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText(
            $bodyPath,
            $body,
            [System.Text.UTF8Encoding]::new($false)
        )
        $responseLines = & $curl `
            --max-time $TimeoutSeconds `
            --silent `
            --show-error `
            --request POST `
            --header "Authorization: Bearer $configToken" `
            --header "Content-Type: application/json; charset=utf-8" `
            --data-binary "@$bodyPath" `
            "https://slack.com/api/$Method"
        if ($LASTEXITCODE -ne 0) {
            throw "$Method request failed with curl exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item -LiteralPath $bodyPath -Force -ErrorAction SilentlyContinue
    }

    $responseBody = $responseLines -join [Environment]::NewLine
    $response = $responseBody | ConvertFrom-Json
    if (-not $response.ok) {
        $details = if ($response.errors) { $response.errors | ConvertTo-Json -Compress -Depth 20 } else { $response.error }
        throw "$Method failed: $details"
    }
    Write-Host "Slack API $Method succeeded."
    return $response
}

$null = Invoke-SlackManifestApi -Method "apps.manifest.validate" -Payload @{ manifest = $manifest }
$result = Invoke-SlackManifestApi -Method "apps.manifest.update" -Payload @{
    app_id = $appId
    manifest = $manifest
}

Write-Host "Slack app manifest updated for $($result.app_id)."
if ($result.permissions_updated) {
    Write-Warning "Slack permissions changed. Reinstall the app in the workspace before using the new scopes."
}
