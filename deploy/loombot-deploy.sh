#!/usr/bin/env bash
#
# LoomBot pull 模式部署脚本。
#
# 由 systemd timer 每分钟触发一次：读 R2 上的 current.json 发布指针，
# sha 与上次部署不同才真正部署。整个流程没有任何外部机器登录本服务器，
# 所以不需要在 GitHub 上保存 SSH 私钥。
#
# 安装位置：/usr/local/bin/loombot-deploy.sh（本文件是仓库里的版本化副本，改动后需同步）

set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

APP_DIR=/opt/loombot
STATE_DIR="$APP_DIR/state"
DEPLOYED_SHA_FILE="$STATE_DIR/deployed-sha"
LOG_FILE="$APP_DIR/deploy.log"
LOCK_FILE="$STATE_DIR/deploy.lock"
RCLONE_CONF=/etc/loombot/rclone.conf
BUCKET="r2:loombot-deploy"
KEEP_RELEASES=5

mkdir -p "$STATE_DIR"
touch "$LOG_FILE"

log() { printf '%s %s\n' "$(date -Is)" "$*" >> "$LOG_FILE"; }

# 同一时间只允许一个部署在跑：flock 拿不到锁就直接退出，下一分钟再来。
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    exit 0
fi

POINTER=$(rclone --config "$RCLONE_CONF" cat "$BUCKET/current.json" 2>/dev/null || true)
if [ -z "$POINTER" ]; then
    exit 0
fi

SHA=$(printf '%s' "$POINTER" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("sha",""))' 2>/dev/null || true)
if [ -z "$SHA" ]; then
    log "current.json 解析失败: $POINTER"
    exit 0
fi

DEPLOYED=$(cat "$DEPLOYED_SHA_FILE" 2>/dev/null || true)
if [ "$SHA" = "$DEPLOYED" ]; then
    exit 0
fi

log "发现新版本 $SHA（当前 ${DEPLOYED:-无}），开始部署"

SHORT=${SHA:0:7}
TS=$(date +%Y%m%d%H%M%S)
REL="$APP_DIR/releases/$TS-$SHORT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

if ! rclone --config "$RCLONE_CONF" copyto "$BUCKET/releases/$SHA/backend.tar.gz" "$TMP/backend.tar.gz" ||
    ! rclone --config "$RCLONE_CONF" copyto "$BUCKET/releases/$SHA/frontend.tar.gz" "$TMP/frontend.tar.gz"; then
    log "下载产物失败: $SHA"
    exit 1
fi

# 新版本先落到独立目录，健康检查失败时旧版本仍然在。
mkdir -p "$REL/frontend"
tar -xzf "$TMP/backend.tar.gz" -C "$REL"
tar -xzf "$TMP/frontend.tar.gz" -C "$REL/frontend"
ln -sfn "$APP_DIR/logs" "$REL/logs"
echo "$SHA" >"$REL/RELEASE"
chown -R loombot:loombot "$REL"

PREV=$(readlink -f "$APP_DIR/current" || true)
ln -sfn "$REL" "$APP_DIR/current"
systemctl restart loombot

ok=0
for _ in $(seq 1 40); do
    if curl -fsS -m 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        ok=1
        break
    fi
    sleep 3
done

if [ "$ok" != "1" ]; then
    log "健康检查失败，回滚到 ${PREV:-无}"
    if [ -n "$PREV" ]; then
        ln -sfn "$PREV" "$APP_DIR/current"
        systemctl restart loombot
    fi
    exit 1
fi

nginx -t && systemctl reload nginx

echo "$SHA" >"$DEPLOYED_SHA_FILE"

# release 目录名以时间戳开头，按名称倒序就是按时间倒序。
cd "$APP_DIR/releases"
ls -1d */ 2>/dev/null | sort -r | tail -n +$((KEEP_RELEASES + 1)) | xargs -r rm -rf --

log "部署完成: $REL"
