param(
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '..\source-materials'),
    [string[]]$FileNames = @(),
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MailpitUrl = 'http://localhost:8025',
    [int]$TaskTimeoutSeconds = 1800
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-Json {
    param([string]$Uri, [string]$Method = 'Get', [object]$Body, [hashtable]$Headers = @{}, $Session)
    $parameters = @{ Uri=$Uri; Method=$Method; Headers=$Headers; WebSession=$Session; ContentType='application/json' }
    if ($null -ne $Body) { $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress }
    Invoke-RestMethod @parameters
}

function New-TestSession([string]$Email) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Invoke-Json -Uri "$BaseUrl/api/v1/csrf" -Session $session
    $headers = @{ 'X-CSRF-TOKEN'=$csrf.token }
    Invoke-Json -Uri "$BaseUrl/api/v1/auth/code" -Method Post -Body @{email=$Email} -Headers $headers -Session $session | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    do {
        $messages = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/messages"
        $match = $messages.messages | Where-Object { @($_.To | ForEach-Object {$_.Address}) -contains $Email } | Select-Object -First 1
        if ($match) {
            $mail = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/message/$($match.ID)"
            if ($mail.Text -match '(?<!\d)(\d{6})(?!\d)') { $code=$Matches[1] }
        }
        if (-not $code) { Start-Sleep -Milliseconds 250 }
    } while (-not $code -and (Get-Date) -lt $deadline)
    if (-not $code) { throw "Mail code not found for $Email" }
    Invoke-Json -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body @{email=$Email;code=$code} -Headers $headers -Session $session | Out-Null
    $csrf = Invoke-Json -Uri "$BaseUrl/api/v1/csrf" -Session $session
    [pscustomobject]@{Session=$session;Headers=@{'X-CSRF-TOKEN'=$csrf.token}}
}

function Wait-Task([string]$TaskId, [hashtable]$Headers, $Session) {
    $deadline = (Get-Date).AddSeconds($TaskTimeoutSeconds)
    do {
        $task = Invoke-Json -Uri "$BaseUrl/api/v1/tasks/$TaskId" -Headers $Headers -Session $Session
        if ($task.status -in @('SUCCEEDED','FAILED','CANCELLED','PUBLISH_FAILED')) { return $task }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Task $TaskId timed out"
}

function Write-State([string]$Path, [object]$State) {
    $State | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding utf8
}

$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
if ($FileNames.Count -eq 0) {
    $files = Get-ChildItem -LiteralPath $source -File | Where-Object Extension -in @('.pptx','.pdf') | Sort-Object Name
} else {
    $files = $FileNames | ForEach-Object { Get-Item -LiteralPath (Join-Path $source $_) }
}
if (-not $files) { throw 'No source files selected' }

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$email = "database-corpus-$runId@example.com"
$auth = New-TestSession $email
$course = Invoke-Json -Uri "$BaseUrl/api/v1/courses" -Method Post -Body @{name="数据库检索评测 $runId"} -Headers $auth.Headers -Session $auth.Session
$rawDirectory = Join-Path $PSScriptRoot '..\raw'
New-Item -ItemType Directory -Force -Path $rawDirectory | Out-Null
$statePath = Join-Path $rawDirectory "course-corpus-ingest-$runId.json"
$rows = [System.Collections.Generic.List[object]]::new()
$state = [ordered]@{run_id=$runId;email=$email;course_id=$course.id;source_directory=$source;selected_count=$files.Count;rows=$rows}
Write-State $statePath $state

foreach ($file in $files) {
    Write-Host "Uploading $($file.Name) ($($file.Length) bytes)"
    $sha = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $init = Invoke-Json -Uri "$BaseUrl/api/v1/courses/$($course.id)/uploads/init" -Method Post -Body @{
        filename=$file.Name;fileSize=$file.Length;sha256=$sha;materialType='COURSEWARE';focusNotes='数据库课程 RAG 量化评测语料'
    } -Headers $auth.Headers -Session $auth.Session
    $stream = [IO.File]::OpenRead($file.FullName)
    try {
        for ($index=0; $index -lt $init.totalChunks; $index++) {
            $remaining = $stream.Length - $stream.Position
            $length = [int][Math]::Min([long]$init.chunkSize, $remaining)
            $buffer = [byte[]]::new($length)
            $read = $stream.Read($buffer, 0, $length)
            if ($read -ne $length) { throw "Short read for $($file.Name) chunk $index" }
            Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($init.uploadId)/chunks/$index" -Method Put -Body $buffer `
                -ContentType 'application/octet-stream' -Headers $auth.Headers -WebSession $auth.Session | Out-Null
        }
    } finally { $stream.Dispose() }
    $accepted = Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($init.uploadId)/complete" -Method Post `
        -Headers $auth.Headers -WebSession $auth.Session
    $response = $accepted.Content | ConvertFrom-Json
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $task = Wait-Task $response.taskId $auth.Headers $auth.Session
    $stopwatch.Stop()
    $material = Invoke-Json -Uri "$BaseUrl/api/v1/materials/$($response.material.id)" -Headers $auth.Headers -Session $auth.Session
    $rows.Add([pscustomobject]@{filename=$file.Name;size_bytes=$file.Length;material_id=$material.id;task_id=$task.id;
            task_status=$task.status;parse_seconds=[Math]::Round($stopwatch.Elapsed.TotalSeconds,3);
            material_status=$material.status;duration_ms=$material.durationMs;parse_warning=$material.parseWarning})
    Write-State $statePath $state
    Write-Host "Completed $($file.Name): $($task.status) in $([Math]::Round($stopwatch.Elapsed.TotalSeconds,1))s"
    if ($task.status -ne 'SUCCEEDED') { throw "Parse failed for $($file.Name): $($task.errorCode) $($task.errorMessage)" }
}

$state.completed_at = (Get-Date).ToString('o')
Write-State $statePath $state
$state | ConvertTo-Json -Depth 10
