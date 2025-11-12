#!/bin/bash

# Simple script to pull latest changes on remote machine

set -e

REMOTE_HOST="merkur.local"
REMOTE_USER="fabian"
REMOTE_PROJECT_DIR="/Users/fabian/projects/telegram-files"
BRANCH="fix/download-concurrency-and-state-persistence"

echo "🚀 Pulling latest changes on remote..."
echo "📋 Branch: ${BRANCH}"
echo "🖥️  Remote: ${REMOTE_USER}@${REMOTE_HOST}"

ssh "${REMOTE_USER}@${REMOTE_HOST}" << EOF
    set -e
    
    cd ${REMOTE_PROJECT_DIR}
    
    echo "📥 Fetching latest changes..."
    git fetch origin
    
    echo "🔀 Checking out branch: ${BRANCH}"
    git checkout ${BRANCH} || git checkout -b ${BRANCH} origin/${BRANCH}
    
    echo "⬇️  Pulling latest changes..."
    git pull origin ${BRANCH}
    
    echo "✅ Changes pulled successfully!"
    echo ""
    echo "Current branch: \$(git branch --show-current)"
    echo "Latest commit: \$(git log -1 --oneline)"
EOF

if [ $? -ne 0 ]; then
    echo "❌ Failed to pull changes!"
    exit 1
fi

echo ""
echo "🎉 Done! You can now build and deploy on the remote machine."

