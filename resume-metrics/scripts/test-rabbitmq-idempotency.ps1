param([int[]]$DuplicateLoads = @(1, 5, 10, 20), [int]$MaterialCount = 5)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$rawDirectory = Join-Path $PSScriptRoot '..\raw'
New-Item -ItemType Directory -Force -Path $rawDirectory | Out-Null

function Invoke-MySql([string]$Sql) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    docker exec -e METRICS_SQL_BASE64=$encoded finalweek-mysql-1 sh -c 'echo "$METRICS_SQL_BASE64" | base64 -d | mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -N' 2>$null
}

function Get-QdrantMaterialCount([string]$MaterialId) {
    $body = @{ exact = $true; filter = @{ must = @(@{ key = 'materialId'; match = @{ value = $MaterialId } }) } } | ConvertTo-Json -Depth 8 -Compress
    (Invoke-RestMethod -Uri 'http://localhost:6333/collections/finalweek_segments/points/count' -Method Post -ContentType 'application/json' -Body $body).result.count
}

function Get-LuceneHash([string]$CourseId) {
    $value = docker exec finalweek-backend-1 sh -c "find '/data/lucene/$CourseId' -type f ! -name write.lock -exec sha256sum '{}' ';' 2>/dev/null | sort | sha256sum | cut -d' ' -f1"
    return ($value | Select-Object -First 1).Trim()
}

function Get-DlqCount {
    $line = docker exec finalweek-rabbitmq-1 rabbitmqctl list_queues name messages 2>$null | Where-Object { $_ -match '^finalweek\.parse\.dlq\s+' }
    if (-not $line) { throw 'Could not read finalweek.parse.dlq count' }
    return [int](($line -split '\s+')[1])
}

$candidateSql = @"
SELECT
 LOWER(CONCAT(SUBSTR(HEX(bt.id),1,8),'-',SUBSTR(HEX(bt.id),9,4),'-',SUBSTR(HEX(bt.id),13,4),'-',SUBSTR(HEX(bt.id),17,4),'-',SUBSTR(HEX(bt.id),21))),
 LOWER(CONCAT(SUBSTR(HEX(bt.material_id),1,8),'-',SUBSTR(HEX(bt.material_id),9,4),'-',SUBSTR(HEX(bt.material_id),13,4),'-',SUBSTR(HEX(bt.material_id),17,4),'-',SUBSTR(HEX(bt.material_id),21))),
 LOWER(CONCAT(SUBSTR(HEX(bt.course_id),1,8),'-',SUBSTR(HEX(bt.course_id),9,4),'-',SUBSTR(HEX(bt.course_id),13,4),'-',SUBSTR(HEX(bt.course_id),17,4),'-',SUBSTR(HEX(bt.course_id),21))),
 bt.execution_round, COUNT(cs.id)
FROM background_task bt
JOIN material m ON m.id=bt.material_id
LEFT JOIN course_segment cs ON cs.material_id=m.id
WHERE bt.task_type='PARSE_MATERIAL' AND bt.status='SUCCEEDED' AND m.deleted=false
GROUP BY bt.id,bt.material_id,bt.course_id,bt.execution_round
HAVING COUNT(cs.id)>0
ORDER BY bt.updated_at DESC LIMIT $MaterialCount;
"@
$candidates = @(Invoke-MySql $candidateSql | ForEach-Object {
    $parts = $_ -split "`t"
    [pscustomobject]@{ task_id=$parts[0]; material_id=$parts[1]; course_id=$parts[2]; execution_round=[int]$parts[3]; initial_segments=[int]$parts[4] }
})
if ($candidates.Count -lt $MaterialCount) { throw "Need $MaterialCount completed parse tasks, found $($candidates.Count)" }

$snapshots = @{}
foreach ($candidate in $candidates) {
    $taskSql = "SELECT status,delivery_attempt_count,api_attempt_count,(SELECT COUNT(*) FROM task_checkpoint tc WHERE tc.task_id=bt.id),(SELECT COUNT(*) FROM course_segment cs WHERE cs.material_id=bt.material_id) FROM background_task bt WHERE bt.id=UNHEX(REPLACE('$($candidate.task_id)','-',''));"
    $parts = (Invoke-MySql $taskSql) -split "`t"
    $snapshots[$candidate.task_id] = [ordered]@{
        task_status=$parts[0]; delivery_attempts=[int]$parts[1]; api_attempts=[int]$parts[2]
        checkpoint_count=[int]$parts[3]; segment_count=[int]$parts[4]
        qdrant_count=[int](Get-QdrantMaterialCount $candidate.material_id)
        lucene_hash=Get-LuceneHash $candidate.course_id
    }
}

$dlqBefore = Get-DlqCount
$deliveryRows = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $candidates) {
    foreach ($load in $DuplicateLoads) {
        $messageId = [guid]::NewGuid().ToString()
        $payload = @{ taskId=$candidate.task_id; executionRound=$candidate.execution_round; messageId=$messageId; publishedAt=(Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json -Compress
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
        $publishOutput = docker exec -e METRICS_PAYLOAD_BASE64=$encoded -e METRICS_REPEAT=$load finalweek-rabbitmq-1 sh -c 'payload=$(echo "$METRICS_PAYLOAD_BASE64" | base64 -d); i=0; while [ "$i" -lt "$METRICS_REPEAT" ]; do rabbitmqadmin -q -u "$RABBITMQ_DEFAULT_USER" -p "$RABBITMQ_DEFAULT_PASS" publish message -e finalweek.tasks -k parse -m "$payload" -p ''{"content_type":"application/json","delivery_mode":2,"headers":{"__TypeId__":"com.finalweek.task.TaskMessage"}}'' --non-interactive || exit 1; i=$((i+1)); done'
        if ($LASTEXITCODE -ne 0) { throw "RabbitMQ publish failed: $publishOutput" }
        $deliveryRows.Add([pscustomobject]@{ task_id=$candidate.task_id; material_id=$candidate.material_id; duplicate_load=$load; message_id=$messageId; published=$load })
    }
}

$deadline = (Get-Date).AddSeconds(60)
do {
    $queueLine = docker exec finalweek-rabbitmq-1 rabbitmqctl list_queues name messages 2>$null | Where-Object { $_ -match '^finalweek\.parse\s+' }
    $remaining = if ($queueLine) { [int](($queueLine -split '\s+')[1]) } else { -1 }
    if ($remaining -eq 0) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
if ($remaining -ne 0) { throw "Queue did not drain, remaining=$remaining" }
Start-Sleep -Seconds 2

$comparisons = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $candidates) {
    $before = $snapshots[$candidate.task_id]
    $taskSql = "SELECT status,delivery_attempt_count,api_attempt_count,(SELECT COUNT(*) FROM task_checkpoint tc WHERE tc.task_id=bt.id),(SELECT COUNT(*) FROM course_segment cs WHERE cs.material_id=bt.material_id) FROM background_task bt WHERE bt.id=UNHEX(REPLACE('$($candidate.task_id)','-',''));"
    $parts = (Invoke-MySql $taskSql) -split "`t"
    $after = [ordered]@{
        task_status=$parts[0]; delivery_attempts=[int]$parts[1]; api_attempts=[int]$parts[2]
        checkpoint_count=[int]$parts[3]; segment_count=[int]$parts[4]
        qdrant_count=[int](Get-QdrantMaterialCount $candidate.material_id)
        lucene_hash=Get-LuceneHash $candidate.course_id
    }
    $unchanged = ($before.task_status -eq $after.task_status) -and ($before.delivery_attempts -eq $after.delivery_attempts) -and
        ($before.api_attempts -eq $after.api_attempts) -and ($before.checkpoint_count -eq $after.checkpoint_count) -and
        ($before.segment_count -eq $after.segment_count) -and ($before.qdrant_count -eq $after.qdrant_count) -and
        ($before.lucene_hash -eq $after.lucene_hash)
    $comparisons.Add([pscustomobject]@{ task_id=$candidate.task_id; material_id=$candidate.material_id; course_id=$candidate.course_id; before=$before; after=$after; business_results_unchanged=$unchanged })
}
$dlqAfter = Get-DlqCount
$totalDuplicates = ($candidates.Count * ($DuplicateLoads | Measure-Object -Sum).Sum)
$changedMaterials = @($comparisons | Where-Object { -not $_.business_results_unchanged }).Count
$result = [ordered]@{
    run_id=$runId; materials=$candidates.Count; loads=$DuplicateLoads; duplicate_deliveries=$totalDuplicates
    changed_materials=$changedMaterials; duplicate_business_result_count=$changedMaterials
    unchanged_materials=@($comparisons | Where-Object business_results_unchanged).Count
    dlq_before=$dlqBefore; dlq_after=$dlqAfter; comparisons=$comparisons
}
$deliveryRows | Export-Csv -LiteralPath (Join-Path $rawDirectory "rabbitmq-idempotency-deliveries-$runId.csv") -NoTypeInformation -Encoding utf8
$jsonPath = Join-Path $rawDirectory "rabbitmq-idempotency-$runId.json"
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding utf8
$result | ConvertTo-Json -Depth 12
