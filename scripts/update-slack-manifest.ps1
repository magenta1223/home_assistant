param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\slack-app-manifest.json")
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
$headers = @{ Authorization = "Bearer $configToken" }

function Invoke-SlackManifestApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][hashtable]$Payload
    )

    $body = $Payload | ConvertTo-Json -Compress -Depth 20
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "https://slack.com/api/$Method" `
        -Headers $headers `
        -ContentType "application/json; charset=utf-8" `
        -Body $body
    if (-not $response.ok) {
        $details = if ($response.errors) { $response.errors | ConvertTo-Json -Compress -Depth 20 } else { $response.error }
        throw "$Method failed: $details"
    }
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
