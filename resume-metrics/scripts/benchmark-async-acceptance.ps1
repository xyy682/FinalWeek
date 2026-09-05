param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MailpitUrl = 'http://localhost:8025',
    [int]$Warmup = 10,
    [int]$Iterations = 100,
    [int]$TaskTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-JsonRequest {
    param(
        [string]$Uri,
        [string]$Method = 'Get',
        [object]$Body,
        [hashtable]$Headers = @{},
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )
    $parameters = @{
        Uri = $Uri
        Method = $Method
        Headers = $Headers
        WebSession = $Session
        ContentType = 'application/json'
    }
    if ($null -ne $Body) { $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    Invoke-RestMethod @parameters
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $rank = [math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    return [math]::Round($sorted[[math]::Max(0, $rank)], 3)
}

function Get-Summary {
    param([object[]]$Rows)
    $values = [double[]]@($Rows | ForEach-Object { $_.elapsed_ms })
    [ordered]@{
        count = $values.Count
        success_202 = @($Rows | Where-Object status -eq 202).Count
        average_ms = [math]::Round(($values | Measure-Object -Average).Average, 3)
        p50_ms = Get-Percentile $values 50
        p90_ms = Get-Percentile $values 90
        p95_ms = Get-Percentile $values 95
        p99_ms = Get-Percentile $values 99
        max_ms = [math]::Round(($values | Measure-Object -Maximum).Maximum, 3)
    }
}

function Wait-Task {
    param([string]$TaskId, [hashtable]$Headers, $Session)
    $deadline = (Get-Date).AddSeconds($TaskTimeoutSeconds)
    do {
        $task = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/tasks/$TaskId" -Headers $Headers -Session $Session
        if ($task.status -in @('SUCCEEDED', 'FAILED', 'CANCELLED', 'PUBLISH_FAILED')) { return $task }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Task $TaskId did not reach a terminal state within $TaskTimeoutSeconds seconds"
}

function Measure-ReplayEndpoint {
    param([string]$Name, [scriptblock]$Request)
    $rows = [System.Collections.Generic.List[object]]::new()
    for ($i = 1; $i -le ($Warmup + $Iterations); $i++) {
        $phase = if ($i -le $Warmup) { 'warmup' } else { 'measurement' }
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = & $Request
            $status = 202
            $taskId = if ($response.taskId) { $response.taskId } elseif ($response.task.id) { $response.task.id } else { $null }
            $replay = if ($null -ne $response.idempotentReplay) { $response.idempotentReplay } else { $null }
            $errorCode = $null
        } catch {
            $status = [int]$_.Exception.Response.StatusCode
            $taskId = $null
            $replay = $null
            $errorCode = $_.ErrorDetails.Message
        } finally { $sw.Stop() }
        $rows.Add([pscustomobject]@{
            endpoint = $Name; phase = $phase; iteration = $i; elapsed_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 3)
            status = $status; task_id = $taskId; idempotent_replay = $replay; error = $errorCode
        })
    }
    return $rows
}

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$rawDirectory = Join-Path $PSScriptRoot '..\raw'
New-Item -ItemType Directory -Force -Path $rawDirectory | Out-Null
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/csrf" -Session $session
$headers = @{ 'X-CSRF-TOKEN' = $csrf.token }
$email = "finalweek-metrics-$runId@example.com"
Invoke-JsonRequest -Uri "$BaseUrl/api/v1/auth/code" -Method Post -Body @{ email = $email } -Headers $headers -Session $session | Out-Null

$code = $null
$deadline = (Get-Date).AddSeconds(20)
do {
    $messages = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/messages"
    $match = $messages.messages | Where-Object { @($_.To | ForEach-Object { $_.Address }) -contains $email } | Select-Object -First 1
    if ($match) {
        $mail = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/message/$($match.ID)"
        if ($mail.Text -match '(?<!\d)(\d{6})(?!\d)') { $code = $Matches[1] }
    }
    if (-not $code) { Start-Sleep -Milliseconds 250 }
} while (-not $code -and (Get-Date) -lt $deadline)
if (-not $code) { throw "Could not obtain the verification code for $email from Mailpit" }

Invoke-JsonRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body @{ email = $email; code = $code } -Headers $headers -Session $session | Out-Null
$csrf = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/csrf" -Session $session
$headers = @{ 'X-CSRF-TOKEN' = $csrf.token }
$course = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/courses" -Method Post -Body @{ name = "Metrics $runId" } -Headers $headers -Session $session

$fixtureText = @'
FinalWeek metrics fixture. Distributed task processing uses RabbitMQ publisher confirms and manual acknowledgements.
The parsing pipeline records checkpoints after content extraction, semantic chunking, and embedding completion.
Hybrid retrieval combines Qdrant vector similarity with Lucene BM25 using reciprocal rank fusion.
Idempotent writes use deterministic business keys, compare-and-set task transitions, deterministic vector point IDs, and index updates.
'@
$fixtureBytes = [Text.Encoding]::UTF8.GetBytes($fixtureText)
$fixtureSha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($fixtureBytes)).ToLowerInvariant()
$upload = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/uploads/init" -Method Post -Body @{
    filename = 'metrics-fixture.txt'; fileSize = $fixtureBytes.Length; sha256 = $fixtureSha; materialType = 'NOTES'; focusNotes = 'resume metrics isolation fixture'
} -Headers $headers -Session $session
Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($upload.uploadId)/chunks/0" -Method Put -Body $fixtureBytes -ContentType 'application/octet-stream' -Headers $headers -WebSession $session | Out-Null

$coldRows = [System.Collections.Generic.List[object]]::new()
$sw = [Diagnostics.Stopwatch]::StartNew()
$complete = Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($upload.uploadId)/complete" -Method Post -Headers $headers -WebSession $session
$sw.Stop()
$completeBody = $complete.Content | ConvertFrom-Json
$coldRows.Add([pscustomobject]@{ endpoint = 'material_parse_acceptance'; elapsed_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 3); status = [int]$complete.StatusCode; task_id = $completeBody.taskId })
$parseTask = Wait-Task -TaskId $completeBody.taskId -Headers $headers -Session $session
if ($parseTask.status -ne 'SUCCEEDED') { throw "Fixture parse failed: $($parseTask | ConvertTo-Json -Compress)" }

$sw.Restart()
try {
    $outlineAccepted = Invoke-WebRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/knowledge-versions" -Method Post -Body '{"ignoreFailedMaterials":false}' -ContentType 'application/json' -Headers $headers -WebSession $session
    $outlineStatusCode = [int]$outlineAccepted.StatusCode
    $outlineBody = $outlineAccepted.Content | ConvertFrom-Json
    $outlineTaskId = $outlineBody.task.id
} catch {
    $outlineStatusCode = [int]$_.Exception.Response.StatusCode
    $outlineTaskId = $null
} finally { $sw.Stop() }
$coldRows.Add([pscustomobject]@{ endpoint = 'outline_acceptance'; elapsed_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 3); status = $outlineStatusCode; task_id = $outlineTaskId })
if ($outlineTaskId) {
    $outlineTask = Wait-Task -TaskId $outlineTaskId -Headers $headers -Session $session
    if ($outlineTask.status -ne 'SUCCEEDED') { throw "Fixture outline failed: $($outlineTask | ConvertTo-Json -Compress)" }
} else {
    $outlineDeadline = (Get-Date).AddSeconds($TaskTimeoutSeconds)
    do {
        $knowledgeStatus = Invoke-JsonRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/knowledge-version" -Headers $headers -Session $session
        if ($knowledgeStatus.current) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $outlineDeadline)
    if (-not $knowledgeStatus.current) { throw 'Outline task did not publish a current knowledge version after the HTTP error' }
}

$planKey = "metrics-plan-$runId"
$planBody = @{ examDate = (Get-Date).Date.AddDays(30).ToString('yyyy-MM-dd'); dailyMinutes = 60; masteryLevel = 'MEDIUM'; targetScore = 85 }
$sw.Restart()
$planAccepted = Invoke-WebRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/plan/generate" -Method Post -Body ($planBody | ConvertTo-Json -Compress) -ContentType 'application/json' -Headers ($headers + @{ 'Idempotency-Key' = $planKey }) -WebSession $session
$sw.Stop()
$planColdBody = $planAccepted.Content | ConvertFrom-Json
$coldRows.Add([pscustomobject]@{ endpoint = 'plan_acceptance'; elapsed_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 3); status = [int]$planAccepted.StatusCode; task_id = $planColdBody.taskId })

$mockKey = "metrics-mock-$runId"
$mockBody = @{
    displayName = 'Metrics Replay Exam'; scope = 'WHOLE_COURSE'; outlineNodeIds = @()
    questionCounts = @{ TRUE_FALSE = 1 }; scoreMode = 'AUTO'; scorePerQuestion = @{}
    totalScore = $null; durationMinutes = 30; allowGeneralKnowledge = $false; instructions = 'Use only the supplied fixture.'
}
$sw.Restart()
$mockAccepted = Invoke-WebRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/mock-exams" -Method Post -Body ($mockBody | ConvertTo-Json -Depth 10 -Compress) -ContentType 'application/json' -Headers ($headers + @{ 'Idempotency-Key' = $mockKey }) -WebSession $session
$sw.Stop()
$mockColdBody = $mockAccepted.Content | ConvertFrom-Json
$coldRows.Add([pscustomobject]@{ endpoint = 'mock_exam_acceptance'; elapsed_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 3); status = [int]$mockAccepted.StatusCode; task_id = $mockColdBody.taskId })

$materialRows = Measure-ReplayEndpoint -Name 'material_parse_replay' -Request {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/uploads/$($upload.uploadId)/complete" -Method Post -Headers $headers -WebSession $session
}
$planRows = Measure-ReplayEndpoint -Name 'plan_replay' -Request {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/courses/$($course.id)/plan/generate" -Method Post -Body ($planBody | ConvertTo-Json -Compress) -ContentType 'application/json' -Headers ($headers + @{ 'Idempotency-Key' = $planKey }) -WebSession $session
}
$mockRows = Measure-ReplayEndpoint -Name 'mock_exam_replay' -Request {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/courses/$($course.id)/mock-exams" -Method Post -Body ($mockBody | ConvertTo-Json -Depth 10 -Compress) -ContentType 'application/json' -Headers ($headers + @{ 'Idempotency-Key' = $mockKey }) -WebSession $session
}

$allRows = @($materialRows) + @($planRows) + @($mockRows)
$csvPath = Join-Path $rawDirectory "async-acceptance-$runId.csv"
$allRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
$coldPath = Join-Path $rawDirectory "async-acceptance-cold-$runId.csv"
$coldRows | Export-Csv -LiteralPath $coldPath -NoTypeInformation -Encoding utf8
$result = [ordered]@{
    run_id = $runId
    test_account = $email
    course_id = $course.id
    methodology = 'Sequential loopback HTTP; cold creation is one sample per endpoint; 10 warmups + 100 measurements use the same idempotency key/upload and therefore measure replay acceptance only.'
    cold_creation_samples = $coldRows
    replay_summaries = [ordered]@{
        material_parse = Get-Summary @($materialRows | Where-Object phase -eq 'measurement')
        plan = Get-Summary @($planRows | Where-Object phase -eq 'measurement')
        mock_exam = Get-Summary @($mockRows | Where-Object phase -eq 'measurement')
    }
    raw_csv = $csvPath
    cold_csv = $coldPath
}
$jsonPath = Join-Path $rawDirectory "async-acceptance-$runId.json"
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding utf8
$result | ConvertTo-Json -Depth 12
