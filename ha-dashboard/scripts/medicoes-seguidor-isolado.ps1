# Cenário "seguidor isolado volta": isola o STANDBY (não o líder) por $IsolateSecs e
# reconecta. Mede se o líder foi derrubado, quantos mandatos o cluster avançou e por
# quanto tempo ficou sem líder. O painel não abre experimento (o alvo não é líder nem
# primary), então a medição é feita aqui, pelos eventos e pelo status do painel.
param([int]$Reps = 5, [int]$IsolateSecs = 60, [int]$ObserveSecs = 60, [string]$Csv = "")
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
function Events($j, $kind, $t0) {
  @(foreach ($n in "node-1", "node-2", "node-3") { $j.logs.$n | Where-Object { -not $_.history -and $_.kind -eq $kind -and $_.host_ts -ge $t0 } | ForEach-Object { [pscustomobject]@{ node = $n; ts = [double]$_.host_ts } } })
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

Log "=== seguidor isolado por $IsolateSecs s e reconectado ($Reps repeticoes)"
$rows = @()
for ($rep = 1; $rep -le $Reps; $rep++) {
  $j = WaitStable 45 300
  if (-not $j) { Log "ABORTANDO: cluster nao ficou estavel"; exit 1 }
  $L = ($j.nodes | Where-Object role -eq "LEADER").id
  $S = ($j.nodes | Where-Object pg_state -eq "STANDBY").id
  $term0 = ($j.nodes | Where-Object id -eq $L).term
  Log "--- rep $rep : lider $L (term $term0), isolando o standby $S"
  $t0 = Now
  Act $S "partition-on"
  Start-Sleep $IsolateSecs
  $termS = ((State).nodes | Where-Object id -eq $S).term
  $t1 = Now
  Act $S "partition-off"
  Start-Sleep $ObserveSecs
  $j = State
  $stepDowns = @(Events $j 'STEP_DOWN' $t1) + @(Events $j 'QUORUM_LOST' $t1)
  $leadersAfter = @(Events $j 'LEADER' $t1)
  $elections = @(Events $j 'ELECTION' $t0)
  $termEnd = ($j.nodes | Where-Object role -eq "LEADER" | Select-Object -First 1).term
  $Lend = ($j.nodes | Where-Object role -eq "LEADER" | Select-Object -First 1).id
  # @(): no PowerShell 5.1 um objeto sozinho não tem .Count
  $deposed = @($stepDowns | Where-Object node -eq $L).Count -gt 0
  $promotions = @(Events $j 'PROMOTED' $t1)
  $healed = @(Events $j 'HEALED' $t1 | Where-Object node -eq $L)
  $gap = $null
  if ($deposed -and $leadersAfter.Count -gt 0) {
    $sd = ($stepDowns | Where-Object node -eq $L | Sort-Object ts | Select-Object -First 1).ts
    # Primeiro líder eleito depois da renúncia; 0 se o novo líder já existia (troca direta)
    $next = $leadersAfter | Where-Object ts -ge $sd | Sort-Object ts | Select-Object -First 1
    $gap = if ($next) { $next.ts - $sd } else { 0.0 }
  }
  $row = [pscustomobject]@{
    rep = $rep; lider_antes = $L; standby_isolado = $S; mandato_antes = $term0
    mandato_do_isolado_antes_de_voltar = $termS; mandato_final = $termEnd; lider_final = $Lend
    eleicoes = $elections.Count; lider_derrubado = $deposed
    promocoes = $promotions.Count; self_healing_do_lider = $healed.Count
    sem_lider_s = if ($gap -ne $null) { "{0:N1}" -f $gap } else { "" }
  }
  $rows += $row
  Log ("  isolado chegou ao mandato {0}; final: lider {1} mandato {2}; eleicoes {3}; lider derrubado: {4}; sem lider: {5}; promocoes: {6}; self-healing do antigo lider: {7}" -f $termS, $Lend, $termEnd, $elections.Count, $deposed, $row.sem_lider_s, $promotions.Count, $healed.Count)
}
if ($Csv) { $rows | Export-Csv -Path $Csv -NoTypeInformation -Encoding UTF8; Log "CSV: $Csv" }
Log "fim."
