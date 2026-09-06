# ACE Local Builder Scripts

Quick local testing of your ACE BAR files without Jenkins. Choose the script for your OS.

## 📋 Quick Start

### Windows (PowerShell) - Recommended

```powershell
# Basic usage
.\build-ace-local.ps1 -BarFilePath "C:\Users\YourUser\Documents\myapp.bar"

# With environment
.\build-ace-local.ps1 -BarFilePath "D:\BARs\payment.bar" -Environment test

# Build without running container
.\build-ace-local.ps1 -BarFilePath "C:\path\to\app.bar" -RunContainer $false
```

### Linux / macOS (Bash)

```bash
# Basic usage
./build-ace-local.sh -b /home/user/myapp.bar

# With environment
./build-ace-local.sh -b /home/user/payment.bar -e test

# Build without running container
./build-ace-local.sh -b /home/user/app.bar -r false
```

---

## 🔧 Parameters

### PowerShell (`build-ace-local.ps1`)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `-BarFilePath` | string | **Required** | Full path to your BAR file |
| `-Environment` | string | `dev` | Environment: `dev`, `test`, `staging` |
| `-RunContainer` | bool | `$true` | Start container after build |
| `-ContainerName` | string | Auto-generated | Custom container name |
| `-AppName` | string | From filename | App name for labels |

### Bash (`build-ace-local.sh`)

| Option | Argument | Default | Description |
|--------|----------|---------|-------------|
| `-b` | path | **Required** | Full path to your BAR file |
| `-e` | env | `dev` | Environment: `dev`, `test`, `staging` |
| `-r` | true/false | `true` | Start container after build |
| `-a` | name | From filename | App name for labels |
| `-c` | name | Auto-generated | Custom container name |
| `-h` | - | - | Show help |

---

## 📍 Environments & Port Ranges

Each environment allocates ports from a specific range to avoid conflicts:

| Environment | Flow Ports | Admin Ports |
|------------|-----------|-----------|
| **dev** | 7800-7900 | 7600-7700 |
| **test** | 8000-8100 | 8100-8200 |
| **staging** | 8200-8300 | 8300-8400 |

**Example**: Running two builds in dev environment:
- Build 1: Flow 7800, Admin 7600
- Build 2: Flow 7802, Admin 7602

---

## 🚀 Examples

### Example 1: Simple Local Build & Run

**PowerShell:**
```powershell
.\build-ace-local.ps1 -BarFilePath "C:\Users\john\Documents\invoice.bar"
```

**Output:**
```
[✓] BAR file found: invoice.bar
[*] Extracted app name: invoice
[✓] Copied BAR to workspace: invoice.bar
[*] Building Docker image...
[✓] Docker image built successfully: ace-invoice-local:latest
[✓] Environment: dev
[✓] Flow port: localhost:7800 -> 7800
[✓] Admin port: localhost:7600 -> 7600
[✓] Container started successfully!

✓ BUILD COMPLETE
Container: ace-invoice-180530
Image: ace-invoice-local:latest
Flow endpoint: http://localhost:7800
Admin API: http://localhost:7600/v1/integrationServers
```

### Example 2: Multiple Containers (Different Environments)

**Build in dev:**
```powershell
.\build-ace-local.ps1 -BarFilePath "C:\path\to\app.bar" -Environment dev
# Container on ports 7800/7600
```

**Build in test simultaneously:**
```powershell
# In another PowerShell window
.\build-ace-local.ps1 -BarFilePath "C:\path\to\app.bar" -Environment test
# Container on ports 8000/8100
```

Both containers run independently without port conflicts!

### Example 3: Build Only (No Auto-Run)

```powershell
.\build-ace-local.ps1 -BarFilePath "D:\BARs\service.bar" -RunContainer $false
```

Then start manually:
```powershell
docker run -d --name ace-service -p 7800:7800 -p 7600:7600 -e LICENSE=accept ace-service-local:latest
```

### Example 4: Custom Container Name

```bash
./build-ace-local.sh -b /home/user/payment.bar -c my-payment-server-v1
```

---

## 🧪 Testing Your Container

Once the container is running, test it:

### Check Logs
```bash
docker logs -f ace-invoice-180530
```

### Test Flow Endpoint
```bash
# If your flow has an HTTP input:
curl http://localhost:7800/your-flow-path

# Example with data:
curl -X POST http://localhost:7800/api/process \
  -H "Content-Type: application/json" \
  -d '{"data": "test"}'
```

### Check Admin API
```bash
# Get server info
curl http://localhost:7600/v1/integrationServers

# List deployed flows
curl http://localhost:7600/v1/integrationServers/default/flows
```

### Container Management
```bash
# View running containers
docker ps --filter "label=local_build=true"

# Stop container
docker stop ace-invoice-180530

# Remove container
docker rm ace-invoice-180530

# View all logs
docker logs ace-invoice-180530

# Get container stats
docker stats ace-invoice-180530
```

---

## 🐛 Troubleshooting

### Container fails to start
```bash
# Check logs
docker logs ace-invoice-180530

# Common issues:
# - Port already in use: Use different environment or stop conflicting container
# - BAR file not found: Verify path to BAR file
# - Docker not running: Start Docker Desktop
```

### Port already in use
```bash
# Find what's using the port (example: 7800)
# Windows
netstat -ano | findstr :7800

# Linux/Mac
lsof -i :7800

# Use different environment
.\build-ace-local.ps1 -BarFilePath "C:\path\to\app.bar" -Environment test
```

### "BAR file not found"
- Use absolute path, not relative
- On Windows, use full path like `C:\Users\john\Documents\file.bar`
- On Linux/Mac, use full path like `/home/john/Documents/file.bar`

### Docker build fails
- Ensure `Dockerfile.ace-generic` exists in the same directory as the script
- Verify you have write permissions to create `generated-bars/` directory
- Check Docker is running: `docker ps`

---

## 📝 Script Features

Both scripts automatically:

✅ **Copy** your BAR to a workspace  
✅ **Build** a Docker image using `Dockerfile.ace-generic`  
✅ **Allocate** unique ports from the environment range  
✅ **Label** containers for easy management  
✅ **Verify** container starts successfully  
✅ **Display** useful endpoint information  
✅ **Clean up** old containers with the same name  

---

## 🔄 Workflow: Build → Test → Deploy

### Local Testing (This Script)
```
Your BAR → Docker Image → Running Container → Test → Verify
```

### Integration with Jenkins/acev13Pipeline
Once tested locally, use the same BAR in Jenkins:

1. Push to GitHub repository
2. Jenkins triggers `acev13Pipeline`
3. Pipeline builds BAR → Docker Image → Deploys to environment
4. Uses same `port-registry.json` for consistent port allocation

---

## 📂 File Structure

```
cicd-shared-library/
├── build-ace-local.ps1          ← Windows PowerShell script
├── build-ace-local.sh           ← Linux/Mac Bash script
├── Dockerfile.ace-generic        ← Required for building
├── port-registry.json            ← Port allocation registry
├── generated-bars/               ← Created automatically
│   └── (your BARs copied here)
└── BUILD_ACE_LOCAL_README.md    ← This file
```

---

## ❓ FAQ

**Q: Do I need Jenkins to use these scripts?**  
A: No, these scripts work standalone on your local machine.

**Q: Can I run multiple containers simultaneously?**  
A: Yes! Use different environments (`dev`, `test`, `staging`) to avoid port conflicts.

**Q: Where does it copy my BAR file?**  
A: To the `generated-bars/` folder in the same directory as the script.

**Q: Can I use custom port ranges?**  
A: Currently, ports are pre-defined per environment. Edit the script to customize.

**Q: What if I need to test with MQ or Redis?**  
A: You'll need to create those containers separately or use Docker Compose. See examples in `PORT_REGISTRY_GUIDE.md`.

**Q: Is the BAR file modified?**  
A: No, only a copy is used. Your original BAR stays untouched.

---

## 🎯 Next Steps

1. **Test locally** using these scripts
2. **Verify functionality** with curl/Postman
3. **Push to repository** when ready
4. **Deploy via Jenkins** using `acev13Pipeline`
5. **Promote across environments** (dev → test → staging)

---

## 📞 Support

- Check Docker is installed: `docker --version`
- Check Dockerfile exists: `ls Dockerfile.ace-generic` (or `dir Dockerfile.ace-generic` on Windows)
- View script help:
  - PowerShell: `Get-Help .\build-ace-local.ps1`
  - Bash: `./build-ace-local.sh -h`
