#!/bin/sh
# scripts/oracle.sh -- the single resolution point for this repo's two reference
# oracles: aapt2 (Android) and xcstringstool (Xcode).
#
# Usage:
#   scripts/oracle.sh aapt2            print absolute path to aapt2, or fail
#   scripts/oracle.sh xcstringstool    print absolute path to xcstringstool, or fail
#   scripts/oracle.sh --check          validate both, print paths + versions,
#                                       exit non-zero if either is unavailable
#
# Why this exists: AGENTS.md records that a hand-copied aapt2 path
# (/private/tmp/claude-501/aapt2bin/aapt2) rotted silently -- it pointed at a
# sandbox temp directory macOS wipes -- and the rot then propagated into a
# GitHub issue and from there into a PR that shipped without ever consulting
# the oracle it named. This script is the fix for the *class* of defect: it
# is the only place either oracle path is computed, so nothing else should
# hardcode one again. See AGENTS.md's "Check the oracle exists before
# trusting a run" for the full rationale.
#
# Never prints a path that does not exist. Every failure names what was
# looked for, where, and the literal command to fix it, per AGENTS.md's
# Conventions section.

set -u

prog="oracle.sh"

# --- aapt2 -------------------------------------------------------------
#
# Prints the absolute path to aapt2 on stdout and returns 0 on success.
# On failure, prints a diagnostic to stderr and returns 1 -- it never exits
# the process directly, so callers (in particular --check) can attempt the
# other oracle too.
resolve_aapt2() {
    if [ -z "${ANDROID_HOME:-}" ]; then
        cat >&2 <<EOF
$prog aapt2: ANDROID_HOME is not set.
aapt2 resolves from \$ANDROID_HOME/build-tools/<version>/aapt2, and with no
ANDROID_HOME there is nothing to glob.
Fix: export ANDROID_HOME="\$HOME/Library/Android/sdk"   # or wherever your SDK lives
EOF
        return 1
    fi

    build_tools_dir="$ANDROID_HOME/build-tools"
    if [ ! -d "$build_tools_dir" ]; then
        cat >&2 <<EOF
$prog aapt2: no build-tools directory at $build_tools_dir (ANDROID_HOME=$ANDROID_HOME).
Fix: install build-tools with sdkmanager, e.g.:
  "\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;36.0.0"
EOF
        return 1
    fi

    # Glob every version directory that actually contains an executable
    # aapt2, and pick the highest version -- never hardcode a version number,
    # so this survives an SDK update. Compare numerically on the (up to)
    # three dot-separated fields of an Android build-tools version.
    highest_version=""
    for entry in "$build_tools_dir"/*/; do
        [ -d "$entry" ] || continue
        version=$(basename "$entry")
        candidate="$build_tools_dir/$version/aapt2"
        [ -x "$candidate" ] || continue
        if [ -z "$highest_version" ]; then
            highest_version="$version"
        else
            highest_version=$(printf '%s\n%s\n' "$version" "$highest_version" |
                sort -t. -k1,1n -k2,2n -k3,3n | tail -n1)
        fi
    done

    if [ -z "$highest_version" ]; then
        cat >&2 <<EOF
$prog aapt2: no aapt2 binary found under any version directory in $build_tools_dir.
Checked: $build_tools_dir/*/aapt2
Fix: install build-tools with sdkmanager, e.g.:
  "\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;36.0.0"
EOF
        return 1
    fi

    aapt2_path="$build_tools_dir/$highest_version/aapt2"

    # Defensive re-check: never print a path that does not exist, even if
    # something raced with disk state between the glob above and now.
    if [ ! -x "$aapt2_path" ]; then
        cat >&2 <<EOF
$prog aapt2: resolved $aapt2_path but it is no longer present/executable.
Fix: re-run this script, or reinstall build-tools $highest_version:
  "\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;$highest_version"
EOF
        return 1
    fi

    printf '%s\n' "$aapt2_path"
    return 0
}

# --- xcstringstool -------------------------------------------------------
#
# Same contract as resolve_aapt2: prints the path on success, diagnostic on
# stderr + return 1 on failure. xcstringstool is macOS-only by construction
# (it lives inside Xcode.app), so a non-Darwin host must fail with a message
# that says the *task* is macOS-only, not merely that a file is missing --
# that is the message PR #23's agent needed and did not get.
resolve_xcstringstool() {
    uname_s=$(uname -s)
    if [ "$uname_s" != "Darwin" ]; then
        cat >&2 <<EOF
$prog xcstringstool: this task is macOS-only, not just this one file.
xcstringstool lives inside Xcode.app (\$(xcode-select -p)/usr/bin/xcstringstool)
and is unreachable from any Linux container -- there is no package or binary
to install here that fixes it (detected uname -s: $uname_s).
Fix: run this task on a macOS host with Xcode installed.
EOF
        return 1
    fi

    if ! command -v xcode-select >/dev/null 2>&1; then
        cat >&2 <<EOF
$prog xcstringstool: xcode-select not found on this Darwin host.
Fix: xcode-select --install
EOF
        return 1
    fi

    dev_dir=$(xcode-select -p 2>/dev/null)
    if [ -z "$dev_dir" ]; then
        cat >&2 <<EOF
$prog xcstringstool: "xcode-select -p" returned no path.
Fix: xcode-select --install
  (or, if Xcode is installed but not selected: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer)
EOF
        return 1
    fi

    xcstringstool_path="$dev_dir/usr/bin/xcstringstool"
    if [ ! -x "$xcstringstool_path" ]; then
        cat >&2 <<EOF
$prog xcstringstool: not found at $xcstringstool_path (xcode-select -p reports $dev_dir).
xcstringstool ships inside a full Xcode.app, not the bare Command Line Tools.
Fix: install Xcode from the App Store, then:
  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
EOF
        return 1
    fi

    printf '%s\n' "$xcstringstool_path"
    return 0
}

# --- --check -------------------------------------------------------------
#
# Validates both oracles, printing path + version for each. Exits non-zero
# if either is unavailable -- a gate that finds nothing wrong must still
# prove it looked (AGENTS.md: "Gates that find nothing to check must fail
# loudly, not pass").
cmd_check() {
    status=0

    echo "== aapt2 =="
    if aapt2_path=$(resolve_aapt2); then
        if aapt2_version=$("$aapt2_path" version 2>&1); then
            :
        else
            aapt2_version="(failed to run '$aapt2_path version': $aapt2_version)"
            status=1
        fi
        echo "path:    $aapt2_path"
        echo "version: $aapt2_version"
    else
        status=1
    fi

    echo
    echo "== xcstringstool =="
    if xcstringstool_path=$(resolve_xcstringstool); then
        # xcstringstool has no --version/version subcommand (verified: `xcstringstool
        # version` errors "Unexpected argument"). Report the Xcode build that ships
        # it instead, via xcodebuild -version, so a vendor-version drift is still
        # visible at the moment of use -- docs/CONVERSIONS.md's aapt2 evidence was
        # gathered against 2.19 and this host runs 2.20; the equivalent risk applies
        # here too.
        if command -v xcodebuild >/dev/null 2>&1 && xcode_version=$(xcodebuild -version 2>&1); then
            xcode_version=$(printf '%s' "$xcode_version" | tr '\n' ' ')
        else
            xcode_version="(xcodebuild -version unavailable; xcstringstool exposes no version flag of its own)"
        fi
        echo "path:    $xcstringstool_path"
        echo "version: $xcode_version"
    else
        status=1
    fi

    return $status
}

usage() {
    cat >&2 <<EOF
usage: $prog aapt2 | xcstringstool | --check
EOF
}

main() {
    case "${1:-}" in
        aapt2)
            resolve_aapt2
            ;;
        xcstringstool)
            resolve_xcstringstool
            ;;
        --check)
            cmd_check
            ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
