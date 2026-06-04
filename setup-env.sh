#!/usr/bin/env bash
# Quick setup script for environment variables
# Usage: source setup-env.sh

# Pseudovision service
export PSEUDOVISION_URL=${PSEUDOVISION_URL:-https://pseudovision.kube.sea.fudo.link}
export PSEUDOVISION_TOKEN=${PSEUDOVISION_TOKEN:-}

# Tunarr Scheduler service  
export TUNARR_SCHEDULER_URL=${TUNARR_SCHEDULER_URL:-https://tunarr-scheduler.kube.sea.fudo.link}
export TUNARR_SCHEDULER_TOKEN=${TUNARR_SCHEDULER_TOKEN:-}

# Tunabrain service (future integration)
export TUNABRAIN_URL=${TUNABRAIN_URL:-https://tunabrain.kube.sea.fudo.link}
export TUNABRAIN_TOKEN=${TUNABRAIN_TOKEN:-}

echo "Environment variables set!"
echo "  PSEUDOVISION_URL=$PSEUDOVISION_URL"
echo "  TUNARR_SCHEDULER_URL=$TUNARR_SCHEDULER_URL"
echo "  TUNABRAIN_URL=$TUNABRAIN_URL"
