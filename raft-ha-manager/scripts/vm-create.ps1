<#
.SYNOPSIS
    Cria as VMs do cluster raft-ha-manager no VirtualBox.

.DESCRIPTION
    Usa o VBoxManage para criar e configurar as VMs automaticamente.
    Cria uma rede Host-Only (192.168.56.0/24) para comunicação entre os nós.

    Cada VM recebe dois adaptadores de rede:
      - Adaptador 1 (NAT): acesso à internet para instalar pacotes
      - Adaptador 2 (Host-Only): comunicação do cluster (192.168.56.x)

.PARAMETER IsoPath
    Caminho completo para o arquivo ISO do Ubuntu Server 22.04.

.PARAMETER Nodes
    Número de VMs a criar. Use 3 para validação no notebook, 5 para o ambiente completo.

.PARAMETER VmDir
    Diretório onde as VMs serão armazenadas. Padrão: pasta VirtualBox VMs do usuário.

.EXAMPLE
    .\vm-create.ps1 -IsoPath "C:\Users\guimi\Downloads\ubuntu-22.04.5-live-server-amd64.iso" -Nodes 3

.EXAMPLE
    .\vm-create.ps1 -IsoPath "D:\ISOs\ubuntu-22.04.5-live-server-amd64.iso" -Nodes 5
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$IsoPath,

    [Parameter(Mandatory = $false)]
    [ValidateSet(3, 5)]
    [int]$Nodes = 3,

    [Parameter(Mandatory = $false)]
    [string]$VmDir = "$env:USERPROFILE\VirtualBox VMs"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Força UTF-8 no console para exibir caracteres especiais corretamente
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# Caminho para o executável do VirtualBox
$VBoxManage = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"

if (-not (Test-Path $VBoxManage)) {
    Write-Error "VBoxManage não encontrado em '$VBoxManage'. Verifique a instalação do VirtualBox."
    exit 1
}

if (-not (Test-Path $IsoPath)) {
    Write-Error "ISO não encontrada em '$IsoPath'."
    exit 1
}

# ============================================================================
# Configuração das VMs
# ============================================================================

$HostOnlyIP   = "192.168.56.1"
$HostOnlyMask = "255.255.255.0"

# Definição de cada nó
$NodeDefs = @(
    @{ Name = "vm-node-1"; RAM = 1536; Arbiter = $false },
    @{ Name = "vm-node-2"; RAM = 1536; Arbiter = $false },
    @{ Name = "vm-node-3"; RAM = 512;  Arbiter = $true  },
    @{ Name = "vm-node-4"; RAM = 2048; Arbiter = $false },
    @{ Name = "vm-node-5"; RAM = 512;  Arbiter = $true  }
)

$NodesToCreate = $NodeDefs | Select-Object -First $Nodes

# ============================================================================
# Detectar a interface Host-Only correta (Windows usa nomes diferentes do Linux)
# No Linux: vboxnet0. No Windows: "VirtualBox Host-Only Ethernet Adapter"
# ============================================================================

Write-Host "`n[1/3] Detectando rede Host-Only (192.168.56.x)..." -ForegroundColor Cyan

$ifOutput = & $VBoxManage list hostonlyifs 2>$null | Out-String

# Encontra o bloco da interface que já tem o IP 192.168.56.1
$HostOnlyNet = $null
$currentName = $null
foreach ($line in ($ifOutput -split "`n")) {
    if ($line -match "^Name:\s+(.+)$") {
        $currentName = $Matches[1].Trim()
    }
    if ($line -match "IPAddress:\s+192\.168\.56\." -and $currentName) {
        $HostOnlyNet = $currentName
        break
    }
}

if ($HostOnlyNet) {
    Write-Host "  Interface existente encontrada: '$HostOnlyNet'" -ForegroundColor Green
} else {
    # Cria uma nova interface e captura o nome gerado pelo VirtualBox
    Write-Host "  Nenhuma interface 192.168.56.x encontrada. Criando uma nova..."
    $createOut = & $VBoxManage hostonlyif create 2>&1 | Out-String
    if ($createOut -match "Interface '(.+)' was successfully created") {
        $HostOnlyNet = $Matches[1].Trim()
    } else {
        Write-Error "Não foi possível criar a interface Host-Only. Saída: $createOut"
        exit 1
    }
    Write-Host "  Interface criada: '$HostOnlyNet'"

    # Configura o IP da nova interface
    & $VBoxManage hostonlyif ipconfig $HostOnlyNet `
        --ip $HostOnlyIP `
        --netmask $HostOnlyMask | Out-Null
}

# Remove o servidor DHCP se existir (usamos IPs estáticos)
# O erro "not found" é esperado e pode ser ignorado
$null = & $VBoxManage dhcpserver remove --ifname $HostOnlyNet 2>&1
Write-Host "  DHCP desativado (IPs estáticos serão configurados nos scripts de setup)." -ForegroundColor Green

# ============================================================================
# Criar cada VM
# ============================================================================

Write-Host "`n[2/3] Criando $Nodes VM(s)..." -ForegroundColor Cyan

foreach ($node in $NodesToCreate) {
    $vmName = $node.Name
    $vmRam  = $node.RAM
    $vmPath = Join-Path $VmDir $vmName

    Write-Host "`n  → $vmName ($vmRam MB RAM)..." -ForegroundColor White

    # Verifica se a VM já existe
    $existing = & $VBoxManage list vms 2>$null | Select-String $vmName
    if ($existing) {
        Write-Host "    VM '$vmName' já existe. Pulando criação." -ForegroundColor Yellow
        continue
    }

    # Cria a VM
    & $VBoxManage createvm `
        --name $vmName `
        --ostype "Ubuntu_64" `
        --register `
        --basefolder $VmDir | Out-Null

    # Configura hardware
    & $VBoxManage modifyvm $vmName `
        --memory $vmRam `
        --cpus 2 `
        --vram 16 `
        --boot1 dvd `
        --boot2 disk `
        --boot3 none `
        --audio-driver none `
        --usb off `
        --usbehci off | Out-Null

    # Adaptador 1: NAT (internet)
    & $VBoxManage modifyvm $vmName `
        --nic1 nat `
        --nictype1 virtio | Out-Null

    # Adaptador 2: Host-Only (cluster)
    & $VBoxManage modifyvm $vmName `
        --nic2 hostonly `
        --nictype2 virtio `
        --hostonlyadapter2 $HostOnlyNet | Out-Null

    # Cria o disco virtual (20 GB para PG nodes, 10 GB para árbitros)
    $diskSize = if ($node.Arbiter) { 10240 } else { 20480 }
    $diskPath = Join-Path $vmPath "$vmName.vdi"

    & $VBoxManage createmedium disk `
        --filename $diskPath `
        --size $diskSize `
        --format VDI | Out-Null

    # Cria e conecta o controlador SATA
    & $VBoxManage storagectl $vmName `
        --name "SATA Controller" `
        --add sata `
        --controller IntelAhci | Out-Null

    & $VBoxManage storageattach $vmName `
        --storagectl "SATA Controller" `
        --port 0 `
        --device 0 `
        --type hdd `
        --medium $diskPath | Out-Null

    # Conecta a ISO de instalação
    & $VBoxManage storageattach $vmName `
        --storagectl "SATA Controller" `
        --port 1 `
        --device 0 `
        --type dvddrive `
        --medium $IsoPath | Out-Null

    Write-Host "    $vmName criada com sucesso." -ForegroundColor Green
}

# ============================================================================
# Resumo final
# ============================================================================

Write-Host "`n[3/3] Resumo" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

foreach ($node in $NodesToCreate) {
    $nodeNum = $node.Name.Split("-")[-1]
    $staticIp = "192.168.56.10$nodeNum"
    $role = if ($node.Arbiter) { "Árbitro (sem PostgreSQL)" } else { "Nó com PostgreSQL" }
    Write-Host "  $($node.Name)  $staticIp  $($node.RAM) MB  $role"
}

Write-Host "`nPróximos passos:" -ForegroundColor Cyan
Write-Host "  1. Inicie cada VM no VirtualBox e instale o Ubuntu Server 22.04"
Write-Host "  2. Durante a instalação, habilite o servidor SSH"
Write-Host "  3. Siga as instruções em scripts\README.md"
Write-Host ""
