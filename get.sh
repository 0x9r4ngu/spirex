#!/usr/bin/env bash
#
# spirex web installer — download the latest release jar and install a launcher.
# No git clone, no build step, no root: just a JDK 21+ to run it.
#
#   curl -fsSL https://raw.githubusercontent.com/0x9r4ngu/spirex/main/get.sh | bash
#
# Installs into ~/.local by default (no sudo, and `spirex --update` stays
# sudo-free too). Override the location with PREFIX:
#
#   curl -fsSL .../get.sh | PREFIX=/usr/local sudo bash   # system-wide
#
set -euo pipefail

APP="spirex"
REPO="0x9r4ngu/spirex"
PREFIX="${PREFIX:-$HOME/.local}"
LIBDIR="$PREFIX/share/$APP"
BINDIR="$PREFIX/bin"
JAR_URL="https://github.com/$REPO/releases/latest/download/$APP.jar"

# ---- pretty output -------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    RST=$'\033[0m'; B=$'\033[1m'; DIM=$'\033[2m'
    CY=$'\033[36m'; GN=$'\033[32m'; YL=$'\033[33m'; RD=$'\033[31m'; MG=$'\033[35m'
else
    RST=''; B=''; DIM=''; CY=''; GN=''; YL=''; RD=''; MG=''
fi

rule()  { printf '   %s%s%s\n' "$DIM" "────────────────────────────────────────────────" "$RST"; }
step()  { printf '   %s%s%s  %s%-9s%s %s\n' "$CY" "▸" "$RST" "$B" "$1" "$RST" "$2"; }
done_() { printf '   %s%s%s  %s%-9s%s %s%s%s\n' "$GN" "✓" "$RST" "$B" "$1" "$RST" "$DIM" "$2" "$RST"; }
warn()  { printf '   %s!%s  %s\n' "$YL" "$RST" "$*" >&2; }
die()   { printf '   %s✗%s  %s\n' "$RD" "$RST" "$*" >&2; exit 1; }

banner() {
    printf '\n%s%s' "$CY" "$B"
    cat <<'ART'
              _
    ___ _ __ (_)_ __ _____  __
   / __| '_ \| | '__/ _ \ \/ /
   \__ \ |_) | | | |  __/>  <
   |___/ .__/|_|_|  \___/_/\_\
       |_|
ART
    printf '%s   %sweb crawler · installer%s\n\n' "$RST" "$DIM" "$RST"
    rule
}

human() {  # bytes -> "57 KB"
    local b="$1"
    if [ "$b" -ge 1048576 ]; then printf '%d.%d MB' "$((b/1048576))" "$(((b%1048576)*10/1048576))"
    elif [ "$b" -ge 1024 ]; then printf '%d KB' "$(((b+512)/1024))"
    else printf '%d B' "$b"; fi
}

banner

# Friendly nudge: this build wants a user-local, sudo-free install.
if [ "$(id -u)" -eq 0 ] && [ -z "${PREFIX_OVERRIDE:-}" ] && [ "${PREFIX}" = "/root/.local" ]; then
    warn "running as root — installing into /root/.local."
    warn "for a per-user install, re-run WITHOUT sudo."
fi

# 1. Java present? Detect it (incl. the invoking user's env under sudo), and
#    auto-install a JDK only if it genuinely isn't there.
install_jdk() {
    local SUDO=""
    if [ "$(id -u)" -ne 0 ]; then
        command -v sudo >/dev/null 2>&1 && SUDO="sudo" || return 1
    fi
    export DEBIAN_FRONTEND=noninteractive
    if   command -v apt-get >/dev/null 2>&1; then
        $SUDO apt-get update -qq || true
        $SUDO apt-get install -y openjdk-21-jre-headless \
            || $SUDO apt-get install -y default-jre \
            || $SUDO apt-get install -y default-jdk
    elif command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y java-21-openjdk-headless \
            || $SUDO dnf install -y java-latest-openjdk-headless
    elif command -v yum >/dev/null 2>&1; then
        $SUDO yum install -y java-21-openjdk
    elif command -v pacman >/dev/null 2>&1; then
        $SUDO pacman -Sy --noconfirm jre-openjdk
    elif command -v zypper >/dev/null 2>&1; then
        $SUDO zypper --non-interactive install java-21-openjdk
    elif command -v apk >/dev/null 2>&1; then
        $SUDO apk add --no-cache openjdk21-jre
    elif command -v brew >/dev/null 2>&1; then
        [ "$(id -u)" -eq 0 ] && return 1   # Homebrew refuses to run as root
        brew install openjdk
    else
        return 1
    fi
}

USER_JAVA=""
if [ -n "${SUDO_USER:-}" ] && [ "${SUDO_USER}" != "root" ]; then
    USER_JAVA="$(sudo -u "$SUDO_USER" sh -lc 'command -v java' 2>/dev/null || true)"
fi

if command -v java >/dev/null 2>&1; then
    done_ "java" "$(java -version 2>&1 | head -1 | sed 's/"//g')"
elif [ -n "$USER_JAVA" ]; then
    done_ "java" "$("$USER_JAVA" -version 2>&1 | head -1 | sed 's/"//g')"
else
    step "java" "not found — installing a JDK (this may take a minute) ..."
    if install_jdk && { hash -r 2>/dev/null; command -v java >/dev/null 2>&1; }; then
        done_ "java" "$(java -version 2>&1 | head -1 | sed 's/"//g')"
    else
        warn "couldn't install Java automatically. Install a JDK 21+ and re-run:"
        warn "  Debian/Kali/Ubuntu:  sudo apt install default-jdk"
        warn "  Fedora/RHEL:         sudo dnf install java-21-openjdk"
        warn "  Arch:                sudo pacman -S jre-openjdk"
        warn "  macOS (brew):        brew install openjdk"
        exit 1
    fi
fi

# 2. A downloader (curl or wget).
if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL "$1" -o "$2"; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -qO "$2" "$1"; }
else
    die "need curl or wget to download the release"
fi

# 3. Download the latest release jar.
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
if ! fetch "$JAR_URL" "$TMP" || [ ! -s "$TMP" ]; then
    die "download failed — no release jar at $JAR_URL yet?"
fi
done_ "download" "$APP.jar · $(human "$(wc -c < "$TMP")")"

# 4. Install jar + launcher (no root needed for ~/.local).
if ! mkdir -p "$LIBDIR" "$BINDIR" 2>/dev/null; then
    die "cannot write to $PREFIX — set PREFIX=\$HOME/.local, or re-run with sudo"
fi
install -m 0644 "$TMP" "$LIBDIR/$APP.jar"

cat > "$BINDIR/$APP" <<EOF
#!/usr/bin/env bash
# spirex launcher (generated by get.sh)
exec java -jar "$LIBDIR/$APP.jar" "\$@"
EOF
chmod 0755 "$BINDIR/$APP"
done_ "install" "${BINDIR/#$HOME/~}/$APP"

# 5. Make sure BINDIR is reachable; add it to the right shell rc if not.
PATH_HINT=""
case ":$PATH:" in
    *":$BINDIR:"*)
        done_ "path" "${BINDIR/#$HOME/~} already on PATH" ;;
    *)
        case "${SHELL:-}" in
            */zsh)  RC="$HOME/.zshrc" ;;
            */bash) RC="$HOME/.bashrc" ;;
            *)      RC="$HOME/.profile" ;;
        esac
        LINE="export PATH=\"$BINDIR:\$PATH\""
        mkdir -p "$(dirname "$RC")" 2>/dev/null || true
        if grep -qsF "$LINE" "$RC" 2>/dev/null; then
            done_ "path" "already in ${RC/#$HOME/~}"
            PATH_HINT="source ${RC/#$HOME/~}"
        elif printf '\n# added by spirex installer\n%s\n' "$LINE" >> "$RC" 2>/dev/null; then
            done_ "path" "added to ${RC/#$HOME/~}"
            PATH_HINT="source ${RC/#$HOME/~}"
        else
            warn "add ${BINDIR/#$HOME/~} to your PATH to run '$APP' anywhere"
        fi
        ;;
esac

# 6. Pretty summary.
VER="$("$BINDIR/$APP" -V 2>/dev/null | awk '{print $2}')"
rule
printf '\n'
printf '   %s%s✓ spirex %s installed%s\n' "$GN" "$B" "${VER:-}" "$RST"
printf '\n'
if [ -n "$PATH_HINT" ]; then
    printf '     %sreload PATH%s   %s%s%s\n' "$DIM" "$RST" "$CY" "$PATH_HINT" "$RST"
fi
printf '     %sget started%s  %s%s --help%s\n' "$DIM" "$RST" "$CY" "$APP" "$RST"
printf '\n'
