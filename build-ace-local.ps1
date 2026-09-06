#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Local ACE BAR Container Builder - Build and run ACE integration containers from local BAR files
.DESCRIPTION
    This script builds a Docker container from your local BAR file using the Dockerfile.ace-generic
    Supports port allocation from registry and optional container execution
.PARAMETER BarFilePath
    Full path to your BAR file (required)
.PARAMETER Environment
    Environment for port allocation: dev, test, staging (default: dev)
.PARAMETER RunContainer
    If $true, starts the container after building (default: $true)
.PARAMETER ContainerName
    Custom container name (default: ace-{appname}-{timestamp})
.PARAMETER AppName
    App name for labeling (extracted from BAR filename if not provided)
.EXAMPLE
    .\build-ace-local.ps1 -BarFilePath "C:\Users\YourUser\Documents\myapp.bar"
.EXAMPLE
    .\build-ace-local.ps1 -BarFilePath "D:\BARs\payment-service.bar" -Environment test -RunContainer $true
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$BarFilePath,
    
    [Parameter(Mandatory = $false)]
    [ValidateSet('dev', 'test', 'staging')]
    [string]$Environment = 'dev',
    
    [Parameter(Mandatory = $false)]
    [bool]$RunContainer = $true,
    
    [Parameter(Mandatory = $false)]
    [string]$ContainerName,
    
    [Parameter(Mandatory = $false)]
    [string]$AppName
)

# Color output
function Write-Success { Write-Host "[OK] $args" -ForegroundColor Green }
function Write-Info { Write-Host "[*] $args" -ForegroundColor Cyan }
function Write-Warning { Write-Host "[!] $args" -ForegroundColor Yellow }
function Write-ErrorMsg { Write-Host "[ERROR] $args" -ForegroundColor Red }

# ===========================
# 1. VALIDATION
# ===========================
Write-Info "Starting ACE Local Builder..."
Write-Info "================================================"

# Check if BAR file exists
if (-not (Test-Path $BarFilePath)) {
    Write-ErrorMsg "BAR file not found: $BarFilePath"
    exit 1
}

# Validate BAR file
if (-not $BarFilePath.EndsWith('.bar')) {
    Write-Warning "File doesn't end with .bar extension. Proceeding anyway..."
}

$BarFileName = Split-Path -Leaf $BarFilePath
$BarDirectory = Split-Path -Parent $BarFilePath

Write-Success "BAR file found: $BarFileName"
Write-Info "Location: $BarDirectory"

# Extract app name from BAR filename if not provided
if (-not $AppName) {
    $AppName = $BarFileName -replace '\.bar$', '' -replace '[^a-zA-Z0-9_-]', '-'
    $AppName = $AppName.ToLower()
    Write-Info "Extracted app name: $AppName"
}

# Generate container name
if (-not $ContainerName) {
    $timestamp = Get-Date -Format "HHmmss"
    $ContainerName = "ace-$AppName-$timestamp"
}

Write-Info "Container name: $ContainerName"
Write-Info "Environment: $Environment"

# ===========================
# 2. COPY BAR TO WORKING DIR
# ===========================
Write-Info "================================================"
Write-Info "Preparing workspace..."

$scriptDir = Split-Path -Parent $PSCommandPath
$generatedBarsDir = Join-Path $scriptDir "generated-bars"

# Create directory if it doesn't exist
if (-not (Test-Path $generatedBarsDir)) {
    New-Item -ItemType Directory -Path $generatedBarsDir | Out-Null
    Write-Success "Created directory: $generatedBarsDir"
}

# Copy BAR file
$targetBarPath = Join-Path $generatedBarsDir $BarFileName
Copy-Item $BarFilePath $targetBarPath -Force
Write-Success "Copied BAR to workspace: $BarFileName"

# ===========================
# 3. BUILD DOCKER IMAGE
# ===========================
Write-Info "================================================"
Write-Info "Building Docker image..."

$imageName = "ace-$AppName-local:latest"

# Check if Dockerfile.ace-generic exists
$dockerfile = Join-Path $scriptDir "Dockerfile.ace-generic"
if (-not (Test-Path $dockerfile)) {
    Write-ErrorMsg "Dockerfile.ace-generic not found in: $scriptDir"
    exit 1
}

Write-Info "Using Dockerfile: Dockerfile.ace-generic"
Write-Info "Image name: $imageName"

# Build Docker image
$buildCmd = "docker build -f Dockerfile.ace-generic --build-arg BAR_FILE=$BarFileName -t $imageName `"$scriptDir`""

Write-Info "Running: $buildCmd"
Invoke-Expression $buildCmd

if ($LASTEXITCODE -ne 0) {
    Write-ErrorMsg "Docker build failed!"
    exit 1
}

Write-Success "Docker image built successfully: $imageName"

# ===========================
# 4. ALLOCATE PORTS
# ===========================
Write-Info "================================================"
Write-Info "Port allocation..."

# Environment-specific port ranges
$portConfig = @{
    'dev'     = @{ baseFlow = 7800; baseAdmin = 7600; }
    'test'    = @{ baseFlow = 8000; baseAdmin = 8100; }
    'staging' = @{ baseFlow = 8200; baseAdmin = 8300; }
}

$baseFlowPort = $portConfig[$Environment].baseFlow
$baseAdminPort = $portConfig[$Environment].baseAdmin

# Simple allocation: use BUILD_NUMBER equivalent (current second + random)
$buildNum = (Get-Date).Second + (Get-Random -Maximum 50)
$hostFlowPort = $baseFlowPort + ($buildNum % 50) * 2
$hostAdminPort = $baseAdminPort + ($buildNum % 50) * 2

Write-Success "Environment: $Environment"
Write-Success "Flow port: localhost:$hostFlowPort -> 7800"
Write-Success "Admin port: localhost:$hostAdminPort -> 7600"

# ===========================
# 5. RUN CONTAINER (Optional)
# ===========================
if ($RunContainer) {
    Write-Info "================================================"
    Write-Info "Starting container..."
    
    # Remove existing container with same name
    $existingContainer = docker ps -a --filter "name=$ContainerName" --format "{{.Names}}" 2>$null
    if ($existingContainer -eq $ContainerName) {
        Write-Warning "Stopping existing container: $ContainerName"
        docker rm -f $ContainerName 2>$null | Out-Null
    }
    
    # Run container
    $runCmd = "docker run -d --name $ContainerName -p ${hostFlowPort}:7800 -p ${hostAdminPort}:7600 -e LICENSE=accept -l app=$AppName -l environment=$Environment -l local_build=true $imageName"
    
    Write-Info "Running: $runCmd"
    Invoke-Expression $runCmd
    
    if ($LASTEXITCODE -ne 0) {
        Write-ErrorMsg "Failed to start container!"
        exit 1
    }
    
    # Wait for container to start
    Start-Sleep -Seconds 2
    
    # Verify container is running
    $running = docker ps --filter "name=$ContainerName" --format "{{.Names}}" 2>$null
    
    if ($running -eq $ContainerName) {
        Write-Success "Container started successfully!"
        Write-Info "================================================"
        Write-Info "[OK] BUILD COMPLETE"
        Write-Info "Container: $ContainerName"
        Write-Info "Image: $imageName"
        Write-Info "Flow endpoint: http://localhost:$hostFlowPort"
        Write-Info "Admin API: http://localhost:$hostAdminPort/v1/integrationServers"
        Write-Info "Logs: docker logs -f $ContainerName"
        Write-Info "Stop: docker stop $ContainerName"
        Write-Info "Remove: docker rm $ContainerName"
        Write-Info "================================================"
        
        # Show initial logs
        Write-Info "Container logs:"
        Start-Sleep -Seconds 1
        docker logs $ContainerName
    } else {
        Write-ErrorMsg "Container failed to start. Check logs:"
        docker logs $ContainerName
        exit 1
    }
} else {
    Write-Info "================================================"
    Write-Info "[OK] BUILD COMPLETE (Container not started)"
    Write-Info "Image: $imageName"
    Write-Info ""
    Write-Info "To start the container manually, run:"
    Write-Info "docker run -d --name $ContainerName -p $hostFlowPort`:7800 -p $hostAdminPort`:7600 -e LICENSE=accept $imageName"
    Write-Info "================================================"
}
