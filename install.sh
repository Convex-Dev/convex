#!/usr/bin/env bash
#
# Convex installer — downloads convex.jar and creates a wrapper command.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Convex-Dev/convex/develop/install.sh | bash
#
# Options (environment variables):
#   CONVEX_VERSION=0.8.3    Install a specific version (default: latest)
#   CONVEX_HOME=~/.convex   Installation directory (default: ~/.convex)

set -euo pipefail

CONVEX_HOME="${CONVEX_HOME:-$HOME/.convex}"
CONVEX_JAR="$CONVEX_HOME/convex.jar"
MIN_JAVA_VERSION=21
REPO="Convex-Dev/convex"

# ── Helpers ──────────────────────────────────────────────

info()  { printf '  %s\n' "$*"; }
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn()  { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail()  { printf '  \033[31m✗\033[0m %s\n' "$*"; exit 1; }

# ── Check Java ───────────────────────────────────────────

check_java() {
    if ! command -v java &>/dev/null; then
        fail "Java not found. Please install Java $MIN_JAVA_VERSION or later."
    fi

    # Parse version from "java -version" (works with Oracle, OpenJDK, Temurin, etc.)
    local version
    version=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')

    if [ -z "$version" ] || [ "$version" -lt "$MIN_JAVA_VERSION" ] 2>/dev/null; then
        fail "Java $MIN_JAVA_VERSION+ required, found Java $version."
    fi

    ok "Java $version"
}

# ── Determine download URL ───────────────────────────────

get_download_url() {
    if [ -n "${CONVEX_VERSION:-}" ]; then
        echo "https://github.com/$REPO/releases/download/$CONVEX_VERSION/convex.jar"
    else
        echo "https://github.com/$REPO/releases/latest/download/convex.jar"
    fi
}

# ── Download ─────────────────────────────────────────────

download() {
    local url
    url=$(get_download_url)

    mkdir -p "$CONVEX_HOME"

    info "Downloading from $url ..."

    if command -v curl &>/dev/null; then
        curl -fSL --progress-bar -o "$CONVEX_JAR" "$url"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress -O "$CONVEX_JAR" "$url"
    else
        fail "Neither curl nor wget found. Please install one."
    fi

    ok "Downloaded convex.jar"
}

# ── Create wrapper script ────────────────────────────────

install_wrapper() {
    # Find a writable directory on PATH
    local bin_dir=""
    for dir in "$HOME/.local/bin" "$HOME/bin" "/usr/local/bin"; do
        if [ -d "$dir" ] && echo "$PATH" | grep -q "$dir"; then
            bin_dir="$dir"
            break
        fi
    done

    # Fall back to ~/.local/bin and create it
    if [ -z "$bin_dir" ]; then
        bin_dir="$HOME/.local/bin"
        mkdir -p "$bin_dir"
    fi

    local wrapper="$bin_dir/convex"

    cat > "$wrapper" <<SCRIPT
#!/usr/bin/env bash
exec java -jar "$CONVEX_JAR" "\$@"
SCRIPT
    chmod +x "$wrapper"

    ok "Installed 'convex' command to $wrapper"

    # Check if bin_dir is on PATH
    if ! echo "$PATH" | grep -q "$bin_dir"; then
        warn "$bin_dir is not on your PATH. Add it with:"
        info "  export PATH=\"$bin_dir:\$PATH\""
    fi
}

# ── Verify ───────────────────────────────────────────────

verify() {
    if [ -f "$CONVEX_JAR" ]; then
        ok "Installed to $CONVEX_JAR"
    else
        fail "Installation failed — convex.jar not found."
    fi
}

# ── Main ─────────────────────────────────────────────────

main() {
    echo ""
    echo "  Convex Installer"
    echo "  ─────────────────"
    echo ""

    check_java
    download
    install_wrapper
    verify

    echo ""
    info "Run 'convex --help' to get started."
    echo ""
}

main
