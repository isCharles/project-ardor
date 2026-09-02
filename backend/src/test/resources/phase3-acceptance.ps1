param(
    [string]$ApiBaseUrl = "http://localhost:8080",
    [string]$ResumePath = "target/acceptance-resume.docx",
    [string]$LlmBaseUrl = "http://project-ardor-fake-llm:8090"
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$webSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$email = "phase3.acceptance@ardor.local"
$password = "Ardor-Acceptance-2026!"

function Get-CsrfHeaders {
    param([Microsoft.PowerShell.Commands.WebRequestSession]$Session = $webSession)
    $csrf = Invoke-RestMethod -Uri "$ApiBaseUrl/api/auth/csrf" -WebSession $Session
    $headers = @{}
    $headers[$csrf.headerName] = $csrf.token
    return $headers
}

$credentials = @{ email = $email; password = $password; displayName = "Phase 3 验收用户" } | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/auth/register" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $credentials | Out-Null
} catch {
    $login = @{ email = $email; password = $password } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/auth/login" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $login | Out-Null
}

$profileBody = @{ displayName = "Phase 3 验收用户"; headline = "Java 后端工程师"; targetRoles = @("Java 后端工程师") } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$ApiBaseUrl/api/profile" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $profileBody | Out-Null

$llmBody = @{ provider = "OPENAI_COMPATIBLE"; baseUrl = $LlmBaseUrl; model = "acceptance-model"; apiKey = "acceptance-key" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/settings/llm/test" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $llmBody | Out-Null
Invoke-RestMethod -Method Put -Uri "$ApiBaseUrl/api/settings/llm" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $llmBody | Out-Null

$resume = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/resumes" -WebSession $webSession -Headers (Get-CsrfHeaders) -Form @{ file = Get-Item -LiteralPath $ResumePath }
if ($resume.parseStatus -ne "PARSED") { throw "简历未成功解析：$($resume.parseStatus)" }

$analysisTask = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/resumes/$($resume.id)/analysis" -WebSession $webSession -Headers (Get-CsrfHeaders)
$pollCount = 0
while ($analysisTask.status -in @("QUEUED", "RUNNING") -and $pollCount -lt 60) {
    Start-Sleep -Milliseconds 500
    $analysisTask = Invoke-RestMethod -Uri "$ApiBaseUrl/api/resumes/$($resume.id)/analysis/status" -WebSession $webSession
    $pollCount++
}
if ($analysisTask.status -ne "COMPLETED") { throw "简历异步分析失败：$($analysisTask.errorMessage)" }
$analysis = Invoke-RestMethod -Uri "$ApiBaseUrl/api/resumes/$($resume.id)/analysis" -WebSession $webSession
if (-not $analysis.analysis.skills) { throw "简历分析未返回 skills" }

$interviewBody = @{ resumeAnalysisId = $analysis.id; targetCompany = "Ardor Labs"; targetRole = "Java 后端工程师"; questionCount = 3 } | ConvertTo-Json
$interview = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/interviews" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $interviewBody

$progress = Invoke-RestMethod -Uri "$ApiBaseUrl/api/interviews/$($interview.id)/next-question" -WebSession $webSession
while ($null -ne $progress.nextQuestion) {
    $answerBody = @{ questionId = $progress.nextQuestion.id; answerText = "我会先明确问题和指标，再说明方案、取舍、验证过程与结果。"; durationSeconds = 45 } | ConvertTo-Json
    $progress = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/interviews/$($interview.id)/answers" -WebSession $webSession -Headers (Get-CsrfHeaders) -ContentType "application/json" -Body $answerBody
}
if (-not $progress.readyToFinish) { throw "回答完成后面试未进入可结束状态" }

$evaluationResult = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/interviews/$($interview.id)/finish" -WebSession $webSession -Headers (Get-CsrfHeaders)
if ($evaluationResult.overallScore -ne 86) { throw "面试评价总分不符合预期" }

$otherSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$otherEmail = "phase3.other@ardor.local"
$otherCredentials = @{ email = $otherEmail; password = $password; displayName = "隔离验收用户" } | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/auth/register" -WebSession $otherSession -Headers (Get-CsrfHeaders -Session $otherSession) -ContentType "application/json" -Body $otherCredentials | Out-Null
} catch {
    $otherLogin = @{ email = $otherEmail; password = $password } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/auth/login" -WebSession $otherSession -Headers (Get-CsrfHeaders -Session $otherSession) -ContentType "application/json" -Body $otherLogin | Out-Null
}
$resumeIsolation = Invoke-WebRequest -Uri "$ApiBaseUrl/api/resumes/$($resume.id)/analysis" -WebSession $otherSession -SkipHttpErrorCheck
$interviewIsolation = Invoke-WebRequest -Uri "$ApiBaseUrl/api/interviews/$($interview.id)" -WebSession $otherSession -SkipHttpErrorCheck
if ($resumeIsolation.StatusCode -ne 404 -or $interviewIsolation.StatusCode -ne 404) {
    throw "跨用户资源隔离验收失败"
}

[ordered]@{
    resumeId = $resume.id
    resumeParseStatus = $resume.parseStatus
    analysisId = $analysis.id
    interviewId = $interview.id
    answeredCount = $progress.answeredCount
    totalQuestions = $progress.totalQuestions
    overallScore = $evaluationResult.overallScore
    crossUserResumeStatus = $resumeIsolation.StatusCode
    crossUserInterviewStatus = $interviewIsolation.StatusCode
} | ConvertTo-Json
