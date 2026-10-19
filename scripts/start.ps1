param(
    [switch]$Takeover,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Test-NonEmptyFile([string]$Path) {
    (Test-Path -LiteralPath $Path -PathType Leaf) -and (Get-Item -LiteralPath $Path).Length -gt 0
}

Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) {
        throw '.env가 없습니다. .env.example을 복사해 설정하세요.'
    }
    if (-not (Test-NonEmptyFile '.secrets/postgres_password')) {
        throw '.secrets/postgres_password가 없거나 비어 있습니다.'
    }
    if (-not (Test-Path -LiteralPath '.secrets/google_client_secret' -PathType Leaf)) {
        throw '.secrets/google_client_secret 파일이 없습니다. Google 로그인을 쓰지 않아도 빈 파일은 필요합니다.'
    }

    # PowerShell 5.1은 native stderr도 ErrorRecord로 승격하므로 Docker 구간은 종료코드로 판정한다.
    $ErrorActionPreference = 'Continue'
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker 엔진에 연결할 수 없습니다. Docker Desktop을 시작하세요.'
    }

    $labelsJson = & docker inspect --format '{{json .Config.Labels}}' jobsite-v006-backend-1 2>$null
    $owner = if ($LASTEXITCODE -eq 0) { ($labelsJson | ConvertFrom-Json).'com.docker.compose.project.working_dir' }
    if ($owner) {
        $ownerPath = [IO.Path]::GetFullPath($owner.Trim())
        if ($ownerPath -ne $repoRoot -and -not $Takeover) {
            throw "실행 중인 JobSight는 다른 작업공간 소유입니다: $ownerPath`n현재 작업공간으로 교체하려면 .\scripts\start.ps1 -Takeover 를 실행하세요. DB 볼륨은 유지됩니다."
        }
    }

    & docker compose config --quiet *> $null
    if ($LASTEXITCODE -ne 0) { throw '.env 또는 Compose 설정이 올바르지 않습니다.' }

    Write-Host 'JobSight 시작 전 검증: PASS'
    if ($CheckOnly) { return }

    $arguments = @('compose', 'up', '-d', '--build')
    if ($Takeover) { $arguments += '--force-recreate' }
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up이 실패했습니다.' }

    $containers = @('jobsite-v006-db-1', 'jobsite-v006-backend-1', 'jobsite-v006-frontend-1')
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $statuses = @($containers | ForEach-Object {
            & docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $_ 2>$null
        })
        if ($statuses.Count -eq $containers.Count -and @($statuses | Where-Object { $_ -ne 'running/healthy' }).Count -eq 0) {
            Write-Host 'JobSight 컨테이너 상태: PASS'
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    & docker compose ps -a
    & docker compose logs --tail=100 backend
    throw '3분 안에 모든 컨테이너가 healthy 상태가 되지 않았습니다.'
} finally {
    Pop-Location
}
