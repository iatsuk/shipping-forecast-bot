#!/usr/bin/env bash
# =============================================================================
# VPS setup script for shipping-forecast-bot
# Target OS : Ubuntu 24.04 LTS
# Run as    : root
#
# Usage:
#   sudo bash vps-setup.sh --key "ssh-ed25519 AAAA..."
#   sudo bash vps-setup.sh --password "secret"
#   sudo bash vps-setup.sh --key "ssh-ed25519 AAAA..." --password "secret"
#
# At least one of --key or --password must be provided.
# Both can be combined (e.g. GitHub Actions uses key, you use password).
# =============================================================================
set -euo pipefail

# --------------------------------------------------------------------------- #
# Argument parsing
# --------------------------------------------------------------------------- #
SSH_PUBLIC_KEY=""
SSH_PASSWORD=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --key)      SSH_PUBLIC_KEY="$2"; shift 2 ;;
    --password) SSH_PASSWORD="$2";   shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [[ -z "$SSH_PUBLIC_KEY" && -z "$SSH_PASSWORD" ]]; then
  echo "Error: at least one of --key or --password must be provided."
  echo ""
  echo "Usage:"
  echo "  sudo bash vps-setup.sh --key \"ssh-ed25519 AAAA...\""
  echo "  sudo bash vps-setup.sh --password \"secret\""
  echo "  sudo bash vps-setup.sh --key \"ssh-ed25519 AAAA...\" --password \"secret\""
  exit 1
fi

if [[ "$EUID" -ne 0 ]]; then
  echo "Run as root: sudo bash vps-setup.sh [options]"
  exit 1
fi

APP_USER="sfb"
APP_DIR="/opt/sfb"
SERVICE_NAME="sfb"

echo "==> [1/9] Updating package index"
apt-get update -qq

echo "==> [2/9] Installing prerequisites"
apt-get install -y -qq curl wget gnupg lsb-release ufw openssh-server

# --------------------------------------------------------------------------- #
# Java 25 via Eclipse Temurin (Adoptium)
# --------------------------------------------------------------------------- #
echo "==> [3/9] Installing Eclipse Temurin 25 JRE"
if ! java -version 2>&1 | grep -q '"25'; then
  mkdir -p /etc/apt/keyrings
  wget -qO /etc/apt/keyrings/adoptium.asc \
    https://packages.adoptium.net/artifactory/api/gpg/key/public
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] \
    https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  apt-get install -y -qq temurin-25-jre
fi
java -version

# --------------------------------------------------------------------------- #
# Dedicated system user + directory layout
# --------------------------------------------------------------------------- #
echo "==> [4/9] Creating system user '${APP_USER}' and directories"
if ! id "${APP_USER}" &>/dev/null; then
  useradd \
    --system \
    --home-dir "${APP_DIR}" \
    --create-home \
    --shell /bin/bash \
    "${APP_USER}"
fi

mkdir -p "${APP_DIR}/data" "${APP_DIR}/logs" "${APP_DIR}/deploy"
chown -R "${APP_USER}:${APP_USER}" "${APP_DIR}"
chmod 750 "${APP_DIR}"

# --------------------------------------------------------------------------- #
# SSH: key-based auth
# --------------------------------------------------------------------------- #
if [[ -n "$SSH_PUBLIC_KEY" ]]; then
  echo "==> [5a/9] Configuring SSH key access"
  SSH_DIR="${APP_DIR}/.ssh"
  AUTH_KEYS="${SSH_DIR}/authorized_keys"
  mkdir -p "${SSH_DIR}"
  chmod 700 "${SSH_DIR}"
  if ! grep -qF "${SSH_PUBLIC_KEY}" "${AUTH_KEYS}" 2>/dev/null; then
    echo "${SSH_PUBLIC_KEY}" >> "${AUTH_KEYS}"
  fi
  chmod 600 "${AUTH_KEYS}"
  chown -R "${APP_USER}:${APP_USER}" "${SSH_DIR}"
fi

# --------------------------------------------------------------------------- #
# SSH: password-based auth
# --------------------------------------------------------------------------- #
if [[ -n "$SSH_PASSWORD" ]]; then
  echo "==> [5b/9] Configuring SSH password access"
  echo "${APP_USER}:${SSH_PASSWORD}" | chpasswd

  SSHD_CONF="/etc/ssh/sshd_config"
  # Enable password authentication if currently disabled
  if grep -q "^PasswordAuthentication no" "${SSHD_CONF}"; then
    sed -i 's/^PasswordAuthentication no/PasswordAuthentication yes/' "${SSHD_CONF}"
  elif ! grep -q "^PasswordAuthentication" "${SSHD_CONF}"; then
    echo "PasswordAuthentication yes" >> "${SSHD_CONF}"
  fi

  # Restart SSH to apply config
  systemctl restart ssh || systemctl restart sshd
  echo "    Password login enabled for user '${APP_USER}'"
fi

# --------------------------------------------------------------------------- #
# sudoers rule — sfb user may restart its own service, nothing else
# --------------------------------------------------------------------------- #
echo "==> [6/9] Configuring sudoers"
SUDOERS_FILE="/etc/sudoers.d/${SERVICE_NAME}"
cat > "${SUDOERS_FILE}" <<EOF
${APP_USER} ALL=(root) NOPASSWD: /bin/systemctl restart ${SERVICE_NAME}, \
                                  /bin/systemctl start ${SERVICE_NAME}, \
                                  /bin/systemctl stop ${SERVICE_NAME}, \
                                  /bin/systemctl status ${SERVICE_NAME}
EOF
chmod 440 "${SUDOERS_FILE}"
visudo -cf "${SUDOERS_FILE}"

# --------------------------------------------------------------------------- #
# Placeholder env file — GitHub Actions will write the real token here
# --------------------------------------------------------------------------- #
ENV_FILE="${APP_DIR}/env"
if [[ ! -f "${ENV_FILE}" ]]; then
  cat > "${ENV_FILE}" <<EOF
# Shipping Forecast Bot environment
# GitHub Actions overwrites TELEGRAM_BOT_TOKEN on every deploy.
TELEGRAM_BOT_TOKEN=replace-me
EOF
  chmod 600 "${ENV_FILE}"
  chown "${APP_USER}:${APP_USER}" "${ENV_FILE}"
fi

# --------------------------------------------------------------------------- #
# systemd unit
# --------------------------------------------------------------------------- #
echo "==> [7/9] Installing systemd service"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=Shipping Forecast Bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}

# Environment variables loaded from file — token is written by GitHub Actions
EnvironmentFile=${APP_DIR}/env

ExecStart=/usr/bin/java \\
  -server \\
  -Xms32m \\
  -Xmx192m \\
  -XX:MaxMetaspaceSize=96m \\
  -XX:+UseZGC \\
  -XX:+ZGenerational \\
  -XX:SoftMaxHeapSize=128m \\
  -Xlog:gc:${APP_DIR}/logs/gc.log::filecount=3,filesize=5m \\
  -Dlogback.configurationFile=${APP_DIR}/logback-prod.xml \\
  -jar ${APP_DIR}/app.jar

Restart=on-failure
RestartSec=10
RestartMaxDelaySec=120

LimitNOFILE=65536

StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"

# --------------------------------------------------------------------------- #
# journald retention
# --------------------------------------------------------------------------- #
echo "==> [8/9] Configuring journald"
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/sfb.conf <<EOF
[Journal]
SystemMaxUse=500M
MaxRetentionSec=1month
EOF
systemctl restart systemd-journald

# --------------------------------------------------------------------------- #
# Firewall
# --------------------------------------------------------------------------- #
echo "==> [9/9] Configuring firewall"
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw --force enable

# --------------------------------------------------------------------------- #
# Done
# --------------------------------------------------------------------------- #
echo ""
echo "=========================================================="
echo "VPS setup complete."
echo ""
echo "Run a deploy from GitHub Actions (push to main or use workflow_dispatch)."
echo "It will write the token to ${APP_DIR}/env and deploy the JAR and Logback config."
echo ""
echo "Then start the service:"
echo "  sudo systemctl start ${SERVICE_NAME}"
echo "  sudo journalctl -u ${SERVICE_NAME} -f"
echo ""
echo "GitHub Actions secrets required:"
echo "  VPS_HOST           — server IP or hostname"
echo "  VPS_USER           — ${APP_USER}"
echo "  VPS_SSH_KEY        — private key matching the public key provided to this script"
echo "  TELEGRAM_BOT_TOKEN — bot token from BotFather"
echo "=========================================================="
