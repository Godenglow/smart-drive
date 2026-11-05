"""Exercise runtime scripts in disposable workspaces with mocked processes, never live services."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PWSH = shutil.which('pwsh')


@unittest.skipUnless(PWSH, 'PowerShell 7 is required for runtime script tests')
class RuntimeScriptTest(unittest.TestCase):
    def setUp(self):
        base = ROOT / 'logs/runtime-tool-tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='workspace with spaces ', dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        self.env = os.environ.copy()
        self.env.update(TEST_WORKSPACE=str(self.root), REDIS_HOST='explicit-host',
                        SMARTDRIVE_DB_PASSWORD='test-only-unused-password')
        (self.root / '.env.local').write_text('REDIS_HOST=\nSMARTDRIVE_RUNTIME_TEST_FALLBACK=fallback\n')
        self.env.pop('SMARTDRIVE_RUNTIME_TEST_FALLBACK', None)

    def run_script(self, name, harness):
        shutil.copyfile(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        script = self.root / 'harness.ps1'
        script.write_text("$ErrorActionPreference = 'Stop'\n" + harness, encoding='utf-8')
        result = subprocess.run([PWSH, '-NoProfile', '-File', str(script)], env=self.env,
                                capture_output=True, text=True, encoding='utf-8', timeout=45)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return json.loads(result.stdout.strip().splitlines()[-1])

    def day21(self, existing=False, no_env_file=False, missing_source=False, foreign=False):
        for name in ('account-service', 'driver-service', 'order-service', 'gateway'):
            target = self.root / name / 'target'
            target.mkdir(parents=True)
            if not missing_source:
                (target / f'{name}-1.0.0-SNAPSHOT.jar').write_text(name)
        if no_env_file:
            (self.root / '.env.local').unlink()
        self.env['TEST_EXISTING'] = str(int(existing))
        self.env['TEST_FOREIGN'] = str(int(foreign))
        return self.run_script('day21-runtime.ps1', r'''
$global:testStarted = @(); $global:testStopped = @(); $failure = $null
function Get-Command { param($Name) [pscustomobject]@{Source='X:\portable-jdk\bin\java.exe'} }
function Get-NetTCPConnection {
    param($LocalPort, $State)
    $global:testServiceName = @{8080='gateway';8081='account-service';8082='driver-service';8083='order-service'}[[int]$LocalPort]
    if ($env:TEST_EXISTING -eq '1') { [pscustomobject]@{OwningProcess=42} }
}
function Get-CimInstance {
    param($ClassName, $Filter)
    $location = if ($env:TEST_FOREIGN -eq '1') {'X:\unrelated'} else {$env:TEST_WORKSPACE}
    [pscustomobject]@{Name='java.exe';CommandLine="java -jar `"$location\$global:testServiceName.jar`""}
}
function Stop-Process { param($Id) $global:testStopped += $Id }
function Start-Sleep { param($Seconds) }
function Invoke-RestMethod { param($Uri, $TimeoutSec) @{status='UP'} }
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    $global:testStarted += @{java=$FilePath;arguments=$ArgumentList;windowStyle=$WindowStyle}
    [pscustomobject]@{Id=123}
}
try { & "$env:TEST_WORKSPACE/scripts/day21-runtime.ps1" | Out-Null }
catch { $failure = $_.Exception.Message }
@{started=$global:testStarted;stopped=$global:testStopped;failure=$failure;redis=$env:REDIS_HOST;
  fallback=$env:SMARTDRIVE_RUNTIME_TEST_FALLBACK} | ConvertTo-Json -Depth 6 -Compress
''')

    def test_day21_bootstraps_all_jars_without_historical_logs_or_running_services(self):
        result = self.day21()
        self.assertIsNone(result['failure'])
        self.assertEqual(4, len(result['started']))
        self.assertEqual([], result['stopped'])
        self.assertEqual('explicit-host', result['redis'])
        self.assertEqual('fallback', result['fallback'])
        for process in result['started']:
            self.assertEqual(r'X:\portable-jdk\bin\java.exe', process['java'])
            self.assertEqual('Hidden', process['windowStyle'])
            self.assertTrue(process['arguments'][1].startswith('"'))
        self.assertEqual('gateway', (self.root / 'logs/day21-runtime/gateway.jar').read_text())

    def test_day21_stops_verified_services_and_restarts_from_build_outputs(self):
        result = self.day21(existing=True)
        self.assertIsNone(result['failure'])
        self.assertEqual(4, len(result['started']))
        self.assertEqual([42] * 4, result['stopped'])

    def test_day21_can_use_process_environment_without_local_env_file(self):
        result = self.day21(no_env_file=True)
        self.assertIsNone(result['failure'])
        self.assertEqual('explicit-host', result['redis'])
        self.assertEqual(4, len(result['started']))

    def test_day21_missing_build_does_not_stop_a_healthy_service(self):
        result = self.day21(existing=True, missing_source=True)
        self.assertIn('Missing build JAR', result['failure'])
        self.assertEqual([], result['started'])
        self.assertEqual([], result['stopped'])

    def test_day21_refuses_another_projects_java_process(self):
        result = self.day21(existing=True, foreign=True)
        self.assertIn('Unexpected process', result['failure'])
        self.assertEqual([], result['started'])
        self.assertEqual([], result['stopped'])

    def day19(self, maven_failure=False):
        self.env['TEST_MAVEN_FAIL'] = str(int(maven_failure))
        reports = self.root / 'order-service/target/surefire-reports'
        reports.mkdir(parents=True)
        (reports / 'TEST-stale.xml').write_text('<testsuite tests="0"/>')
        (self.root / 'scripts/start-local.ps1').write_text('$global:restored++\n')
        (self.root / 'order-service.jar').write_text('stable running order-service')
        return self.run_script('verify-day19.ps1', r'''
$global:restored=0; $global:testStopped=@(); $global:testLive=$false; $failure=$null
function Get-NetTCPConnection { param($LocalPort,$State) [pscustomobject]@{OwningProcess=42} }
function Get-CimInstance {
    param($ClassName,$Filter)
    [pscustomobject]@{Name='java.exe';CommandLine="java -jar `"$env:TEST_WORKSPACE\order-service.jar`""}
}
function Stop-Process { param($Id) $global:testStopped += $Id }
function python {
    $global:LASTEXITCODE=0
    if ($args -contains 'scripts/verify-day19.py') {
        if ($global:restored -ne 1) { throw 'HTTP ran before order-service was restored' }
        $global:testLive=$true
        @{passed=$true;cleanupPassed=$true;runId='fake-test-run'} | ConvertTo-Json |
            Set-Content "$env:TEST_WORKSPACE/logs/day19-http-result.json"
    }
}
function mvn.cmd {
    if ($global:testStopped.Count -ne 1) { throw 'Maven ran without publisher isolation' }
    if ($env:TEST_MAVEN_FAIL -eq '1') { $global:LASTEXITCODE=1; return }
    foreach ($name in 'AccountMySqlIntegrationTest','OrderMySqlIntegrationTest','OrderAcceptanceMySqlIntegrationTest',
                      'OrderFulfillmentMySqlIntegrationTest','OutboxMySqlIntegrationTest','MatchingIntegrationTest',
                      'DriverRedisIntegrationTest','OutboxRabbitIntegrationTest') {
        "<testsuite name='fake.$name' tests='1' failures='0' errors='0' skipped='0'/>" |
            Set-Content "$env:TEST_WORKSPACE/order-service/target/surefire-reports/TEST-$name.xml"
    }
    $global:LASTEXITCODE=0
}
try { & "$env:TEST_WORKSPACE/scripts/verify-day19.ps1" | Out-Null }
catch { $failure=$_.Exception.Message }
@{stopped=$global:testStopped;restored=$global:restored;live=$global:testLive;failure=$failure} |
    ConvertTo-Json -Compress
''')

    def test_day19_default_command_isolates_publisher_and_restores_before_http(self):
        result = self.day19()
        self.assertIsNone(result['failure'])
        self.assertEqual([42], result['stopped'])
        self.assertEqual(1, result['restored'])
        self.assertTrue(result['live'])

    def test_day19_restores_publisher_even_when_maven_fails(self):
        result = self.day19(maven_failure=True)
        self.assertEqual('Maven regression failed', result['failure'])
        self.assertEqual([42], result['stopped'])
        self.assertEqual(1, result['restored'])
        self.assertFalse(result['live'])

    def local_runtime(self, existing=False, target=True, stale=False, foreign=False):
        runtime = self.root / 'logs/local-runtime'
        runtime.mkdir(parents=True)
        (runtime / 'order-service.jar').write_text('running-copy')
        if target:
            build = self.root / 'order-service/target'
            build.mkdir(parents=True)
            (build / 'order-service-1.0.0-SNAPSHOT.jar').write_text('new-build' if stale else 'running-copy')
        (self.root / 'scripts/runtime-support.ps1').write_text('function Assert-LocalDependency {}\n')
        self.env.update(TEST_EXISTING=str(int(existing)), TEST_FOREIGN=str(int(foreign)),
                        JWT_SECRET='test-only-secret-with-more-than-thirty-two-bytes', SMARTDRIVE_INTERNAL_TOKEN='test-only')
        return self.run_script('start-local.ps1', r'''
$global:testStarted=@(); $global:testStopped=@(); $failure=$null
function Get-Command { param($Name) [pscustomobject]@{Source='X:\jdk\bin\java.exe'} }
function Get-NetTCPConnection {
    param($LocalPort,$State)
    if ($env:TEST_EXISTING -eq '1' -or $global:testStarted.Count -gt 0) { [pscustomobject]@{OwningProcess=42} }
}
function Get-CimInstance {
    param($ClassName,$Filter)
    $location = if ($env:TEST_FOREIGN -eq '1') {'X:\another-project'} else {$env:TEST_WORKSPACE}
    [pscustomobject]@{Name='java.exe';CommandLine="java -jar `"$location\logs\local-runtime\order-service.jar`""}
}
function Stop-Process { param($Id) $global:testStopped += $Id }
function Wait-Process { param($Id,$Timeout) }
function Start-Sleep { param($Seconds) }
function Invoke-RestMethod { param($Uri,$TimeoutSec) @{status='UP'} }
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    $global:testStarted += @{arguments=$ArgumentList}
    [pscustomobject]@{Id=999}
}
try { & "$env:TEST_WORKSPACE/scripts/start-local.ps1" -Service order-service | Out-Null }
catch { $failure=$_.Exception.Message }
$metadata = if (Test-Path "$env:TEST_WORKSPACE/logs/local-runtime/order-service.json") {
    Get-Content "$env:TEST_WORKSPACE/logs/local-runtime/order-service.json" -Raw | ConvertFrom-Json
} else { $null }
@{started=$global:testStarted;stopped=$global:testStopped;failure=$failure;metadata=$metadata} | ConvertTo-Json -Depth 6 -Compress
''')

    def test_local_runtime_recovers_stable_copy_after_clean_test_removed_target_jar(self):
        result = self.local_runtime(target=False)
        self.assertIsNone(result['failure'])
        self.assertEqual(1, len(result['started']))
        self.assertIn('--server.port=8083', result['started'][0]['arguments'])
        self.assertEqual(42, result['metadata']['pid'])  # Actual listener, not launcher PID 999.

    def test_local_runtime_restarts_stale_jar_instead_of_accepting_old_code(self):
        result = self.local_runtime(existing=True, stale=True)
        self.assertIsNone(result['failure'])
        self.assertEqual([42], result['stopped'])
        self.assertEqual(1, len(result['started']))
        self.assertEqual('new-build', (self.root / 'logs/local-runtime/order-service.jar').read_text())

    def test_local_runtime_reuses_only_matching_jar_and_records_its_source(self):
        result = self.local_runtime(existing=True)
        self.assertIsNone(result['failure'])
        self.assertEqual([], result['started'])
        self.assertEqual([], result['stopped'])
        self.assertEqual(str(self.root / 'logs/local-runtime/order-service.jar'), result['metadata']['jarPath'])

    def test_local_runtime_never_stops_a_different_projects_service(self):
        result = self.local_runtime(existing=True, foreign=True)
        self.assertIn('Unexpected process', result['failure'])
        self.assertEqual([], result['stopped'])


if __name__ == '__main__':
    unittest.main()
