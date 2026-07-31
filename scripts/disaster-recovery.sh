#!/usr/bin/env bash
#
# Wyrdsekai Disaster Recovery
#
# Usage:
#   ./disaster-recovery.sh backup              — Create a snapshot now
#   ./disaster-recovery.sh list                — List available snapshots
#   ./disaster-recovery.sh restore [snapshot]  — Restore from snapshot (latest if omitted)
#   ./disaster-recovery.sh verify [snapshot]   — Verify a snapshot's integrity
#   ./disaster-recovery.sh export [dir]        — Full export (DB + config + scripts + souls)
#   ./disaster-recovery.sh import <archive>    — Import from a full export
#
# Environment:
#   WYRDSEKAI_DATA_DIR  — Data directory (default: ~/.wyrdsekai)
#   WYRDSEKAI_DB_PATH   — Database path (default: $WYRDSEKAI_DATA_DIR/world.db)
#

set -euo pipefail

# --- Configuration ---

DATA_DIR="${WYRDSEKAI_DATA_DIR:-$HOME/.wyrdsekai}"
DB_PATH="${WYRDSEKAI_DB_PATH:-$DATA_DIR/world.db}"
BACKUP_DIR="$DATA_DIR/backups"
SCRIPTS_DIR="$DATA_DIR/scripts"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# --- Commands ---

cmd_backup() {
    if [ ! -f "$DB_PATH" ]; then
        error "Database not found: $DB_PATH"
        exit 1
    fi

    mkdir -p "$BACKUP_DIR"
    local backup_file="$BACKUP_DIR/world.db.${TIMESTAMP}.bak"

    # Use sqlite3 .backup if available (safe even while server is running)
    if command -v sqlite3 &>/dev/null; then
        info "Creating backup via sqlite3 .backup (online-safe)..."
        sqlite3 "$DB_PATH" ".backup '$backup_file'"
    else
        # File copy — safe if server is idle or using WAL mode
        warn "sqlite3 not found, using file copy (stop server first for consistency)"
        cp "$DB_PATH" "$backup_file"
    fi

    local size
    size=$(stat -c%s "$backup_file" 2>/dev/null || stat -f%z "$backup_file" 2>/dev/null)
    info "Backup created: $backup_file ($(numfmt --to=iec "$size" 2>/dev/null || echo "${size} bytes"))"

    # Prune old backups (keep 10)
    local count
    count=$(ls -1 "$BACKUP_DIR"/*.bak 2>/dev/null | wc -l)
    if [ "$count" -gt 10 ]; then
        local to_remove=$((count - 10))
        ls -1t "$BACKUP_DIR"/*.bak | tail -n "$to_remove" | while read -r f; do
            rm -f "$f"
            info "Pruned old backup: $(basename "$f")"
        done
    fi
}

cmd_list() {
    if [ ! -d "$BACKUP_DIR" ]; then
        warn "No backups directory: $BACKUP_DIR"
        exit 0
    fi

    echo "Available backups (newest first):"
    echo "---"
    ls -lht "$BACKUP_DIR"/*.bak 2>/dev/null | awk '{printf "  %-40s %s %s %s\n", $NF, $5, $6, $7}' || echo "  (none)"
    echo ""

    local count
    count=$(ls -1 "$BACKUP_DIR"/*.bak 2>/dev/null | wc -l)
    echo "Total: $count snapshot(s)"
    echo "Database: $DB_PATH"
    if [ -f "$DB_PATH" ]; then
        local db_size
        db_size=$(stat -c%s "$DB_PATH" 2>/dev/null || stat -f%z "$DB_PATH" 2>/dev/null)
        echo "Database size: $(numfmt --to=iec "$db_size" 2>/dev/null || echo "${db_size} bytes")"
    fi
}

cmd_restore() {
    local backup_file="$1"

    if [ -z "$backup_file" ]; then
        # Use latest
        backup_file=$(ls -1t "$BACKUP_DIR"/*.bak 2>/dev/null | head -1)
        if [ -z "$backup_file" ]; then
            error "No backups found in $BACKUP_DIR"
            exit 1
        fi
        info "Using latest backup: $(basename "$backup_file")"
    fi

    if [ ! -f "$backup_file" ]; then
        # Try relative to backup dir
        if [ -f "$BACKUP_DIR/$backup_file" ]; then
            backup_file="$BACKUP_DIR/$backup_file"
        else
            error "Backup file not found: $backup_file"
            exit 1
        fi
    fi

    # Safety: check if server is running
    if pgrep -f "wyrdsekai" &>/dev/null || pgrep -f "org.wyrdsekai.server.Main" &>/dev/null; then
        error "Server appears to be running. Stop it first:"
        error "  systemctl --user stop wyrdsekai"
        error "  # or: kill \$(pgrep -f wyrdsekai)"
        exit 1
    fi

    # Verify backup integrity
    if command -v sqlite3 &>/dev/null; then
        info "Verifying backup integrity..."
        local result
        result=$(sqlite3 "$backup_file" "PRAGMA integrity_check;" 2>&1)
        if [ "$result" != "ok" ]; then
            error "Backup integrity check FAILED: $result"
            error "This backup may be corrupted. Aborting."
            exit 1
        fi
        info "Integrity check passed."
    fi

    # Create a backup of the current DB before overwriting
    if [ -f "$DB_PATH" ]; then
        local safety_backup="$DB_PATH.pre-restore.${TIMESTAMP}.bak"
        cp "$DB_PATH" "$safety_backup"
        info "Current database backed up to: $safety_backup"
    fi

    # Restore
    cp "$backup_file" "$DB_PATH"
    info "Database restored from: $(basename "$backup_file")"
    info "Start the server to verify: ./scripts/run-solo.sh"
}

cmd_verify() {
    local backup_file="$1"

    if [ -z "$backup_file" ]; then
        backup_file=$(ls -1t "$BACKUP_DIR"/*.bak 2>/dev/null | head -1)
        if [ -z "$backup_file" ]; then
            error "No backups found"
            exit 1
        fi
    fi

    if [ ! -f "$backup_file" ]; then
        if [ -f "$BACKUP_DIR/$backup_file" ]; then
            backup_file="$BACKUP_DIR/$backup_file"
        else
            error "File not found: $backup_file"
            exit 1
        fi
    fi

    if ! command -v sqlite3 &>/dev/null; then
        error "sqlite3 required for verification"
        exit 1
    fi

    info "Verifying: $(basename "$backup_file")"

    # Integrity check
    local integrity
    integrity=$(sqlite3 "$backup_file" "PRAGMA integrity_check;" 2>&1)
    if [ "$integrity" = "ok" ]; then
        info "  Integrity: PASS"
    else
        error "  Integrity: FAIL — $integrity"
        exit 1
    fi

    # Table count
    local tables
    tables=$(sqlite3 "$backup_file" "SELECT count(*) FROM sqlite_master WHERE type='table';" 2>&1)
    info "  Tables: $tables"

    # Row counts for key tables
    for table in journal snapshot_store auth_users soul_manifests; do
        local count
        count=$(sqlite3 "$backup_file" "SELECT count(*) FROM $table;" 2>/dev/null || echo "N/A")
        info "  $table: $count rows"
    done

    local size
    size=$(stat -c%s "$backup_file" 2>/dev/null || stat -f%z "$backup_file" 2>/dev/null)
    info "  Size: $(numfmt --to=iec "$size" 2>/dev/null || echo "${size} bytes")"
    info "Verification complete."
}

cmd_export() {
    local export_dir="${1:-$DATA_DIR/exports}"
    local archive="$export_dir/wyrdsekai-export-${TIMESTAMP}.tar.gz"
    local staging="$export_dir/.staging-${TIMESTAMP}"

    mkdir -p "$staging"

    info "Exporting Wyrdsekai data..."

    # Database snapshot
    if [ -f "$DB_PATH" ]; then
        if command -v sqlite3 &>/dev/null; then
            sqlite3 "$DB_PATH" ".backup '$staging/world.db'"
        else
            cp "$DB_PATH" "$staging/world.db"
        fi
        info "  Database: copied"
    fi

    # User scripts
    if [ -d "$SCRIPTS_DIR" ]; then
        cp -r "$SCRIPTS_DIR" "$staging/scripts"
        info "  Scripts: copied"
    fi

    # Config (if custom)
    local config_files=("$DATA_DIR/application.conf" "$DATA_DIR/logback.xml")
    for cf in "${config_files[@]}"; do
        if [ -f "$cf" ]; then
            cp "$cf" "$staging/"
            info "  Config: $(basename "$cf") copied"
        fi
    done

    # Manifest
    cat > "$staging/MANIFEST.txt" <<EOF
Wyrdsekai Export
Timestamp: $(date -Iseconds)
Hostname: $(hostname)
Database: $(stat -c%s "$DB_PATH" 2>/dev/null || stat -f%z "$DB_PATH" 2>/dev/null || echo "unknown") bytes
Version: 0.1.0-SNAPSHOT
EOF

    # Create archive
    mkdir -p "$export_dir"
    tar -czf "$archive" -C "$staging" .
    rm -rf "$staging"

    local archive_size
    archive_size=$(stat -c%s "$archive" 2>/dev/null || stat -f%z "$archive" 2>/dev/null)
    info "Export complete: $archive ($(numfmt --to=iec "$archive_size" 2>/dev/null || echo "${archive_size} bytes"))"
    info "To restore on another machine: $0 import $archive"
}

cmd_import() {
    local archive="$1"

    if [ -z "$archive" ] || [ ! -f "$archive" ]; then
        error "Usage: $0 import <archive.tar.gz>"
        exit 1
    fi

    # Safety: check if server is running
    if pgrep -f "wyrdsekai" &>/dev/null || pgrep -f "org.wyrdsekai.server.Main" &>/dev/null; then
        error "Server appears to be running. Stop it first."
        exit 1
    fi

    local staging="$DATA_DIR/.import-staging-${TIMESTAMP}"
    mkdir -p "$staging"
    tar -xzf "$archive" -C "$staging"

    if [ -f "$staging/MANIFEST.txt" ]; then
        info "Import manifest:"
        cat "$staging/MANIFEST.txt" | sed 's/^/  /'
    fi

    # Back up current state
    if [ -f "$DB_PATH" ]; then
        local safety="$DB_PATH.pre-import.${TIMESTAMP}.bak"
        cp "$DB_PATH" "$safety"
        info "Current database backed up to: $safety"
    fi

    # Restore database
    if [ -f "$staging/world.db" ]; then
        cp "$staging/world.db" "$DB_PATH"
        info "Database restored"
    fi

    # Restore scripts
    if [ -d "$staging/scripts" ]; then
        mkdir -p "$SCRIPTS_DIR"
        cp -r "$staging/scripts/"* "$SCRIPTS_DIR/"
        info "Scripts restored"
    fi

    # Restore config
    for cf in application.conf logback.xml; do
        if [ -f "$staging/$cf" ]; then
            cp "$staging/$cf" "$DATA_DIR/$cf"
            info "Config restored: $cf"
        fi
    done

    rm -rf "$staging"
    info "Import complete. Start the server to verify."
}

# --- Main ---

case "${1:-help}" in
    backup)
        cmd_backup
        ;;
    list)
        cmd_list
        ;;
    restore)
        cmd_restore "${2:-}"
        ;;
    verify)
        cmd_verify "${2:-}"
        ;;
    export)
        cmd_export "${2:-}"
        ;;
    import)
        cmd_import "${2:-}"
        ;;
    help|--help|-h)
        echo "Wyrdsekai Disaster Recovery"
        echo ""
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  backup              Create a database snapshot"
        echo "  list                List available snapshots"
        echo "  restore [snapshot]  Restore from snapshot (latest if omitted)"
        echo "  verify  [snapshot]  Verify snapshot integrity"
        echo "  export  [dir]       Full export (DB + config + scripts)"
        echo "  import  <archive>   Import from a full export archive"
        echo ""
        echo "Environment:"
        echo "  WYRDSEKAI_DATA_DIR  Data directory (default: ~/.wyrdsekai)"
        echo "  WYRDSEKAI_DB_PATH   Database path (default: \$DATA_DIR/world.db)"
        echo ""
        echo "Examples:"
        echo "  $0 backup                          # Snapshot the database"
        echo "  $0 restore                         # Restore from latest snapshot"
        echo "  $0 export ~/wyrdsekai-backup       # Full export to directory"
        echo "  $0 import wyrdsekai-export.tar.gz   # Import on a new machine"
        ;;
    *)
        error "Unknown command: $1"
        echo "Run '$0 help' for usage."
        exit 1
        ;;
esac
