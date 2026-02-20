#!/bin/bash

# RDM Setup Script
# Sets up the Remote Device Manager system

set -e

echo "🚀 Remote Device Manager Setup"
echo "================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check prerequisites
echo "📋 Checking prerequisites..."

# Check Rust
if ! command -v cargo &> /dev/null; then
    echo -e "${RED}❌ Rust not found. Install from https://rustup.rs${NC}"
    exit 1
fi
echo -e "${GREEN}✅${NC} Rust installed"

# Check Android SDK (optional, for building app)
if command -v adb &> /dev/null; then
    echo -e "${GREEN}✅${NC} Android SDK found"
else
    echo -e "${YELLOW}⚠️  Android SDK not found (optional, for building Android app)${NC}"
fi

echo ""
echo "🔧 Setting up project structure..."

# Create necessary directories
mkdir -p server/database
mkdir -p tui
mkdir -p android-app
mkdir -p logs
mkdir -p certs

echo "✅ Directories created"

# Setup server
echo ""
echo "📦 Setting up Rust server..."

cd server

# Check if .env exists
if [ ! -f .env ]; then
    echo "Creating server .env file..."
    cat > .env << EOF
# RDM Server Configuration

# Server binding
RDM_HOST=0.0.0.0
RDM_PORT=8443

# Database
DATABASE_URL=sqlite:database/rdm.db

# JWT Secret (CHANGE THIS IN PRODUCTION!)
JWT_SECRET=your-super-secret-jwt-key-change-this-in-production-min-32-chars

# TLS Configuration
TLS_CERT_PATH=./certs/server.crt
TLS_KEY_PATH=./certs/server.key

# Admin credentials (for initial setup)
ADMIN_USERNAME=admin
ADMIN_PASSWORD=admin123
EOF
    echo -e "${GREEN}✅${NC} Server .env created"
else
    echo -e "${YELLOW}⚠️  Server .env already exists${NC}"
fi

# Build server
echo "Building server..."
cargo build --release

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅${NC} Server built successfully"
else
    echo -e "${RED}❌ Server build failed${NC}"
    exit 1
fi

cd ..

# Setup TUI
echo ""
echo "📦 Setting up Rust TUI..."

cd tui

# Create .env file
if [ ! -f .env ]; then
    echo "Creating TUI .env file..."
    cat > .env << EOF
# RDM TUI Configuration

# Server URL
RDM_SERVER_URL=https://localhost:8443

# Authentication
RDM_USERNAME=admin
RDM_PASSWORD=admin123
EOF
    echo -e "${GREEN}✅${NC} TUI .env created"
else
    echo -e "${YELLOW}⚠️  TUI .env already exists${NC}"
fi

# Build TUI
echo "Building TUI..."
cargo build --release

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅${NC} TUI built successfully"
else
    echo -e "${RED}❌ TUI build failed${NC}"
    exit 1
fi

cd ..

# Setup TLS certificates (self-signed for development)
echo ""
echo "🔐 Setting up TLS certificates..."

cd certs

if [ ! -f server.key ] || [ ! -f server.crt ]; then
    echo "Generating self-signed certificates..."
    openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes \
        -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost" 2>/dev/null || \
    {
        echo -e "${YELLOW}⚠️  OpenSSL not found. TLS setup skipped.${NC}"
        echo "You'll need to install openssl or provide your own certificates."
        cd ..
        exit 0
    }

    chmod 600 server.key
    echo -e "${GREEN}✅${NC} Certificates generated"
else
    echo -e "${YELLOW}⚠️  Certificates already exist${NC}"
fi

cd ..

echo ""
echo "================================"
echo -e "${GREEN}✨ Setup complete!${NC}"
echo ""
echo "📝 Next steps:"
echo ""
echo "1. Update configuration files:"
echo "   - server/.env (change JWT_SECRET and passwords)"
echo "   - tui/.env (update server URL and credentials)"
echo ""
echo "2. Start the server:"
echo "   cd server && ../target/release/rdm-server"
echo ""
echo "3. In another terminal, start the TUI:"
echo "   cd tui && ../target/release/rdm-tui"
echo ""
echo "4. For the Android app:"
echo "   - Open in Android Studio"
echo "   - Build and install on rooted device"
echo "   - Update SERVER_URL in MainActivity.kt"
echo ""
echo "📚 Documentation:"
echo "   - README.md - Project overview"
echo "   - docs/API.md - API documentation"
echo "   - docs/SECURITY.md - Security guidelines"
echo ""
echo "⚠️  IMPORTANT:"
echo "   - Change default passwords in production"
echo "   - Use proper TLS certificates in production"
echo "   - Only use on devices you own"
echo ""
