# Wipe every table and reload the dataset, then print row counts vs the dataset README.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\reset-and-seed.ps1 [-Api http://localhost:8088]
# (Windows refuses unsigned scripts by default; -ExecutionPolicy Bypass applies to this one run only.)
param([string]$Api = "http://localhost:8088")
$ErrorActionPreference = "Stop"
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
try { Invoke-RestMethod -Method Post -Uri "$Api/api/auth/login" -WebSession $s -ContentType "application/json" -Body '{"userId":"U002"}' | Out-Null } catch {}
Invoke-RestMethod -Method Post -Uri "$Api/api/admin/reset" -WebSession $s | Out-Null
$r = Invoke-RestMethod -Uri "$Api/api/admin/seed-report" -WebSession $s
$r.tables.PSObject.Properties | ForEach-Object {
  "{0,-22} {1,5} / {2,-5} {3}" -f $_.Name, $_.Value.actual, $_.Value.expected, $(if ($_.Value.match) { "ok" } else { "MISMATCH" })
}
if ($r.allMatch) { "ALL MATCH" } else { "MISMATCHES FOUND"; exit 1 }
