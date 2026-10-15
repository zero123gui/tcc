<#
.SYNOPSIS
    Inicia o ha-dashboard (painel do cluster raft-ha-manager).

.DESCRIPTION
    Pode ser executado de qualquer pasta. Entra na pasta do painel, cria o
    config.yaml na primeira execução e pede a senha do usuário raft_monitor do
    PostgreSQL em um campo oculto (ela não é gravada em disco).

    Se a variável de ambiente HA_PG_PASSWORD já estiver definida, ela é usada
    e nada é perguntado.

.PARAMETER Demo
    Usa um cluster simulado, sem VMs e sem senha.

.EXAMPLE
    .\ha-dashboard\run.ps1

.EXAMPLE
    .\ha-dashboard\run.ps1 -Demo
#>
param([switch]$Demo)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    Write-Host "Ambiente Python não encontrado em .venv." -ForegroundColor Red
    Write-Host "Crie com:"
    Write-Host "  py -3.12 -m venv .venv"
    Write-Host "  .venv\Scripts\pip install -r requirements.txt"
    exit 1
}

if (-not (Test-Path "config.yaml")) {
    Copy-Item "config.example.yaml" "config.yaml"
    Write-Host "config.yaml criado a partir do exemplo." -ForegroundColor Cyan
}

# A porta 8000 ocupada é o erro mais comum (outra instância do painel).
$busy = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($busy[0].OwningProcess)"
    Write-Host "A porta 8000 já está em uso pelo processo $($busy[0].OwningProcess):" -ForegroundColor Red
    Write-Host "  $($owner.CommandLine)"
    Write-Host "Encerre esse processo (ou outra instância do painel) e tente de novo."
    exit 1
}

if (-not $Demo -and -not $env:HA_PG_PASSWORD) {
    $secure = Read-Host "Senha do usuário raft_monitor (PostgreSQL), Enter para pular" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($plain) { $env:HA_PG_PASSWORD = $plain }
    else { Write-Host "Sem senha: o estado do PostgreSQL aparecerá como 'sem senha'." -ForegroundColor Yellow }
}

$args = @("-m", "ha_dashboard")
if ($Demo) { $args += "--demo" }

Write-Host "Abra http://127.0.0.1:8000  (Ctrl+C para encerrar)" -ForegroundColor Green
& $python @args
