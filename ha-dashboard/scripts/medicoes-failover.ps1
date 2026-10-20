param([int]$Reps = 5, [int]$Skip = 0, [string[]]$Scenarios = @("raft-stop", "pg-stop", "partition-on"))
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = "Continue"
$api = "http://127.0.0.1:8000"
# Write-Host, e não a saída da função: senão a mensagem vira parte do valor de retorno
function Log($m) { Write-Host ("{0} {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function State { (Invoke-WebRequest -Uri "$api/api/state" -UseBasicParsing -TimeoutSec 10).Content | ConvertFrom-Json }
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }
function Act($node, $action) {
  $r = Invoke-WebRequest -Uri "$api/api/nodes/$node/actions/$action" -Method POST -Headers @{ "X-HA-Dashboard" = "1" } -UseBasicParsing -TimeoutSec 90
  $b = $r.Content | ConvertFrom-Json
  Log "  acao $action em ${node}: exit=$($b.exit) experimento=$($b.experiment)"
}
function FirstEvent($j, $node, $kind, $t0) {
  $e = $j.logs.$node | Where-Object { -not $_.history -and $_.kind -eq $kind -and $_.host_ts -ge $t0 } | Select-Object -First 1
  if ($e) { [double]$e.host_ts } else { $null }
}
function WaitFor([scriptblock]$cond, $secs, $what) {
  $d = (Get-Date).AddSeconds($secs)
  while ((Get-Date) -lt $d) { try { $j = State; if (& $cond $j) { return $j } } catch {}; Start-Sleep 1 }
  Log "  TIMEOUT esperando: $what"; return $null
}
# Cluster saudável: um líder, todos no mesmo mandato, um PRIMARY e um STANDBY, sem experimento ativo
function Healthy($j) {
  $leaders = @($j.nodes | Where-Object role -eq "LEADER")
  $terms = @($j.nodes | ForEach-Object term | Sort-Object -Unique)
  $prim = @($j.nodes | Where-Object pg_state -eq "PRIMARY")
  $stby = @($j.nodes | Where-Object pg_state -eq "STANDBY")
  $active = @($j.experiments | Where-Object active)
  return ($leaders.Count -eq 1 -and $terms.Count -eq 1 -and $prim.Count -eq 1 -and $stby.Count -eq 1 -and
          $active.Count -eq 0 -and $prim[0].id -eq $leaders[0].id)
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
function HealCheck($node, $t1, $label) {
  $j = WaitFor { param($j) (FirstEvent $j $node 'HEALED' $t1) -or (FirstEvent $j $node 'HEAL_FAILED' $t1) -or (FirstEvent $j $node 'HEAL_ABORTED' $t1) } 240 "self-healing de $node"
  if (-not $j) { return $false }
  $h = FirstEvent $j $node 'HEALED' $t1
  if ($h) { Log ("  self-healing de {0} concluido em {1:N1} s apos {2}" -f $node, ($h - $t1), $label); return $true }
  Log "  ERRO: self-healing de $node falhou ou foi abortado"; return $false
}

Log "=== medicoes: $Reps repeticoes de $($Scenarios -join ', ')"
for ($rep = 1; $rep -le $Reps; $rep++) {
  foreach ($sc in $Scenarios) {
    if ($Skip -gt 0) { $Skip--; continue }
    $j = WaitStable 45 300
    if (-not $j) { Log "ABORTANDO: cluster nao ficou estavel"; exit 1 }
    $L = ($j.nodes | Where-Object role -eq "LEADER").id
    $O = ($j.nodes | Where-Object { $_.id -ne $L -and $_.pg_state -eq "STANDBY" }).id
    Log "--- rep $rep / $sc : lider $L (term $(($j.nodes | Select-Object -First 1).term)), standby $O"
    $t0 = Now
    Act $L $sc
    $j = WaitFor { param($j) FirstEvent $j $O 'PROMOTED' $t0 } 150 "promocao em $O"
    if (-not $j) { Log "ABORTANDO: sem promocao"; exit 1 }
    Log ("  promocao confirmada em {0:N1} s" -f ((FirstEvent $j $O 'PROMOTED' $t0) - $t0))
    $restore = @{ "raft-stop" = "raft-start"; "pg-stop" = "pg-start"; "partition-on" = "partition-off" }[$sc]
    if ($sc -ne "partition-on") {
      WaitFor { param($j) -not @($j.experiments | Where-Object active).Count } 30 "fim do experimento" | Out-Null
    }
    $t1 = Now
    Act $L $restore
    if (-not (HealCheck $L $t1 $restore)) { exit 1 }
    $e = (State).experiments | Sort-Object id | Select-Object -Last 1
    Log ("  experimento {0}: {1} | novo lider {2} mandato {3} | marcos {4}" -f $e.id, $e.outcome, $e.new_leader, $e.new_term, (($e.marks.PSObject.Properties | Where-Object { $_.Value -ne $null } | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join " "))
  }
}
$j = WaitStable 45 300
Log "fim. estavel: $([bool]$j)"
