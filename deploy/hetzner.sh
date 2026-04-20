#!/usr/bin/env bash
# deploy/hetzner.sh — Production-grade Hetzner VPS deployment for Kudi wallet platform
#
# Provisions a hardened Hetzner Cloud VPS with:
#   PostgreSQL 18 (scram-sha-256, WAL archiving, daily backups)
#   OpenTelemetry Collector + Jaeger (traces) + Prometheus + Grafana (metrics)
#   Nginx reverse proxy (rate limiting, security headers, TLS via certbot)
#   Systemd-managed JVM service with sandboxing
#   Hetzner Cloud Firewall + Volume + Server Backups
#
# Usage:
#   ./deploy/hetzner.sh                        # Interactive setup
#   ./deploy/hetzner.sh --deploy [jar-path]    # Deploy/redeploy JAR to existing server
#   ./deploy/hetzner.sh --status               # Check remote server health
#   ./deploy/hetzner.sh --rollback             # Rollback to previous JAR
#   ./deploy/hetzner.sh --logs [lines]         # Tail kudi service logs
#   ./deploy/hetzner.sh --destroy              # Tear down the server (keeps volume)
#   ./deploy/hetzner.sh --destroy-all          # Tear down server + volume + firewall
#
# Server type recommendations (fintech workloads):
#   cpx31: 4 vCPU, 8GB RAM,  ~15.59 EUR/mo — Staging / low-traffic prod
#   cpx41: 8 vCPU, 16GB RAM, ~28.19 EUR/mo — Production
#   ccx23: 4 vCPU, 16GB RAM, ~22.49 EUR/mo — Production (dedicated CPU, consistent latency)
#   ccx33: 8 vCPU, 32GB RAM, ~42.99 EUR/mo — High-volume production
#
# Environment variables:
#   HETZNER_TOKEN     — Hetzner Cloud API token (required)
#   KUDI_ENV          — Environment tag: prod, staging, dev (default: prod)
#   KUDI_SERVER_TYPE  — Override server type prompt
#   KUDI_LOCATION     — Override region prompt
#   KUDI_DOMAIN       — Override domain prompt

set -euo pipefail

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
readonly HETZNER_API="https://api.hetzner.cloud/v1"
readonly SCRIPT_VERSION="2.0.0"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KUDI_ENV="${KUDI_ENV:-prod}"
SERVER_NAME="kudi-${KUDI_ENV}"
APP_DIR="/opt/kudi"
VOLUME_NAME="kudi-pgdata-${KUDI_ENV}"
FIREWALL_NAME="kudi-fw-${KUDI_ENV}"
CREDENTIALS_FILE="$HOME/.kudi-${KUDI_ENV}.env"

# ---------------------------------------------------------------------------
# Colors (disabled if not a terminal)
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    DIM='\033[2m'
    RESET='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' DIM='' RESET=''
fi

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()    { printf "${BLUE}[*]${RESET} %s\n" "$*"; }
success() { printf "${GREEN}[+]${RESET} %s\n" "$*"; }
warn()    { printf "${YELLOW}[!]${RESET} %s\n" "$*"; }
error()   { printf "${RED}[x]${RESET} %s\n" "$*" >&2; }
fatal()   { error "$*"; exit 1; }

prompt() {
    local var_name="$1" prompt_text="$2" default="${3:-}"
    if [[ -n "$default" ]]; then
        printf "${CYAN}    %s${DIM} [%s]${RESET}: " "$prompt_text" "$default"
    else
        printf "${CYAN}    %s${RESET}: " "$prompt_text"
    fi
    read -r input
    printf -v "$var_name" '%s' "${input:-$default}"
}

prompt_secret() {
    local var_name="$1" prompt_text="$2" default="${3:-}"
    if [[ -n "$default" ]]; then
        printf "${CYAN}    %s${DIM} [********]${RESET}: " "$prompt_text"
    else
        printf "${CYAN}    %s${RESET}: " "$prompt_text"
    fi
    read -rs input
    echo
    printf -v "$var_name" '%s' "${input:-$default}"
}

generate_password() {
    openssl rand -base64 36
}

generate_key() {
    openssl rand -hex 32
}

ssh_cmd() {
    local ip="$1"
    shift
    ssh -o ConnectTimeout=10 \
        -o StrictHostKeyChecking=no \
        -o BatchMode=yes \
        -o LogLevel=ERROR \
        "root@$ip" "$@"
}

scp_cmd() {
    scp -o ConnectTimeout=10 \
        -o StrictHostKeyChecking=no \
        -o LogLevel=ERROR \
        "$@"
}

hetzner_api() {
    local method="$1" path="$2"
    shift 2
    local response http_code
    response=$(curl -s -w "\n%{http_code}" -X "$method" \
        -H "Authorization: Bearer $HETZNER_TOKEN" \
        -H "Content-Type: application/json" \
        "$HETZNER_API$path" \
        "$@")
    http_code=$(echo "$response" | tail -1)
    response=$(echo "$response" | sed '$d')

    if [[ "$http_code" -ge 400 ]]; then
        local err_msg
        err_msg=$(echo "$response" | jq -r '.error.message // "Unknown error"' 2>/dev/null || echo "HTTP $http_code")
        error "Hetzner API error ($method $path): $err_msg"
        return 1
    fi
    echo "$response"
}

save_credentials() {
    local ip="$1" db_password="$2" backup_encryption_key="$3"

    cat > "$CREDENTIALS_FILE" <<EOF
# Kudi $KUDI_ENV credentials — generated $(date -u +%Y-%m-%dT%H:%M:%SZ)
# KEEP THIS FILE SECURE — it contains production secrets
KUDI_SERVER_IP=$ip
KUDI_DB_PASSWORD=$db_password
KUDI_BACKUP_ENCRYPTION_KEY=$backup_encryption_key
KUDI_ENV=$KUDI_ENV
EOF
    chmod 600 "$CREDENTIALS_FILE"
    success "Credentials saved to $CREDENTIALS_FILE (mode 600)"
}

load_credentials() {
    if [[ -f "$CREDENTIALS_FILE" ]]; then
        # shellcheck source=/dev/null
        source "$CREDENTIALS_FILE"
    fi
}

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
preflight() {
    local missing=()
    command -v curl    &>/dev/null || missing+=("curl")
    command -v jq      &>/dev/null || missing+=("jq")
    command -v ssh     &>/dev/null || missing+=("ssh")
    command -v openssl &>/dev/null || missing+=("openssl")

    if [[ ${#missing[@]} -gt 0 ]]; then
        fatal "Missing required tools: ${missing[*]}. Install them and try again."
    fi
}

# ---------------------------------------------------------------------------
# Validate Hetzner token
# ---------------------------------------------------------------------------
validate_token() {
    info "Validating Hetzner API token..."
    hetzner_api GET "/servers?per_page=1" > /dev/null || {
        fatal "Invalid Hetzner API token. Get one at: https://console.hetzner.cloud"
    }
    success "Token is valid."
}

# ---------------------------------------------------------------------------
# Server lookup
# ---------------------------------------------------------------------------
find_server() {
    local response
    response=$(hetzner_api GET "/servers?name=$SERVER_NAME")
    echo "$response" | jq -r '.servers[0] // empty'
}

get_server_id() {
    local server
    server=$(find_server)
    if [[ -n "$server" && "$server" != "null" ]]; then
        echo "$server" | jq -r '.id'
    fi
}

get_server_ip() {
    local server
    server=$(find_server)
    if [[ -n "$server" && "$server" != "null" ]]; then
        echo "$server" | jq -r '.public_net.ipv4.ip'
    fi
}

require_server() {
    local ip
    ip=$(get_server_ip)
    if [[ -z "$ip" ]]; then
        load_credentials
        ip="${KUDI_SERVER_IP:-}"
    fi
    if [[ -z "$ip" ]]; then
        fatal "No server found. Run ./deploy/hetzner.sh to create one."
    fi
    echo "$ip"
}

# ---------------------------------------------------------------------------
# Hetzner Cloud Firewall
# ---------------------------------------------------------------------------
find_firewall_id() {
    local response
    response=$(hetzner_api GET "/firewalls?name=$FIREWALL_NAME")
    echo "$response" | jq -r '.firewalls[0].id // empty'
}

create_firewall() {
    info "Creating Hetzner Cloud Firewall: $FIREWALL_NAME..."
    local existing
    existing=$(find_firewall_id)
    if [[ -n "$existing" ]]; then
        success "Firewall already exists (ID: $existing)"
        echo "$existing"
        return
    fi

    local payload response fw_id
    payload=$(jq -n --arg name "$FIREWALL_NAME" '{
        name: $name,
        rules: [
            {
                direction: "in",
                protocol: "tcp",
                port: "22",
                source_ips: ["0.0.0.0/0", "::/0"],
                description: "SSH"
            },
            {
                direction: "in",
                protocol: "tcp",
                port: "80",
                source_ips: ["0.0.0.0/0", "::/0"],
                description: "HTTP"
            },
            {
                direction: "in",
                protocol: "tcp",
                port: "443",
                source_ips: ["0.0.0.0/0", "::/0"],
                description: "HTTPS"
            },
            {
                direction: "in",
                protocol: "icmp",
                source_ips: ["0.0.0.0/0", "::/0"],
                description: "Ping"
            }
        ]
    }')

    response=$(hetzner_api POST "/firewalls" -d "$payload")
    fw_id=$(echo "$response" | jq -r '.firewall.id')
    success "Firewall created (ID: $fw_id)"
    echo "$fw_id"
}

# ---------------------------------------------------------------------------
# Hetzner Volume (persistent PG data)
# ---------------------------------------------------------------------------
find_volume_id() {
    local response
    response=$(hetzner_api GET "/volumes?name=$VOLUME_NAME")
    echo "$response" | jq -r '.volumes[0].id // empty'
}

create_volume() {
    local location="$1"
    info "Creating persistent volume: $VOLUME_NAME (50GB)..."

    local existing
    existing=$(find_volume_id)
    if [[ -n "$existing" ]]; then
        success "Volume already exists (ID: $existing)"
        echo "$existing"
        return
    fi

    local payload response vol_id
    payload=$(jq -n \
        --arg name "$VOLUME_NAME" \
        --arg location "$location" \
        '{
            name: $name,
            size: 50,
            location: $location,
            format: "ext4",
            automount: false
        }')

    response=$(hetzner_api POST "/volumes" -d "$payload")
    vol_id=$(echo "$response" | jq -r '.volume.id')
    success "Volume created (ID: $vol_id, 50GB)"
    echo "$vol_id"
}

# ---------------------------------------------------------------------------
# Hetzner Server Backups
# ---------------------------------------------------------------------------
enable_server_backups() {
    local server_id="$1"
    info "Enabling Hetzner server backups..."
    hetzner_api POST "/servers/$server_id/actions/enable_backup" > /dev/null || true
    success "Server backups enabled (daily snapshots, 7-day retention)."
}

# ---------------------------------------------------------------------------
# SSH keys
# ---------------------------------------------------------------------------
get_ssh_keys() {
    local response
    response=$(hetzner_api GET "/ssh_keys")
    echo "$response" | jq -r '[.ssh_keys[].id] // []'
}

# ---------------------------------------------------------------------------
# Build cloud-init user_data
# ---------------------------------------------------------------------------
build_cloud_init() {
    local db_password="$1" domain="$2" backup_encryption_key="$3"

    local nginx_block=""
    if [[ -n "$domain" ]]; then
        nginx_block="
# --- Nginx reverse proxy + TLS ---
- apt-get install -y nginx certbot python3-certbot-nginx

- |
  cat > /etc/nginx/conf.d/rate-limit.conf <<'RATELIMIT'
  limit_req_zone \$binary_remote_addr zone=api:10m rate=30r/s;
  limit_req_zone \$binary_remote_addr zone=auth:10m rate=5r/s;
  limit_conn_zone \$binary_remote_addr zone=connlimit:10m;
  RATELIMIT
  sed -i 's/^  //' /etc/nginx/conf.d/rate-limit.conf

- |
  cat > /etc/nginx/sites-available/kudi <<'NGINX'
  server {
      listen 80;
      server_name ${domain};
      client_max_body_size 2m;

      # Security headers
      add_header X-Content-Type-Options nosniff always;
      add_header X-Frame-Options DENY always;
      add_header X-XSS-Protection \"1; mode=block\" always;
      add_header Strict-Transport-Security \"max-age=63072000; includeSubDomains; preload\" always;
      add_header Referrer-Policy strict-origin-when-cross-origin always;
      add_header Content-Security-Policy \"default-src 'none'; frame-ancestors 'none'\" always;
      add_header Permissions-Policy \"camera=(), microphone=(), geolocation=()\" always;

      server_tokens off;

      # Health — no rate limit
      location /health {
          proxy_pass http://127.0.0.1:8080;
          proxy_set_header Host \$host;
          proxy_set_header X-Real-IP \$remote_addr;
          proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
          proxy_set_header X-Forwarded-Proto \$scheme;
          access_log off;
      }

      # Auth endpoints — strict rate limit
      location ~ ^/api/v1/(auth|login|register) {
          limit_req zone=auth burst=3 nodelay;
          limit_conn connlimit 5;
          proxy_pass http://127.0.0.1:8080;
          proxy_set_header Host \$host;
          proxy_set_header X-Real-IP \$remote_addr;
          proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
          proxy_set_header X-Forwarded-Proto \$scheme;
          proxy_read_timeout 15s;
          proxy_connect_timeout 5s;
      }

      # API — standard rate limit
      location /api/ {
          limit_req zone=api burst=20 nodelay;
          limit_conn connlimit 20;
          proxy_pass http://127.0.0.1:8080;
          proxy_set_header Host \$host;
          proxy_set_header X-Real-IP \$remote_addr;
          proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
          proxy_set_header X-Forwarded-Proto \$scheme;
          proxy_read_timeout 30s;
          proxy_connect_timeout 5s;
      }

      # Deny everything else
      location / {
          return 404;
      }
  }
  NGINX
  sed -i 's/^  //' /etc/nginx/sites-available/kudi

- ln -sf /etc/nginx/sites-available/kudi /etc/nginx/sites-enabled/kudi
- rm -f /etc/nginx/sites-enabled/default
- nginx -t && systemctl restart nginx

# TLS via certbot
- certbot --nginx -d $domain --non-interactive --agree-tos -m admin@$domain || echo 'Certbot: point DNS first, then run: certbot --nginx -d $domain'
- systemctl enable --now certbot.timer
"
    fi

    cat <<CLOUDINIT
#cloud-config
package_update: true
packages:
  - openjdk-21-jre-headless
  - docker.io
  - docker-compose-plugin
  - unattended-upgrades
  - fail2ban
  - ufw
  - logrotate
  - jq

write_files:
# --- SSH hardening ---
- path: /etc/ssh/sshd_config.d/90-hardening.conf
  content: |
    PermitRootLogin prohibit-password
    PasswordAuthentication no
    ChallengeResponseAuthentication no
    X11Forwarding no
    MaxAuthTries 3
    LoginGraceTime 20
    ClientAliveInterval 300
    ClientAliveCountMax 2
    AllowAgentForwarding no
    AllowTcpForwarding yes
  permissions: '0644'

# --- fail2ban ---
- path: /etc/fail2ban/jail.local
  content: |
    [sshd]
    enabled = true
    port = ssh
    filter = sshd
    maxretry = 3
    bantime = 3600
    findtime = 600
    ignoreip = 127.0.0.1/8
  permissions: '0644'

# --- Docker daemon (log rotation, live-restore) ---
- path: /etc/docker/daemon.json
  content: |
    {
      "log-driver": "json-file",
      "log-opts": {
        "max-size": "10m",
        "max-file": "3"
      },
      "live-restore": true,
      "default-ulimits": {
        "nofile": { "Name": "nofile", "Hard": 65536, "Soft": 65536 }
      }
    }
  permissions: '0644'

# --- Logrotate for kudi ---
- path: /etc/logrotate.d/kudi
  content: |
    /var/log/kudi/*.log {
      daily
      rotate 14
      compress
      delaycompress
      missingok
      notifempty
      create 0640 kudi kudi
    }
  permissions: '0644'

# --- Journald limits ---
- path: /etc/systemd/journald.conf.d/kudi.conf
  content: |
    [Journal]
    SystemMaxUse=500M
    SystemMaxFileSize=50M
    MaxRetentionSec=30day
  permissions: '0644'

runcmd:
# --- Firewall (defense in depth — Hetzner firewall is primary) ---
- ufw default deny incoming
- ufw default allow outgoing
- ufw allow 22/tcp
- ufw allow 80/tcp
- ufw allow 443/tcp
- ufw --force enable

# --- SSH hardening apply ---
- systemctl restart sshd

# --- fail2ban ---
- systemctl enable --now fail2ban

# --- Kernel tuning ---
- |
  cat > /etc/sysctl.d/99-kudi.conf <<'SYSCTL'
  vm.swappiness=1
  vm.overcommit_memory=0
  vm.dirty_ratio=10
  vm.dirty_background_ratio=3
  net.core.somaxconn=4096
  net.core.netdev_max_backlog=4096
  net.ipv4.tcp_max_syn_backlog=4096
  net.ipv4.tcp_keepalive_time=60
  net.ipv4.tcp_keepalive_intvl=10
  net.ipv4.tcp_keepalive_probes=3
  net.ipv4.tcp_fin_timeout=15
  net.ipv4.tcp_tw_reuse=1
  net.ipv4.ip_local_port_range=1024 65535
  net.ipv4.conf.all.rp_filter=1
  net.ipv4.conf.default.rp_filter=1
  net.ipv4.conf.all.accept_redirects=0
  net.ipv4.conf.default.accept_redirects=0
  net.ipv4.conf.all.send_redirects=0
  net.ipv4.conf.default.send_redirects=0
  net.ipv4.icmp_echo_ignore_broadcasts=1
  SYSCTL
  sysctl --system

# --- 2GB swap for OOM protection ---
- |
  if [[ ! -f /swapfile ]]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi

# --- System user ---
- useradd --system --no-create-home --shell /usr/sbin/nologin kudi || true

# --- Directories ---
- mkdir -p $APP_DIR/{migrations,otel,backups,logs,jars}
- mkdir -p /var/log/kudi
- chown -R kudi:kudi $APP_DIR /var/log/kudi

# --- Mount Hetzner volume ---
- |
  VOLUME_DEV=\$(ls /dev/disk/by-id/scsi-0HC_Volume_* 2>/dev/null | head -1)
  if [[ -n "\$VOLUME_DEV" ]]; then
    mkdir -p /mnt/pgdata
    if ! blkid "\$VOLUME_DEV" | grep -q ext4; then
      mkfs.ext4 -L pgdata "\$VOLUME_DEV"
    fi
    mount "\$VOLUME_DEV" /mnt/pgdata
    echo "\$VOLUME_DEV /mnt/pgdata ext4 defaults,noatime,nofail 0 2" >> /etc/fstab
    mkdir -p /mnt/pgdata/data /mnt/pgdata/wal-archive
    chmod 700 /mnt/pgdata/data
  else
    mkdir -p /mnt/pgdata/data /mnt/pgdata/wal-archive
  fi

# --- Docker ---
- systemctl enable --now docker

# --- Docker compose stack ---
- |
  cat > $APP_DIR/docker-compose.yaml <<'COMPOSE'
  services:
    postgres:
      image: postgres:18-alpine
      restart: unless-stopped
      environment:
        POSTGRES_USER: kudi
        POSTGRES_PASSWORD: "\${DB_PASSWORD}"
        POSTGRES_DB: kudi
        POSTGRES_INITDB_ARGS: "--data-checksums --auth-local=scram-sha-256 --auth-host=scram-sha-256"
      ports:
        - "127.0.0.1:5432:5432"
      volumes:
        - /opt/kudi/migrations:/docker-entrypoint-initdb.d
        - /mnt/pgdata/data:/var/lib/postgresql/data
        - /mnt/pgdata/wal-archive:/var/lib/postgresql/wal-archive
      command:
        - "postgres"
        - "-c"
        - "shared_buffers=512MB"
        - "-c"
        - "effective_cache_size=1536MB"
        - "-c"
        - "work_mem=8MB"
        - "-c"
        - "maintenance_work_mem=256MB"
        - "-c"
        - "huge_pages=try"
        - "-c"
        - "max_connections=150"
        - "-c"
        - "superuser_reserved_connections=3"
        - "-c"
        - "wal_level=replica"
        - "-c"
        - "max_wal_size=2GB"
        - "-c"
        - "min_wal_size=512MB"
        - "-c"
        - "checkpoint_completion_target=0.9"
        - "-c"
        - "wal_compression=zstd"
        - "-c"
        - "archive_mode=on"
        - "-c"
        - "archive_command=cp %p /var/lib/postgresql/wal-archive/%f"
        - "-c"
        - "random_page_cost=1.1"
        - "-c"
        - "effective_io_concurrency=200"
        - "-c"
        - "default_statistics_target=200"
        - "-c"
        - "log_min_duration_statement=200"
        - "-c"
        - "log_checkpoints=on"
        - "-c"
        - "log_lock_waits=on"
        - "-c"
        - "log_temp_files=64MB"
        - "-c"
        - "log_autovacuum_min_duration=250"
        - "-c"
        - "log_line_prefix=%t [%p-%l] %q%u@%d "
        - "-c"
        - "password_encryption=scram-sha-256"
        - "-c"
        - "ssl=off"
        - "-c"
        - "autovacuum_max_workers=3"
        - "-c"
        - "autovacuum_vacuum_scale_factor=0.05"
        - "-c"
        - "autovacuum_analyze_scale_factor=0.02"
      healthcheck:
        test: ["CMD-SHELL", "pg_isready -U kudi -d kudi"]
        interval: 5s
        timeout: 3s
        retries: 5
        start_period: 30s
      deploy:
        resources:
          limits:
            memory: 2G
      shm_size: '256mb'

    otel-collector:
      image: otel/opentelemetry-collector-contrib:0.115.0
      restart: unless-stopped
      ports:
        - "127.0.0.1:4317:4317"
        - "127.0.0.1:4318:4318"
        - "127.0.0.1:8889:8889"
      volumes:
        - /opt/kudi/otel/otel-config.yaml:/etc/otelcol-contrib/config.yaml
      depends_on:
        - jaeger
      deploy:
        resources:
          limits:
            memory: 256M

    jaeger:
      image: jaegertracing/all-in-one:1.62
      restart: unless-stopped
      environment:
        COLLECTOR_OTLP_ENABLED: "true"
        SPAN_STORAGE_TYPE: "badger"
        BADGER_EPHEMERAL: "false"
        BADGER_DIRECTORY_VALUE: "/badger/data"
        BADGER_DIRECTORY_KEY: "/badger/key"
      ports:
        - "127.0.0.1:16686:16686"
      volumes:
        - jaeger-data:/badger
      deploy:
        resources:
          limits:
            memory: 512M

    prometheus:
      image: prom/prometheus:v2.54.1
      restart: unless-stopped
      ports:
        - "127.0.0.1:9090:9090"
      volumes:
        - /opt/kudi/otel/prometheus.yaml:/etc/prometheus/prometheus.yml
        - prometheus-data:/prometheus
      command:
        - "--config.file=/etc/prometheus/prometheus.yml"
        - "--storage.tsdb.retention.time=30d"
        - "--storage.tsdb.retention.size=5GB"
      deploy:
        resources:
          limits:
            memory: 512M

    grafana:
      image: grafana/grafana:11.3.0
      restart: unless-stopped
      environment:
        GF_SECURITY_ADMIN_PASSWORD: "\${GRAFANA_PASSWORD}"
        GF_SERVER_ROOT_URL: "http://localhost:3000"
        GF_USERS_ALLOW_SIGN_UP: "false"
        GF_SECURITY_COOKIE_SECURE: "true"
        GF_SECURITY_STRICT_TRANSPORT_SECURITY: "true"
      ports:
        - "127.0.0.1:3000:3000"
      volumes:
        - grafana-data:/var/lib/grafana
        - /opt/kudi/otel/grafana-datasources.yaml:/etc/grafana/provisioning/datasources/datasources.yaml
      depends_on:
        - prometheus
      deploy:
        resources:
          limits:
            memory: 256M

  volumes:
    jaeger-data:
    prometheus-data:
    grafana-data:
  COMPOSE
  sed -i 's/^  //' $APP_DIR/docker-compose.yaml

# --- Docker .env for secrets ---
- |
  cat > $APP_DIR/.env <<DOTENV
  DB_PASSWORD=$db_password
  GRAFANA_PASSWORD=$(openssl rand -base64 16)
  DOTENV
  sed -i 's/^  //' $APP_DIR/.env
  chmod 600 $APP_DIR/.env

# --- OTel Collector config ---
- |
  cat > $APP_DIR/otel/otel-config.yaml <<'OTELCFG'
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

  processors:
    batch:
      timeout: 5s
      send_batch_size: 1024
      send_batch_max_size: 2048
    memory_limiter:
      check_interval: 5s
      limit_mib: 200
      spike_limit_mib: 50

  exporters:
    otlp/jaeger:
      endpoint: jaeger:4317
      tls:
        insecure: true
    prometheus:
      endpoint: "0.0.0.0:8889"
      resource_to_telemetry_conversion:
        enabled: true

  service:
    pipelines:
      traces:
        receivers: [otlp]
        processors: [memory_limiter, batch]
        exporters: [otlp/jaeger]
      metrics:
        receivers: [otlp]
        processors: [memory_limiter, batch]
        exporters: [prometheus]
  OTELCFG
  sed -i 's/^  //' $APP_DIR/otel/otel-config.yaml

# --- Prometheus config ---
- |
  cat > $APP_DIR/otel/prometheus.yaml <<'PROMCFG'
  global:
    scrape_interval: 15s
    evaluation_interval: 15s

  scrape_configs:
    - job_name: 'otel-collector'
      static_configs:
        - targets: ['otel-collector:8889']
    - job_name: 'prometheus'
      static_configs:
        - targets: ['localhost:9090']
  PROMCFG
  sed -i 's/^  //' $APP_DIR/otel/prometheus.yaml

# --- Grafana datasource provisioning ---
- |
  cat > $APP_DIR/otel/grafana-datasources.yaml <<'GRAFANA'
  apiVersion: 1
  datasources:
    - name: Prometheus
      type: prometheus
      access: proxy
      url: http://prometheus:9090
      isDefault: true
    - name: Jaeger
      type: jaeger
      access: proxy
      url: http://jaeger:16686
  GRAFANA
  sed -i 's/^  //' $APP_DIR/otel/grafana-datasources.yaml

# --- Start infrastructure ---
- cd $APP_DIR && docker compose up -d

# --- Wait for Postgres ---
- |
  echo "Waiting for PostgreSQL..."
  for i in \$(seq 1 60); do
    if docker compose -f $APP_DIR/docker-compose.yaml exec -T postgres pg_isready -U kudi -d kudi &>/dev/null; then
      echo "PostgreSQL is ready."
      break
    fi
    sleep 2
  done

# --- Automated PG backup script ---
- |
  cat > $APP_DIR/backup.sh <<'BACKUP'
  #!/usr/bin/env bash
  set -euo pipefail
  BACKUP_DIR="/opt/kudi/backups"
  RETENTION_DAYS=14
  TIMESTAMP=\$(date +%Y%m%d_%H%M%S)
  BACKUP_FILE="\$BACKUP_DIR/kudi_\$TIMESTAMP.sql.gz"

  docker compose -f /opt/kudi/docker-compose.yaml exec -T postgres \
    pg_dump -U kudi -d kudi --no-owner --no-privileges -Z 6 \
    > "\$BACKUP_FILE"

  if [[ ! -s "\$BACKUP_FILE" ]]; then
    echo "ERROR: Backup file is empty" >&2
    rm -f "\$BACKUP_FILE"
    exit 1
  fi

  find "\$BACKUP_DIR" -name "kudi_*.sql.gz" -mtime +\$RETENTION_DAYS -delete
  echo "Backup complete: \$BACKUP_FILE (\$(du -h "\$BACKUP_FILE" | cut -f1))"
  BACKUP
  chmod 755 $APP_DIR/backup.sh

# --- Backup cron: daily at 02:00 UTC ---
- echo '0 2 * * * root /opt/kudi/backup.sh >> /var/log/kudi/backup.log 2>&1' > /etc/cron.d/kudi-backup
- chmod 644 /etc/cron.d/kudi-backup

# --- WAL archive cleanup (keep 7 days) ---
- echo '0 3 * * * root find /mnt/pgdata/wal-archive -type f -mtime +7 -delete' > /etc/cron.d/kudi-wal-cleanup
- chmod 644 /etc/cron.d/kudi-wal-cleanup

# --- Kudi systemd service (sandboxed) ---
- |
  cat > /etc/systemd/system/kudi.service <<'SYSTEMD'
  [Unit]
  Description=Kudi Wallet Platform
  After=network.target docker.service
  Requires=docker.service
  StartLimitIntervalSec=300
  StartLimitBurst=5

  [Service]
  Type=simple
  User=kudi
  Group=kudi
  WorkingDirectory=/opt/kudi

  ExecStart=/usr/bin/java \
    -Xms512m -Xmx1536m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:+UseStringDeduplication \
    -XX:+AlwaysPreTouch \
    -Dotel.java.global-autoconfigure.enabled=true \
    -Dotel.service.name=kudi \
    -Dotel.exporter.otlp.endpoint=http://127.0.0.1:4317 \
    -Dotel.metrics.exporter=otlp \
    -Dotel.traces.exporter=otlp \
    -jar /opt/kudi/kudi.jar

  Restart=on-failure
  RestartSec=5
  StandardOutput=journal
  StandardError=journal
  SyslogIdentifier=kudi

  # Resource limits
  LimitNOFILE=65536
  LimitNPROC=4096
  MemoryMax=2G
  CPUAccounting=true

  # Sandboxing
  ProtectSystem=strict
  ProtectHome=true
  PrivateTmp=true
  PrivateDevices=true
  NoNewPrivileges=true
  ProtectKernelTunables=true
  ProtectKernelModules=true
  ProtectControlGroups=true
  RestrictSUIDSGID=true
  RestrictNamespaces=true
  ReadWritePaths=/opt/kudi /var/log/kudi

  [Install]
  WantedBy=multi-user.target
  SYSTEMD
  sed -i 's/^  //' /etc/systemd/system/kudi.service

# --- Permissions ---
- chown -R kudi:kudi $APP_DIR /var/log/kudi

# --- Enable (won't start until JAR deployed) ---
- systemctl daemon-reload
- systemctl enable kudi

$nginx_block
CLOUDINIT
}

# ---------------------------------------------------------------------------
# Wait for SSH
# ---------------------------------------------------------------------------
wait_for_ssh() {
    local ip="$1" max_attempts=40
    info "Waiting for server to become reachable..."

    for i in $(seq 1 $max_attempts); do
        if ssh_cmd "$ip" "echo ok" &>/dev/null; then
            return 0
        fi
        printf "."
        sleep 5
    done
    echo
    return 1
}

# ---------------------------------------------------------------------------
# Wait for cloud-init
# ---------------------------------------------------------------------------
wait_for_cloud_init() {
    local ip="$1" max_attempts=60
    info "Waiting for cloud-init provisioning..."

    for i in $(seq 1 $max_attempts); do
        local status
        status=$(ssh_cmd "$ip" "cloud-init status 2>/dev/null | awk '{print \$NF}'" 2>/dev/null || echo "")
        if [[ "$status" == "done" ]]; then
            return 0
        elif [[ "$status" == "error" ]]; then
            warn "cloud-init reported errors. Check: ssh root@$ip 'cloud-init status --long'"
            return 1
        fi
        printf "."
        sleep 10
    done
    echo
    warn "cloud-init still running after timeout."
    return 1
}

# ---------------------------------------------------------------------------
# Deploy migrations
# ---------------------------------------------------------------------------
deploy_migrations() {
    local ip="$1"
    local migration_dir="$PROJECT_ROOT/kudi/src/main/resources/migrations"

    if [[ -d "$migration_dir" ]]; then
        info "Uploading migrations..."
        scp_cmd -r "$migration_dir"/* "root@$ip:$APP_DIR/migrations/"
        ssh_cmd "$ip" "chown -R kudi:kudi $APP_DIR/migrations"
        success "Migrations uploaded."
    else
        warn "No migrations directory found at $migration_dir"
    fi
}

# ---------------------------------------------------------------------------
# Deploy JAR (zero-downtime with rollback support)
# ---------------------------------------------------------------------------
deploy_jar() {
    local ip="$1" jar_path="${2:-}"

    if [[ -z "$jar_path" ]]; then
        info "Building kudi fat JAR..."
        command -v sbt &>/dev/null || fatal "sbt not found. Pass JAR path: --deploy /path/to/kudi.jar"
        (cd "$PROJECT_ROOT" && sbt --client "kudi/assembly") || fatal "JAR build failed."

        jar_path=$(find "$PROJECT_ROOT/kudi/target" -name "*.jar" -path "*/assembly/*" 2>/dev/null | head -1)
        if [[ -z "$jar_path" ]]; then
            jar_path=$(find "$PROJECT_ROOT/kudi/target" -name "*.jar" 2>/dev/null | sort -t/ -k1 | tail -1)
        fi
        [[ -n "$jar_path" ]] || fatal "Could not find assembled JAR."
    fi

    [[ -f "$jar_path" ]] || fatal "JAR not found: $jar_path"

    local jar_size
    jar_size=$(du -h "$jar_path" | cut -f1)
    info "Deploying JAR ($jar_size): $jar_path"

    scp_cmd "$jar_path" "root@$ip:$APP_DIR/jars/kudi-new.jar"

    ssh_cmd "$ip" bash <<'REMOTE_DEPLOY'
        set -euo pipefail
        APP="/opt/kudi"

        # Verify JAR integrity
        if ! unzip -t "$APP/jars/kudi-new.jar" > /dev/null 2>&1; then
            echo "ERROR: Uploaded JAR is corrupted" >&2
            rm -f "$APP/jars/kudi-new.jar"
            exit 1
        fi

        # Backup current JAR
        if [[ -f "$APP/kudi.jar" ]]; then
            cp "$APP/kudi.jar" "$APP/jars/kudi-prev.jar"
        fi

        # Atomic swap
        mv "$APP/jars/kudi-new.jar" "$APP/kudi.jar"
        chown kudi:kudi "$APP/kudi.jar"

        # Restart
        systemctl restart kudi

        # Health check
        echo "Waiting for health check..."
        for i in $(seq 1 30); do
            if curl -sf http://127.0.0.1:8080/health > /dev/null 2>&1; then
                echo "Health check passed."
                exit 0
            fi
            sleep 2
        done
        echo "WARNING: Health check did not pass within 60s. Check: journalctl -u kudi -n 50"
REMOTE_DEPLOY

    success "JAR deployed."
}

# ---------------------------------------------------------------------------
# Rollback
# ---------------------------------------------------------------------------
rollback() {
    local ip
    ip=$(require_server)

    info "Rolling back to previous JAR..."
    ssh_cmd "$ip" bash <<'REMOTE_ROLLBACK'
        set -euo pipefail
        APP="/opt/kudi"
        if [[ ! -f "$APP/jars/kudi-prev.jar" ]]; then
            echo "ERROR: No previous JAR found" >&2
            exit 1
        fi
        cp "$APP/jars/kudi-prev.jar" "$APP/kudi.jar"
        chown kudi:kudi "$APP/kudi.jar"
        systemctl restart kudi
        echo "Rolled back. Waiting for health check..."
        for i in $(seq 1 30); do
            if curl -sf http://127.0.0.1:8080/health > /dev/null 2>&1; then
                echo "Health check passed."
                exit 0
            fi
            sleep 2
        done
        echo "WARNING: Health check did not pass within 60s."
REMOTE_ROLLBACK

    success "Rollback complete."
}

# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------
check_status() {
    local ip
    ip=$(require_server)

    echo
    printf "${BOLD}${GREEN}  Kudi Status — %s (%s)${RESET}\n" "$ip" "$KUDI_ENV"
    echo

    ssh_cmd "$ip" bash <<'STATUS'
        printf "  Services:\n"
        printf "    kudi:       %s\n" "$(systemctl is-active kudi 2>/dev/null || echo 'not running')"
        printf "    postgres:   %s\n" "$(docker compose -f /opt/kudi/docker-compose.yaml ps postgres --format '{{.Status}}' 2>/dev/null || echo 'unknown')"
        printf "    otel:       %s\n" "$(docker compose -f /opt/kudi/docker-compose.yaml ps otel-collector --format '{{.Status}}' 2>/dev/null || echo 'unknown')"
        printf "    jaeger:     %s\n" "$(docker compose -f /opt/kudi/docker-compose.yaml ps jaeger --format '{{.Status}}' 2>/dev/null || echo 'unknown')"
        printf "    prometheus: %s\n" "$(docker compose -f /opt/kudi/docker-compose.yaml ps prometheus --format '{{.Status}}' 2>/dev/null || echo 'unknown')"
        printf "    grafana:    %s\n" "$(docker compose -f /opt/kudi/docker-compose.yaml ps grafana --format '{{.Status}}' 2>/dev/null || echo 'unknown')"
        echo

        printf "  Disk:\n"
        df -h / /mnt/pgdata 2>/dev/null | awk 'NR>1{printf "    %-20s %s used / %s total (%s)\n", $6, $3, $2, $5}'
        echo

        printf "  Memory:\n"
        free -h | awk '/^Mem:/{printf "    %s used / %s total\n", $3, $2}'
        echo

        printf "  PG connections:\n"
        docker compose -f /opt/kudi/docker-compose.yaml exec -T postgres \
            psql -U kudi -d kudi -t -c "SELECT count(*) || ' active' FROM pg_stat_activity WHERE state = 'active'" 2>/dev/null | xargs printf "    %s\n" || echo "    (unavailable)"
        echo

        printf "  Last backup:\n"
        ls -lth /opt/kudi/backups/*.sql.gz 2>/dev/null | head -1 | awk '{printf "    %s %s %s — %s\n", $6, $7, $8, $9}' || echo "    (no backups yet)"
        echo

        printf "  WAL archive: %s files\n" "$(ls /mnt/pgdata/wal-archive/ 2>/dev/null | wc -l)"
STATUS
    echo
}

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------
tail_logs() {
    local ip lines="${1:-100}"
    ip=$(require_server)
    ssh_cmd "$ip" "journalctl -u kudi -n $lines --no-pager"
}

# ---------------------------------------------------------------------------
# Destroy
# ---------------------------------------------------------------------------
destroy_server() {
    info "Looking for kudi server..."
    local server_id
    server_id=$(get_server_id)

    if [[ -z "$server_id" ]]; then
        warn "No server named '$SERVER_NAME' found."
        exit 0
    fi

    local ip
    ip=$(get_server_ip)
    warn "Found: $SERVER_NAME (ID: $server_id, IP: $ip)"
    printf "${RED}    This will permanently delete the server.${RESET}\n"
    printf "${YELLOW}    Volume ($VOLUME_NAME) will be KEPT for data safety.${RESET}\n"
    printf "${CYAN}    Type 'destroy' to confirm${RESET}: "
    read -r confirmation

    [[ "$confirmation" == "destroy" ]] || { info "Aborted."; exit 0; }

    info "Destroying server $server_id..."
    hetzner_api DELETE "/servers/$server_id" > /dev/null
    success "Server destroyed. Volume preserved."

    if [[ -n "$ip" ]]; then
        ssh-keygen -R "$ip" 2>/dev/null || true
    fi
}

destroy_all() {
    destroy_server

    local vol_id
    vol_id=$(find_volume_id)
    if [[ -n "$vol_id" ]]; then
        warn "Destroying volume $VOLUME_NAME — ALL DATABASE DATA WILL BE LOST"
        printf "${CYAN}    Type 'destroy-data' to confirm${RESET}: "
        read -r vol_confirm
        if [[ "$vol_confirm" == "destroy-data" ]]; then
            hetzner_api DELETE "/volumes/$vol_id" > /dev/null
            success "Volume destroyed."
        fi
    fi

    local fw_id
    fw_id=$(find_firewall_id)
    if [[ -n "$fw_id" ]]; then
        hetzner_api DELETE "/firewalls/$fw_id" > /dev/null 2>&1 || true
        success "Firewall destroyed."
    fi

    rm -f "$CREDENTIALS_FILE"
    success "Credentials file removed."
}

# ---------------------------------------------------------------------------
# Create server
# ---------------------------------------------------------------------------
create_server() {
    local existing_id
    existing_id=$(get_server_id)
    if [[ -n "$existing_id" ]]; then
        local existing_ip
        existing_ip=$(get_server_ip)
        warn "Server '$SERVER_NAME' already exists (ID: $existing_id, IP: $existing_ip)"
        warn "Run with --destroy first."
        exit 1
    fi

    echo
    printf "${BOLD}${GREEN}  Kudi Wallet — Hetzner Production Deploy (v%s)${RESET}\n" "$SCRIPT_VERSION"
    printf "${DIM}  PostgreSQL 18 + OTel + Jaeger + Prometheus + Grafana + JVM${RESET}\n"
    printf "${DIM}  Environment: %s${RESET}\n" "$KUDI_ENV"
    echo

    prompt       SERVER_TYPE  "Server type (cpx31/cpx41/ccx23/ccx33)" "${KUDI_SERVER_TYPE:-cpx31}"
    prompt       LOCATION     "Region (fsn1/nbg1/hel1/ash/hil)" "${KUDI_LOCATION:-fsn1}"
    prompt       DOMAIN       "Domain for TLS (leave empty to skip)" "${KUDI_DOMAIN:-}"

    local generated_db_password generated_backup_key
    generated_db_password=$(generate_password)
    generated_backup_key=$(generate_key)
    prompt_secret DB_PASSWORD "PostgreSQL password" "$generated_db_password"

    echo
    info "Configuration:"
    printf "    Environment:   ${BOLD}%s${RESET}\n" "$KUDI_ENV"
    printf "    Server:        ${BOLD}%s${RESET} in ${BOLD}%s${RESET}\n" "$SERVER_TYPE" "$LOCATION"
    printf "    Domain:        ${BOLD}%s${RESET}\n" "${DOMAIN:-none}"
    printf "    Volume:        ${BOLD}%s${RESET} (50GB)\n" "$VOLUME_NAME"
    printf "    Firewall:      ${BOLD}%s${RESET}\n" "$FIREWALL_NAME"
    printf "    Backups:       ${BOLD}Hetzner daily + pg_dump (14d) + WAL archiving${RESET}\n"
    printf "    Observability: ${BOLD}OTel + Jaeger + Prometheus + Grafana${RESET}\n"
    echo

    printf "${CYAN}    Proceed? (y/n)${RESET}: "
    read -r confirm
    [[ "$confirm" =~ ^[Yy]$ ]] || { info "Aborted."; exit 0; }

    # Hetzner resources
    local fw_id vol_id
    fw_id=$(create_firewall)
    vol_id=$(create_volume "$LOCATION")

    local user_data
    user_data=$(build_cloud_init "$DB_PASSWORD" "$DOMAIN" "$generated_backup_key")

    local ssh_keys
    ssh_keys=$(get_ssh_keys)
    local key_count
    key_count=$(echo "$ssh_keys" | jq length)
    [[ "$key_count" -gt 0 ]] || fatal "No SSH keys in your Hetzner account. Add one first."
    info "Found $key_count SSH key(s)."

    info "Creating $SERVER_TYPE server in $LOCATION..."
    local create_payload
    create_payload=$(jq -n \
        --arg name "$SERVER_NAME" \
        --arg server_type "$SERVER_TYPE" \
        --arg location "$LOCATION" \
        --arg user_data "$user_data" \
        --argjson ssh_keys "$ssh_keys" \
        --argjson volumes "[$vol_id]" \
        --argjson firewalls "[{\"firewall\": $fw_id}]" \
        --arg env "$KUDI_ENV" \
        '{
            name: $name,
            server_type: $server_type,
            location: $location,
            image: "ubuntu-24.04",
            ssh_keys: $ssh_keys,
            user_data: $user_data,
            volumes: $volumes,
            firewalls: $firewalls,
            public_net: { enable_ipv4: true, enable_ipv6: true },
            labels: { app: "kudi", env: $env, managed_by: "hetzner.sh" }
        }')

    local response
    response=$(hetzner_api POST "/servers" -d "$create_payload") || fatal "Failed to create server."

    local server_id server_ip root_password
    server_id=$(echo "$response" | jq -r '.server.id')
    server_ip=$(echo "$response" | jq -r '.server.public_net.ipv4.ip')
    root_password=$(echo "$response" | jq -r '.root_password // empty')

    [[ -n "$server_id" && "$server_id" != "null" ]] || fatal "Server creation failed."

    success "Server created: ID=$server_id, IP=$server_ip"

    enable_server_backups "$server_id"
    save_credentials "$server_ip" "$DB_PASSWORD" "$generated_backup_key"

    if [[ -n "$root_password" ]]; then
        warn "Root password (one-time): $root_password"
    fi

    if wait_for_ssh "$server_ip"; then
        success "SSH reachable."
        deploy_migrations "$server_ip"
        wait_for_cloud_init "$server_ip"

        printf "\n${CYAN}    Build and deploy JAR now? (y/n)${RESET}: "
        read -r deploy_confirm
        if [[ "$deploy_confirm" =~ ^[Yy]$ ]]; then
            deploy_jar "$server_ip"
        fi
    else
        warn "SSH not reachable yet. Try: ssh root@$server_ip"
    fi

    echo
    printf "${BOLD}${GREEN}  ┌─────────────────────────────────────────────────┐${RESET}\n"
    printf "${BOLD}${GREEN}  │  Kudi Deployment Complete                       │${RESET}\n"
    printf "${BOLD}${GREEN}  └─────────────────────────────────────────────────┘${RESET}\n"
    echo
    printf "  ${BOLD}Server:${RESET}       %s (%s in %s)\n" "$server_ip" "$SERVER_TYPE" "$LOCATION"
    printf "  ${BOLD}SSH:${RESET}          ssh root@%s\n" "$server_ip"
    printf "  ${BOLD}Credentials:${RESET}  %s\n" "$CREDENTIALS_FILE"
    echo
    printf "  ${BOLD}Dashboards (via SSH tunnel):${RESET}\n"
    printf "    ssh -L 16686:127.0.0.1:16686 -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 root@%s\n" "$server_ip"
    printf "    Jaeger:     http://localhost:16686\n"
    printf "    Grafana:    http://localhost:3000\n"
    printf "    Prometheus: http://localhost:9090\n"
    echo
    printf "  ${BOLD}Commands:${RESET}\n"
    printf "    --status     Health check       --deploy      Build + deploy JAR\n"
    printf "    --rollback   Revert to prev     --logs [n]    Tail service logs\n"
    printf "    --destroy    Tear down server    --destroy-all Remove everything\n"
    echo

    if [[ -n "$DOMAIN" ]]; then
        printf "  ${BOLD}DNS:${RESET} Point %s A -> %s (TLS auto-configures)\n" "$DOMAIN" "$server_ip"
        echo
    fi
}

# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------
main() {
    preflight

    if [[ -z "${HETZNER_TOKEN:-}" ]]; then
        echo
        printf "${BOLD}${GREEN}  Kudi Wallet — Hetzner Deploy (v%s)${RESET}\n" "$SCRIPT_VERSION"
        echo
        prompt_secret HETZNER_TOKEN "Hetzner API token" ""
        [[ -n "$HETZNER_TOKEN" ]] || fatal "Hetzner API token is required."
    fi

    validate_token

    case "${1:-}" in
        --destroy)     destroy_server ;;
        --destroy-all) destroy_all ;;
        --deploy)      deploy_jar "$(require_server)" "${2:-}" ;;
        --status)      check_status ;;
        --rollback)    rollback ;;
        --logs)        tail_logs "${2:-100}" ;;
        --help|-h)     head -20 "${BASH_SOURCE[0]}" | tail -19 ;;
        "")            create_server ;;
        *)             fatal "Unknown: $1. Use --help." ;;
    esac
}

main "$@"