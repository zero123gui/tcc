# Cenários em que NADA deve mudar no cluster: um nó que não é o líder fica fora e volta.
#   standby-agente : para o agente do standby por $DownSecs e religa
#   arbitro-agente : para o agente do árbitro por $DownSecs e religa
# Em cada repetição mede, até $ObserveSecs depois de religar, se o líder ou o mandato
# mudaram e quantas eleições começaram. O resultado esperado é: nenhuma mudança.
param([int]$Reps = 3, [int]$DownSecs = 60, [int]$ObserveSecs = 60, [string]$Csv = "")
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"
$api = "http://127.0.0.1:8000"
function Log($m) { Write-Host ("{0} {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function State { (Invoke-WebRequest -Uri "$api/api/state" -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json }
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }
function Act($node, $action) {
  $r = Invoke-WebRequest -Uri "$api/api/nodes/$node/actions/$action" -Method POST -Headers @{ "X-HA-Dashboard" = "1" } -UseBasicParsing -TimeoutSec 90
  Log "  acao $action em ${node}: exit=$(($r.Content | ConvertFrom-Json).exit)"
}
function CountEvents($j, $kind, $t0) {
  $c = 0
  foreach ($n in "node-1", "node-2", "node-3") { $c += @($j.logs.$n | Where-Object { -not $_.history -and $_.kind -eq $kind -and $_.host_ts -ge $t0 }).Count }
  return $c
}
function Healthy($j) {
  $leaders = @($j.nodes | Where-Object role -eq "LEADER")
  $terms = @($j.nodes | ForEach-Object term | Sort-Object -Unique)
  $prim = @($j.nodes | Where-Object pg_state -eq "PRIMARY")
  $stby = @($j.nodes | Where-Object pg_state -eq "STANDBY")
  return ($leaders.Count -eq 1 -and $terms.Count -eq 1 -and $prim.Count -eq 1 -and $stby.Count -eq 1 -and $prim[0].id -eq $leaders[0].id)
}
function WaitStable($holdSecs, $maxSecs) {
  $deadline = (Get-Date).AddSeconds($maxSecs); $since = $null; $term = $null
  while ((Get-Date) -lt $deadline) {
    try {
      $j = State
      if (Healthy $j) {
        $t = ($j.nodes | Select-Object -First 1).term
        if ($since -eq $null -or $t -ne $term) { $since = Get-Date; $term = $t }
        elseif (((Get-Date) - $since).TotalSeconds -ge $holdSecs) { return $j }
      } else { $since = $null }
    } catch { $since = $null }
    Start-Sleep 2
  }
  return $null
}

$rows = @()
foreach ($sc in "standby-agente", "arbitro-agente") {
  for ($rep = 1; $rep -le $Reps; $rep++) {
    $j = WaitStable 45 300
    if (-not $j) { Log "ABORTANDO: cluster nao ficou estavel"; exit 1 }
    $L = ($j.nodes | Where-Object role -eq "LEADER").id
    $term0 = ($j.nodes | Where-Object id -eq $L).term
    $target = if ($sc -eq "standby-agente") { ($j.nodes | Where-Object pg_state -eq "STANDBY").id } else { ($j.nodes | Where-Object arbiter -eq $true).id }
    if (-not $target) { $target = "node-3" }
    Log "--- $sc rep $rep : lider $L (mandato $term0), alvo $target"
    $t0 = Now
    Act $target "raft-stop"
    Start-Sleep $DownSecs
    Act $target "raft-start"
    Start-Sleep $ObserveSecs
    $j2 = State
    $L2 = ($j2.nodes | Where-Object role -eq "LEADER" | Select-Object -First 1).id
    $term2 = ($j2.nodes | Where-Object role -eq "LEADER" | Select-Object -First 1).term
    $elections = CountEvents $j2 'ELECTION' $t0
    $denied = (CountEvents $j2 'PRE_VOTE_DENIED' $t0) + (CountEvents $j2 'PRE_VOTE_LOST' $t0)
    $unchanged = ($L2 -eq $L -and $term2 -eq $term0 -and $elections -eq 0)
    $rows += [pscustomobject]@{ cenario = $sc; rep = $rep; alvo = $target; lider_antes = $L; lider_depois = $L2
      mandato_antes = $term0; mandato_depois = $term2; eleicoes = $elections; pre_votos_negados = $denied; sem_mudanca = $unchanged }
    Log ("  lider {0} -> {1}; mandato {2} -> {3}; eleicoes {4}; pre-votos negados {5}; sem mudanca: {6}" -f $L, $L2, $term0, $term2, $elections, $denied, $unchanged)
  }
}
if ($Csv) { $rows | Export-Csv -Path $Csv -NoTypeInformation -Encoding UTF8; Log "CSV: $Csv" }
Log "fim."
