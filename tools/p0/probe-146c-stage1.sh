#!/bin/bash
# 1.4.6c 真机验证 —— 阶段 1：确认 dsh CLI 形态与基线
set -u
export DSH_HOME=/root/.dsh
export PATH="/usr/local/bin:$PATH"

echo "===DSH_HOME==="
ls -a "$DSH_HOME" 2>&1 | head -20

echo "===PROFILES==="
ls "$DSH_HOME/profiles" 2>&1

echo "===DSH_HELP==="
dsh --help 2>&1 | head -60

echo "===DSH_VERSION==="
dsh --version 2>&1 | head -5
