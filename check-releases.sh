#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REPO="kushagrasinghx/BitChord"
FORK_REPO="Rishav-sy/BitChord"

# Resolve token: environment variable > gh auth token > empty
TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ] && command -v gh &>/dev/null; then
    TOKEN="$(gh auth token 2>/dev/null || true)"
fi

echo "=========================================================="
echo "         BitChord Release & Fork Version Tracker          "
echo "=========================================================="
echo "Upstream : https://github.com/$UPSTREAM_REPO"
echo "Fork     : https://github.com/$FORK_REPO"
echo "Current  : $(git branch --show-current 2>/dev/null || echo 'unknown') ($(git rev-parse --short HEAD 2>/dev/null || echo 'unknown'))"
echo "=========================================================="
echo ""

fetch_release() {
    local repo="$1"
    local auth_header=()
    if [ -n "$TOKEN" ]; then
        auth_header=(-H "Authorization: Bearer $TOKEN")
    fi

    # Try latest release first, fallback to first in list
    local res
    res=$(curl -sSL -H "User-Agent: BitChord-ReleaseCheck" "${auth_header[@]}" "https://api.github.com/repos/$repo/releases/latest" 2>/dev/null || echo "{}")
    local tag
    tag=$(echo "$res" | jq -r '.tag_name // empty' 2>/dev/null || true)
    
    if [ -z "$tag" ]; then
        res=$(curl -sSL -H "User-Agent: BitChord-ReleaseCheck" "${auth_header[@]}" "https://api.github.com/repos/$repo/releases?per_page=1" 2>/dev/null || echo "[]")
        tag=$(echo "$res" | jq -r '.[0].tag_name // empty' 2>/dev/null || true)
        if [ -n "$tag" ]; then
            res=$(echo "$res" | jq '.[0]')
        fi
    fi

    echo "$res"
}

now_epoch=$(date +%s)

calc_days_ago() {
    local date_str="$1"
    if [ -z "$date_str" ] || [ "$date_str" = "null" ]; then
        echo "N/A"
        return
    fi
    local pub_epoch
    pub_epoch=$(date -d "$date_str" +%s 2>/dev/null || date -jf "%Y-%m-%dT%H:%M:%SZ" "$date_str" +%s 2>/dev/null || echo 0)
    if [ "$pub_epoch" -eq 0 ]; then
        echo "unknown"
        return
    fi
    local diff_sec=$((now_epoch - pub_epoch))
    local days=$((diff_sec / 86400))
    if [ "$days" -lt 0 ]; then days=0; fi
    echo "$days"
}

echo "Fetching latest releases..."
upstream_json=$(fetch_release "$UPSTREAM_REPO")
fork_json=$(fetch_release "$FORK_REPO")

# Upstream info
u_tag=$(echo "$upstream_json" | jq -r '.tag_name // "unknown"' 2>/dev/null || echo "unknown")
u_name=$(echo "$upstream_json" | jq -r '.name // "N/A"' 2>/dev/null || echo "N/A")
u_date=$(echo "$upstream_json" | jq -r '.published_at // .created_at // ""' 2>/dev/null || true)
u_url=$(echo "$upstream_json" | jq -r '.html_url // ""' 2>/dev/null || true)
u_days=$(calc_days_ago "$u_date")

# Fork info
f_tag=$(echo "$fork_json" | jq -r '.tag_name // "unknown"' 2>/dev/null || echo "unknown")
f_name=$(echo "$fork_json" | jq -r '.name // "N/A"' 2>/dev/null || echo "N/A")
f_date=$(echo "$fork_json" | jq -r '.published_at // .created_at // ""' 2>/dev/null || true)
f_url=$(echo "$fork_json" | jq -r '.html_url // ""' 2>/dev/null || true)
f_days=$(calc_days_ago "$f_date")

echo ""
printf "%-12s | %-12s | %-18s | %-22s | %s\n" "Source" "Version" "Days Since Release" "Published Date" "Release Title"
printf -- "-------------+--------------+--------------------+------------------------+--------------------------\n"

if [ "$u_days" = "1" ]; then u_days_str="1 day ago"; elif [ "$u_days" = "0" ]; then u_days_str="today (0 days)"; elif [ "$u_days" != "N/A" ] && [ "$u_days" != "unknown" ]; then u_days_str="$u_days days ago"; else u_days_str="unknown"; fi
if [ "$f_days" = "1" ]; then f_days_str="1 day ago"; elif [ "$f_days" = "0" ]; then f_days_str="today (0 days)"; elif [ "$f_days" != "N/A" ] && [ "$f_days" != "unknown" ]; then f_days_str="$f_days days ago"; else f_days_str="unknown"; fi

printf "%-12s | %-12s | %-18s | %-22s | %s\n" "Upstream" "$u_tag" "$u_days_str" "${u_date:0:10}" "$u_name"
printf "%-12s | %-12s | %-18s | %-22s | %s\n" "My Fork" "$f_tag" "$f_days_str" "${f_date:0:10}" "$f_name"

echo ""
echo "----------------------------------------------------------"
echo "Summary:"
if [ "$u_days" != "N/A" ] && [ "$f_days" != "N/A" ]; then
    if [ "$u_days" -lt "$f_days" ]; then
        diff_d=$((f_days - u_days))
        echo "• Upstream released more recently ($u_days days ago vs $f_days days ago, $diff_d days newer)."
    elif [ "$f_days" -lt "$u_days" ]; then
        diff_d=$((u_days - f_days))
        echo "• Your fork released more recently ($f_days days ago vs $u_days days ago, $diff_d days newer)."
    else
        echo "• Both repositories released on the same day ($u_days days ago)."
    fi
fi

if git rev-parse --is-inside-work-tree &>/dev/null; then
    echo ""
    echo "Local Git Status:"
    ahead_upstream=$(git rev-list --count upstream/v1.8.2..HEAD 2>/dev/null || echo "?")
    behind_upstream=$(git rev-list --count HEAD..upstream/v1.8.2 2>/dev/null || echo "?")
    echo "• Ahead of upstream/v1.8.2 by $ahead_upstream commits, behind by $behind_upstream commits."
    if git rev-parse --verify origin/v1.8.0 &>/dev/null; then
        ahead_origin=$(git rev-list --count origin/v1.8.0..HEAD 2>/dev/null || echo "?")
        echo "• Ahead of origin/v1.8.0 by $ahead_origin unpushed commits."
    fi
fi
echo "=========================================================="
