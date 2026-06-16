param(
    [Parameter(Mandatory = $true)]
    [int]$ProcessId,

    [Parameter(Mandatory = $true)]
    [string]$OutLog,

    [Parameter(Mandatory = $true)]
    [string]$ErrLog,

    [int]$StartupTimeout = 120,

    [switch]$NoTail
)

$successPatterns = @(
    'Started .* in .* seconds',
    'Started .*Application',
    'Tomcat started on port',
    'Netty started on port'
)

$failurePatterns = @(
    'APPLICATION FAILED TO START',
    'Error starting ApplicationContext',
    'Web server failed to start',
    'APPLICATION FAILED'
)

$logFiles = @($OutLog)
if ($ErrLog -and $ErrLog -ne $OutLog) {
    $logFiles += $ErrLog
}

foreach ($file in $logFiles) {
    if (-not (Test-Path -LiteralPath $file)) {
        New-Item -ItemType File -Path $file -Force | Out-Null
    }
}

function Test-LogPatterns {
    param(
        [string[]]$Paths,
        [string[]]$Patterns
    )

    foreach ($path in $Paths) {
        foreach ($pattern in $Patterns) {
            if (Select-String -Path $path -Pattern $pattern -Quiet -ErrorAction SilentlyContinue) {
                return $true
            }
        }
    }

    return $false
}

$tailJob = $null

try {
    Write-Host "Waiting up to $StartupTimeout seconds for startup result..."

    if (-not $NoTail) {
        Write-Host "Following logs:"
        foreach ($file in $logFiles) {
            Write-Host "  $file"
        }
        Write-Host "Press Ctrl+C to stop log following without stopping the service."

        $tailJob = Start-Job -ScriptBlock {
            param($files)
            Get-Content -Path $files -Wait -Tail 0
        } -ArgumentList (, $logFiles)
    }

    $successDetected = $false
    $deadline = (Get-Date).AddSeconds($StartupTimeout)

    while ((Get-Date) -lt $deadline) {
        if ($tailJob) {
            Receive-Job -Job $tailJob -Keep | ForEach-Object { Write-Host $_ }
        }

        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            if ($tailJob) {
                Receive-Job -Job $tailJob -Keep | ForEach-Object { Write-Host $_ }
            }
            Write-Host "Startup failed. Process exited. Check logs above." -ForegroundColor Red
            exit 1
        }

        if (Test-LogPatterns -Paths $logFiles -Patterns $failurePatterns) {
            Write-Host "Startup failed. Failure markers detected in logs." -ForegroundColor Red
            exit 1
        }

        if (Test-LogPatterns -Paths $logFiles -Patterns $successPatterns) {
            $successDetected = $true
            break
        }

        Start-Sleep -Seconds 1
    }

    if ($successDetected) {
        Write-Host "Startup succeeded. Success markers detected in logs." -ForegroundColor Green
    } elseif (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        Write-Host "Startup succeeded. Process is still running after $StartupTimeout seconds." -ForegroundColor Green
    } else {
        Write-Host "Startup failed. Process exited during startup detection." -ForegroundColor Red
        exit 1
    }

    if ($NoTail) {
        exit 0
    }

    while ($true) {
        Receive-Job -Job $tailJob -Keep | ForEach-Object { Write-Host $_ }
        Start-Sleep -Milliseconds 500
    }
} finally {
    if ($tailJob) {
        Stop-Job -Job $tailJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job -Job $tailJob -Force -ErrorAction SilentlyContinue | Out-Null
    }
}
