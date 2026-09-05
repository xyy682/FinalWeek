param(
    [int]$Iterations = 5,
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MailpitUrl = 'http://localhost:8025',
    [int]$TaskTimeoutSeconds = 300
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
    $code = $null
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
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Task $TaskId timed out"
}

$runId=Get-Date -Format 'yyyyMMdd-HHmmss'
$rawDirectory=Join-Path $PSScriptRoot '..\raw'
New-Item -ItemType Directory -Force -Path $rawDirectory | Out-Null
$rows=[System.Collections.Generic.List[object]]::new()

for($iteration=1;$iteration -le $Iterations;$iteration++) {
    $email="outline-repro-$runId-$iteration@example.com"
    $auth=New-TestSession $email
    $course=Invoke-Json -Uri "$BaseUrl/api/v1/courses" -Method Post -Body @{name="Outline Repro $runId $iteration"} -Headers $auth.Headers -Session $auth.Session
    $text="Outline acceptance reproduction $runId iteration $iteration. Database transactions provide atomicity, consistency, isolation and durability. A B+ tree stores sorted keys in balanced pages."
    $bytes=[Text.Encoding]::UTF8.GetBytes($text)
    $sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    $upload=Invoke-Json -Uri "$BaseUrl/api/v1/courses/$($course.id)/uploads/init" -Method Post -Body @{
        filename="outline-repro-$iteration.txt";fileSize=$bytes.Length;sha256=$sha;materialType='NOTES';focusNotes='outline 500 reproduction'
    } -Headers $auth.Headers -Session $auth.Session
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($upload.uploadId)/chunks/0" -Method Put -Body $bytes -ContentType 'application/octet-stream' -Headers $auth.Headers -WebSession $auth.Session | Out-Null
    $complete=Invoke-WebRequest -Uri "$BaseUrl/api/v1/uploads/$($upload.uploadId)/complete" -Method Post -Headers $auth.Headers -WebSession $auth.Session
    $parseTaskId=($complete.Content | ConvertFrom-Json).taskId
    $parseTask=Wait-Task $parseTaskId $auth.Headers $auth.Session
    if($parseTask.status -ne 'SUCCEEDED'){throw "Parse failed for iteration $iteration"}

    $requestId="outline-repro-$runId-$iteration"
    $headers=$auth.Headers + @{'X-Request-Id'=$requestId}
    $stopwatch=[Diagnostics.Stopwatch]::StartNew()
    try {
        $accepted=Invoke-WebRequest -Uri "$BaseUrl/api/v1/courses/$($course.id)/knowledge-versions" -Method Post -Body '{"ignoreFailedMaterials":false}' -ContentType 'application/json' -Headers $headers -WebSession $auth.Session
        $status=[int]$accepted.StatusCode
        $response=$accepted.Content | ConvertFrom-Json
        $taskId=$response.task.id
        $errorCode=$null
    } catch {
        $status=[int]$_.Exception.Response.StatusCode
        $taskId=$null
        $errorCode=$_.ErrorDetails.Message
    } finally { $stopwatch.Stop() }
    $knowledge=$null
    $deadline=(Get-Date).AddSeconds($TaskTimeoutSeconds)
    do {
        $knowledge=Invoke-Json -Uri "$BaseUrl/api/v1/courses/$($course.id)/knowledge-version" -Headers $auth.Headers -Session $auth.Session
        if($knowledge.current -or $knowledge.recentFailedOutlineTask){break}
        Start-Sleep -Milliseconds 500
    } while((Get-Date)-lt $deadline)
    $rows.Add([pscustomobject]@{
        iteration=$iteration;email=$email;course_id=$course.id;http_status=$status;elapsed_ms=[math]::Round($stopwatch.Elapsed.TotalMilliseconds,3)
        request_id=$requestId;response_task_id=$taskId;current_version_status=$knowledge.current.status
        failed_task_status=$knowledge.recentFailedOutlineTask.status;error=$errorCode
    })
}

$csv=Join-Path $rawDirectory "outline-acceptance-repro-$runId.csv"
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding utf8
$summary=[ordered]@{
    run_id=$runId;iterations=$Iterations;accepted_202=@($rows|Where-Object http_status -eq 202).Count
    http_500=@($rows|Where-Object http_status -eq 500).Count;rows=$rows;raw_csv=$csv
}
$json=Join-Path $rawDirectory "outline-acceptance-repro-$runId.json"
$summary|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $json -Encoding utf8
$summary|ConvertTo-Json -Depth 8
