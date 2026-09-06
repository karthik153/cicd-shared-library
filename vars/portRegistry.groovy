#!/usr/bin/env groovy
/**
 * Port Registry Manager for ACE Integration Servers
 * Manages allocation and deallocation of unique ports for each container
 * Supports environment-specific port ranges (dev, test, staging, prod)
 */

def allocatePort(String appName, String environment = 'dev', String buildNumber = '1') {
    """
    Allocates unique ports for a container from the registry.
    Returns: [hostFlowPort, hostAdminPort, containerFlowPort, containerAdminPort]
    """
    def registry = readJSON file: 'port-registry.json'
    def env_config = registry.environments[environment]
    
    if (!env_config) {
        error "Environment '${environment}' not found in port registry. Available: ${registry.environments.keySet()}"
    }
    
    def baseFlowPort = env_config.baseFlowPort as Integer
    def baseAdminPort = env_config.baseAdminPort as Integer
    def spacing = registry.portSpacing as Integer
    def containerKey = "${appName}-${buildNumber}-${environment}"
    
    // Find next available slot
    def allocations = registry.allocations
    def usedIndices = allocations.collect { k, v -> 
        if (v.jobName == env.JOB_NAME) {
            return (v.hostFlowPort - baseFlowPort) / spacing
        }
        return -1
    }.findAll { it >= 0 }
    
    def nextIndex = 0
    while (usedIndices.contains(nextIndex)) { nextIndex++ }
    
    if (nextIndex >= registry.maxContainers) {
        error "Port registry full for environment '${environment}'. Max ${registry.maxContainers} containers allowed."
    }
    
    def hostFlowPort = baseFlowPort + (nextIndex * spacing)
    def hostAdminPort = baseAdminPort + (nextIndex * spacing)
    
    // Record allocation
    def timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
    allocations[containerKey] = [
        hostFlowPort: hostFlowPort,
        hostAdminPort: hostAdminPort,
        containerFlowPort: 7800,
        containerAdminPort: 7600,
        allocatedAt: timestamp,
        jobName: env.JOB_NAME,
        buildNumber: buildNumber,
        environment: environment
    ]
    
    writeJSON file: 'port-registry.json', json: registry, pretty: 2
    
    return [
        hostFlowPort: hostFlowPort,
        hostAdminPort: hostAdminPort,
        containerFlowPort: 7800,
        containerAdminPort: 7600,
        key: containerKey
    ]
}

def deallocatePort(String containerKey) {
    """
    Releases ports back to the registry for reuse.
    """
    def registry = readJSON file: 'port-registry.json'
    
    if (registry.allocations.containsKey(containerKey)) {
        def allocation = registry.allocations[containerKey]
        registry.allocations.remove(containerKey)
        writeJSON file: 'port-registry.json', json: registry, pretty: 2
        echo "✓ Released ports for ${containerKey} (Flow: ${allocation.hostFlowPort}, Admin: ${allocation.hostAdminPort})"
    } else {
        echo "⚠ Container key '${containerKey}' not found in registry"
    }
}

def listAllocations(String environment = null) {
    """
    Lists all current port allocations, optionally filtered by environment.
    """
    def registry = readJSON file: 'port-registry.json'
    def allocations = registry.allocations
    
    if (environment) {
        allocations = allocations.findAll { k, v -> v.environment == environment }
    }
    
    if (allocations.isEmpty()) {
        echo "No port allocations found"
        return
    }
    
    echo "╔═══════════════════════════════════════════════════════════════╗"
    echo "║ ACE Integration Server Port Allocations                       ║"
    echo "╠═══════════════════════════════════════════════════════════════╣"
    allocations.each { key, allocation ->
        echo "║ Container: ${key.padRight(48)}"
        echo "║   Env: ${allocation.environment} | Flow: ${allocation.hostFlowPort} | Admin: ${allocation.hostAdminPort}"
        echo "║   Job: ${allocation.jobName} | Build: ${allocation.buildNumber}"
        echo "║   ─────────────────────────────────────────────────────────"
    }
    echo "╚═══════════════════════════════════════════════════════════════╝"
}

def getEnvironmentConfig(String environment) {
    """
    Returns the port configuration for a specific environment.
    """
    def registry = readJSON file: 'port-registry.json'
    return registry.environments[environment]
}

def cleanupOldAllocations(int retentionDays = 7) {
    """
    Removes port allocations older than specified days.
    Useful for cleanup after container deletions.
    """
    def registry = readJSON file: 'port-registry.json'
    def cutoffDate = new Date() - retentionDays
    def removed = 0
    
    registry.allocations.findAll { k, v ->
        Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", v.allocatedAt) < cutoffDate
    }.each { k, v ->
        registry.allocations.remove(k)
        echo "✓ Cleaned up: ${k} (allocated on ${v.allocatedAt})"
        removed++
    }
    
    if (removed > 0) {
        writeJSON file: 'port-registry.json', json: registry, pretty: 2
    }
    echo "Cleanup complete: ${removed} allocation(s) removed"
}

return this
