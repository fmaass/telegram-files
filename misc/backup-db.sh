#!/bin/sh
# Backup the telegram-files PostgreSQL database.
#
# Uses a postgres:17 client image to dump the database (the cleanup sidecar
# runs pg_dump v16 which refuses the v17 server). Output is pg_dump custom
# format (-Fc). Backups older than 14 days are pruned.
#
# Usage: ./misc/backup-db.sh

set -e

BACKUP_DIR="${HOME}/backups/telegram-files"
TIMESTAMP=$(date +%Y-%m-%d_%H%M%S)
OUTFILE="${BACKUP_DIR}/telegram_files_mac-${TIMESTAMP}.dump"

mkdir -p "${BACKUP_DIR}"

echo "Starting backup to ${OUTFILE}..."

docker run --rm \
    --env-file <(docker exec telegram-files-cleanup sh -c 'env | grep -E "^PG(HOST|PORT|USER|PASSWORD|DATABASE)="') \
    postgres:17-alpine \
    pg_dump -Fc > "${OUTFILE}"

echo "Verifying backup..."
pg_restore -l "${OUTFILE}" > /dev/null

echo "Pruning backups older than 14 days..."
find "${BACKUP_DIR}" -name "*.dump" -mtime +14 -delete

echo "Backup complete: ${OUTFILE} ($(du -h "${OUTFILE}" | cut -f1))"
