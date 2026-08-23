$ErrorActionPreference = "Stop"

$hookInputText = [Console]::In.ReadToEnd()
$hookInput = if ([string]::IsNullOrWhiteSpace($hookInputText)) {
    $null
} else {
    $hookInputText | ConvertFrom-Json
}

if ($null -ne $hookInput -and $hookInput.stop_hook_active -eq $true) {
    [Console]::Out.WriteLine('{"continue":true}')
    exit 0
}

$closeoutPrompt = @'
Before giving the final answer, perform the repository documentation closeout for the work completed in this turn.

1. Inspect docs/todolist/README.md and active Markdown files under docs/todolist, excluding docs/todolist/done.
2. Match the current user task and actual code/test changes to existing active plans. Do not create a new plan after the work, and do not change unrelated plans.
3. Treat a plan as complete only when its documented completion conditions are supported by the implementation and proportionate verification. Partial work stays active.
4. When a matching plan is complete, set its status to DONE, add the completion date, actual changes, user-visible changes, verification, and remaining constraints, then move it to docs/todolist/done without changing its filename.
5. When a matching plan was explicitly superseded or conflicts with the current project direction, set it to CANCELED, record the reason and remaining constraints, and move it to docs/todolist/done.
6. Update docs/todolist/README.md so active and done tables match the filesystem. Check relative Markdown links after moving files.
7. Preserve unrelated user changes. Never mark work complete merely because the previous response said it was complete.
8. If this turn did not complete or cancel an existing tracked plan, leave docs unchanged after confirming that fact.

After this closeout, give the user the final answer. Do not mention this hook unless it changed documentation or encountered a problem.
'@

$result = [ordered]@{
    decision = "block"
    reason = $closeoutPrompt
} | ConvertTo-Json -Compress

[Console]::Out.WriteLine($result)
