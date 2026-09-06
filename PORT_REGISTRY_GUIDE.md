# ACE Port Registry - Environment-Specific Port Management

This system manages unique port allocation for independent ACE integration servers across different environments.

## Overview

Instead of calculating ports dynamically from BUILD_NUMBER, the port registry provides:
- **Environment-specific port ranges** (dev, test, staging, prod)
- **Centralized allocation tracking** via `port-registry.json`
- **Prevention of port conflicts** across concurrent deployments
- **Cleanup capabilities** for old allocations

## Environments

Each environment has its own dedicated port range:

| Environment | Flow Port Range | Admin Port Range |
|------------|-----------------|------------------|
| **dev**    | 7800-7900       | 7600-7700        |
| **test**   | 8000-8100       | 8100-8200        |
| **staging**| 8200-8300       | 8300-8400        |

## How It Works

1. **Allocation**: When a pipeline runs, it calls `portRegistry.allocatePort()` to get unique ports
2. **Registry**: The allocation is recorded in `port-registry.json` with timestamps and metadata
3. **Assignment**: The container gets unique host ports from the allocated range
4. **Cleanup**: Old allocations can be removed after retention period (default: 7 days)

## Usage in Pipelines

### Option 1: Using `acev13Pipeline.groovy` (Shared Library)

```groovy
@Library('cicd-shared-library') _

acev13Pipeline {
    appName = 'my-integration-app'
    aceProjectName = 'MyProject'
    environment = 'dev'          // ← Specify environment (dev|test|staging)
    // Ports are automatically allocated from registry
}
```

### Option 2: Using `Jenkinsfile` (Declarative)

The Jenkinsfile also uses the port registry. Just add `environment` parameter to your Jenkins job configuration.

## Management Commands

### View Current Allocations

```bash
# All allocations
cat port-registry.json | jq '.allocations'

# Only dev environment
cat port-registry.json | jq '.allocations | map(select(.environment=="dev"))'
```

### Manual Port Allocation (Groovy)

```groovy
def portRegistry = load 'vars/portRegistry.groovy'

// Allocate ports
def ports = portRegistry.allocatePort('myapp', 'test', '42')
echo "Allocated ports: Flow=${ports.hostFlowPort}, Admin=${ports.hostAdminPort}"

// List all allocations
portRegistry.listAllocations()

// List only dev environment
portRegistry.listAllocations('dev')

// Release ports
portRegistry.deallocatePort(ports.key)

// Cleanup old allocations (older than 7 days)
portRegistry.cleanupOldAllocations(7)
```

### Docker Commands

```bash
# Find which container is using which ports
docker ps --filter "label=port_key" --format "table {{.Names}}\t{{.Ports}}\t{{.Labels}}"

# Query by environment
docker ps --filter "label=environment=dev" --format "table {{.Names}}\t{{.Ports}}"

# Query by job name
docker ps --filter "label=jenkins_job=my-job" --format "table {{.Names}}\t{{.Ports}}"
```

## Port Allocation Strategy

The system allocates ports sequentially:

```
Dev Environment (starting at 7800):
Build 1: Flow 7800, Admin 7600
Build 2: Flow 7802, Admin 7602
Build 3: Flow 7804, Admin 7604
...
Max 50 containers per environment

Test Environment (starting at 8000):
Build 1: Flow 8000, Admin 8100
Build 2: Flow 8002, Admin 8102
...
```

## Adding New Environments

Edit `port-registry.json` to add new environments:

```json
{
  "environments": {
    "production": {
      "baseFlowPort": 9000,
      "baseAdminPort": 9100,
      "portRange": "9000-9100"
    }
  }
}
```

Then use in pipeline:
```groovy
acev13Pipeline {
    appName = 'critical-app'
    environment = 'production'
}
```

## Benefits

✅ **No hardcoded ports** - Environments manage their own ranges  
✅ **Scalable** - Support up to 50 concurrent containers per environment  
✅ **Trackable** - Know which app/build/job uses which ports  
✅ **Self-cleaning** - Old allocations can be automatically removed  
✅ **Audit trail** - Timestamps and metadata for each allocation  

## Troubleshooting

### "Port registry full for environment 'dev'"
- Too many active containers. Cleanup old allocations or increase `maxContainers` in `port-registry.json`

### "Environment 'prod' not found"
- Add the environment config to `port-registry.json` first

### Port conflicts after cleanup
- The registry only tracks allocations; it doesn't stop containers. Manually stop old containers:
  ```bash
  docker rm -f $(docker ps -a --filter "label=environment=dev" --format "{{.Names}}")
  ```

## Related Files

- **Port Registry**: `port-registry.json` - Central allocation store
- **Port Manager**: `vars/portRegistry.groovy` - Allocation logic
- **Jenkinsfile**: Uses registry for standalone pipeline jobs
- **acev13Pipeline.groovy**: Uses registry for shared library calls
