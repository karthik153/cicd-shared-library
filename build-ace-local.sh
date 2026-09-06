#!/usr/bin/env bash
#
# ACE Local BAR Container Builder - Bash version
# Build and run ACE integration containers from local BAR files
#
# Usage: ./build-ace-local.sh -b /path/to/file.bar [-e dev|test|staging] [-r true|false]
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Helper functions
success() { echo -e "${GREEN}[✓]${NC} $1"; }
info() { echo -e "${CYAN}[*]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[✗]${NC} $1"; }

# Default values
ENVIRONMENT="dev"
RUN_CONTAINER=true
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Parse arguments
while getopts "b:e:r:a:c:h" opt; do
    case $opt in
        b) BAR_FILE_PATH="$OPTARG" ;;
        e) ENVIRONMENT="$OPTARG" ;;
        r) RUN_CONTAINER="$OPTARG" ;;
        a) APP_NAME="$OPTARG" ;;
        c) CONTAINER_NAME="$OPTARG" ;;
        h) 
            echo "ACE Local BAR Container Builder"
            echo ""
            echo "Usage: $0 -b <bar_file_path> [OPTIONS]"
            echo ""
            echo "Required:"
            echo "  -b <path>  Path to BAR file (required)"
            echo ""
            echo "Options:"
            echo "  -e <env>   Environment: dev, test, staging (default: dev)"
            echo "  -r true|false  Run container after build (default: true)"
            echo "  -a <name>  App name for labels"
            echo "  -c <name>  Custom container name"
            echo "  -h         Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0 -b /home/user/myapp.bar"
            echo "  $0 -b /home/user/payment.bar -e test -r true"
            exit 0
            ;;
        \?) 
            error "Invalid option: -$OPTARG"
            exit 1
            ;;
    esac
done

# ===========================
# 1. VALIDATION
# ===========================
info "Starting ACE Local Builder..."
info "================================================"

if [ -z "$BAR_FILE_PATH" ]; then
    error "BAR file path is required. Use -b option."
    exit 1
fi

if [ ! -f "$BAR_FILE_PATH" ]; then
    error "BAR file not found: $BAR_FILE_PATH"
    exit 1
fi

BAR_FILE_NAME=$(basename "$BAR_FILE_PATH")
BAR_DIRECTORY=$(dirname "$BAR_FILE_PATH")

success "BAR file found: $BAR_FILE_NAME"
info "Location: $BAR_DIRECTORY"

# Extract app name if not provided
if [ -z "$APP_NAME" ]; then
    APP_NAME="${BAR_FILE_NAME%.bar}"
    APP_NAME=$(echo "$APP_NAME" | sed 's/[^a-zA-Z0-9_-]/-/g' | tr '[:upper:]' '[:lower:]')
    info "Extracted app name: $APP_NAME"
fi

# Generate container name if not provided
if [ -z "$CONTAINER_NAME" ]; then
    TIMESTAMP=$(date +%H%M%S)
    CONTAINER_NAME="ace-$APP_NAME-$TIMESTAMP"
fi

info "Container name: $CONTAINER_NAME"
info "Environment: $ENVIRONMENT"

# ===========================
# 2. COPY BAR TO WORKING DIR
# ===========================
info "================================================"
info "Preparing workspace..."

GENERATED_BARS_DIR="$SCRIPT_DIR/generated-bars"

# Create directory if needed
mkdir -p "$GENERATED_BARS_DIR"
success "Using workspace: $GENERATED_BARS_DIR"

# Copy BAR file
TARGET_BAR_PATH="$GENERATED_BARS_DIR/$BAR_FILE_NAME"
cp "$BAR_FILE_PATH" "$TARGET_BAR_PATH"
success "Copied BAR to workspace: $BAR_FILE_NAME"

# ===========================
# 3. BUILD DOCKER IMAGE
# ===========================
info "================================================"
info "Building Docker image..."

IMAGE_NAME="ace-$APP_NAME-local:latest"

# Check Dockerfile
DOCKERFILE="$SCRIPT_DIR/Dockerfile.ace-generic"
if [ ! -f "$DOCKERFILE" ]; then
    error "Dockerfile.ace-generic not found in: $SCRIPT_DIR"
    exit 1
fi

info "Using Dockerfile: Dockerfile.ace-generic"
info "Image name: $IMAGE_NAME"

# Build Docker image
info "Running: docker build -f Dockerfile.ace-generic --build-arg BAR_FILE=$BAR_FILE_NAME -t $IMAGE_NAME $SCRIPT_DIR"
docker build -f "$DOCKERFILE" \
    --build-arg "BAR_FILE=$BAR_FILE_NAME" \
    -t "$IMAGE_NAME" \
    "$SCRIPT_DIR"

if [ $? -ne 0 ]; then
    error "Docker build failed!"
    exit 1
fi

success "Docker image built successfully: $IMAGE_NAME"

# ===========================
# 4. ALLOCATE PORTS
# ===========================
info "================================================"
info "Port allocation..."

# Environment-specific port ranges
case "$ENVIRONMENT" in
    dev)
        BASE_FLOW_PORT=7800
        BASE_ADMIN_PORT=7600
        ;;
    test)
        BASE_FLOW_PORT=8000
        BASE_ADMIN_PORT=8100
        ;;
    staging)
        BASE_FLOW_PORT=8200
        BASE_ADMIN_PORT=8300
        ;;
    *)
        error "Unknown environment: $ENVIRONMENT"
        exit 1
        ;;
esac

# Simple allocation using current second
BUILD_NUM=$(($(date +%s) % 50))
HOST_FLOW_PORT=$((BASE_FLOW_PORT + BUILD_NUM * 2))
HOST_ADMIN_PORT=$((BASE_ADMIN_PORT + BUILD_NUM * 2))

success "Environment: $ENVIRONMENT"
success "Flow port: localhost:$HOST_FLOW_PORT -> 7800"
success "Admin port: localhost:$HOST_ADMIN_PORT -> 7600"

# ===========================
# 5. RUN CONTAINER (Optional)
# ===========================
if [ "$RUN_CONTAINER" = "true" ]; then
    info "================================================"
    info "Starting container..."
    
    # Remove existing container
    EXISTING=$(docker ps -a --filter "name=$CONTAINER_NAME" --format "{{.Names}}" 2>/dev/null || echo "")
    if [ "$EXISTING" = "$CONTAINER_NAME" ]; then
        warn "Stopping existing container: $CONTAINER_NAME"
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    
    # Run container
    info "Running: docker run -d --name $CONTAINER_NAME -p $HOST_FLOW_PORT:7800 -p $HOST_ADMIN_PORT:7600 $IMAGE_NAME"
    docker run -d \
        --name "$CONTAINER_NAME" \
        -p "$HOST_FLOW_PORT:7800" \
        -p "$HOST_ADMIN_PORT:7600" \
        -e "LICENSE=accept" \
        -l "app=$APP_NAME" \
        -l "environment=$ENVIRONMENT" \
        -l "local_build=true" \
        "$IMAGE_NAME"
    
    if [ $? -ne 0 ]; then
        error "Failed to start container!"
        exit 1
    fi
    
    # Wait for container to start
    sleep 2
    
    # Verify container is running
    RUNNING=$(docker ps --filter "name=$CONTAINER_NAME" --format "{{.Names}}" 2>/dev/null || echo "")
    
    if [ "$RUNNING" = "$CONTAINER_NAME" ]; then
        success "Container started successfully!"
        info "================================================"
        info "✓ BUILD COMPLETE"
        info "Container: $CONTAINER_NAME"
        info "Image: $IMAGE_NAME"
        info "Flow endpoint: http://localhost:$HOST_FLOW_PORT"
        info "Admin API: http://localhost:$HOST_ADMIN_PORT/v1/integrationServers"
        info "Logs: docker logs -f $CONTAINER_NAME"
        info "Stop: docker stop $CONTAINER_NAME"
        info "Remove: docker rm $CONTAINER_NAME"
        info "================================================"
        
        # Show initial logs
        info "Container logs (last 10 lines):"
        sleep 1
        docker logs --tail 10 "$CONTAINER_NAME"
    else
        error "Container failed to start. Check logs:"
        docker logs "$CONTAINER_NAME" || true
        exit 1
    fi
else
    info "================================================"
    info "✓ BUILD COMPLETE (Container not started)"
    info "Image: $IMAGE_NAME"
    info ""
    info "To start the container manually, run:"
    info "docker run -d --name $CONTAINER_NAME -p $HOST_FLOW_PORT:7800 -p $HOST_ADMIN_PORT:7600 -e LICENSE=accept $IMAGE_NAME"
    info "================================================"
fi
