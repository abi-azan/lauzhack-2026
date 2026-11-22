#!/usr/bin/env bash
# Team message board watcher (GitHub issue #1).
# Claude Code hook on SessionStart, UserPromptSubmit and PostToolUse (see .claude/settings.json);
# the event name is passed as $1. It adds board posts that are new since this clone last looked to
# Claude's context, so agents learn about them at session start, on every prompt, and mid-task
# after their next tool call. GitHub is queried at most once every $MIN_GAP seconds; other calls
# return immediately.
# Only needs bash and gh (logged in): JSON is filtered with gh's built-in --jq.
# Per-user state: .claude/board-last-seen, .claude/board-last-check, .claude/board-me (gitignored).
# Fails silently when gh is missing or GitHub is unreachable.
set -u
REPO="bayes-boys/lauzhack-2026"
ISSUE=1
MAX_POSTS=8
MAX_CHARS=700
MIN_GAP=60

event="${1:-UserPromptSubmit}"
case "$event" in SessionStart|UserPromptSubmit|PostToolUse) ;; *) exit 0 ;; esac
cat >/dev/null 2>&1   # drain the hook's stdin; we don't need it
command -v gh >/dev/null 2>&1 || exit 0

dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
seen="$dir/.claude/board-last-seen"
checked="$dir/.claude/board-last-check"
mefile="$dir/.claude/board-me"

now=$(date +%s)
lastcheck=$(cat "$checked" 2>/dev/null || echo 0)
case "$lastcheck" in ''|*[!0-9]*) lastcheck=0 ;; esac
if [ "$event" != "SessionStart" ] && [ $((now - lastcheck)) -lt "$MIN_GAP" ]; then exit 0; fi
echo "$now" > "$checked" 2>/dev/null

last=$(cat "$seen" 2>/dev/null || true)
case "$last" in 20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]T*Z) ;; *) last="" ;; esac
if [ -z "$last" ]; then
  # First run in this clone: don't dump the whole history, just start watching from now.
  date -u +%Y-%m-%dT%H:%M:%SZ > "$seen" 2>/dev/null
  printf '{"hookSpecificOutput":{"hookEventName":"%s","additionalContext":"Team board (issue #%s): new posts will be added to your context automatically. History: gh issue view %s --repo %s --comments"}}\n' \
    "$event" "$ISSUE" "$ISSUE" "$REPO"
  exit 0
fi

me=$(cat "$mefile" 2>/dev/null || true)
if [ -z "$me" ]; then
  me=$(gh api user --jq .login 2>/dev/null || true)
  [ -n "$me" ] && printf '%s\n' "$me" > "$mefile"
fi
case "$me" in *[!A-Za-z0-9-]*) me="" ;; esac   # GitHub logins are [A-Za-z0-9-]; keeps the filter safe

# One request. The filter prints two lines: the newest timestamp seen (for the state file), then
# the finished hook output as JSON, or an empty line when nothing is new.
filter='
  ([.[] | select(.updated_at > "'"$last"'") | select(.user.login != "'"$me"'")]) as $new
  | ([.[].updated_at] | max // ""),
    (if ($new | length) == 0 then "" else
      ("NEW TEAM BOARD POSTS (issue #'"$ISSUE"', since '"$last"'). Check whether any affect your current work, and reply on the board if one tags your human'"'"'s login:\n"
       + ([$new[:'"$MAX_POSTS"'][]
           | "--- \(.updated_at[11:16])Z @\(.user.login)\(if .updated_at != .created_at then " (edited)" else "" end) \(.html_url)\n"
             + (if (.body | length) > '"$MAX_CHARS"' then .body[:'"$MAX_CHARS"'] + " …[truncated]" else .body end)
          ] | join("\n"))
       + (if ($new | length) > '"$MAX_POSTS"' then "\n--- +\(($new | length) - '"$MAX_POSTS"') more: gh issue view '"$ISSUE"' --repo '"$REPO"' --comments" else "" end))
      | {hookSpecificOutput: {hookEventName: "'"$event"'", additionalContext: .}} | tojson
    end)'
out=$(gh api "repos/$REPO/issues/$ISSUE/comments?since=$last&per_page=100" --jq "$filter" 2>/dev/null) || exit 0

newest=$(printf '%s\n' "$out" | sed -n 1p)
json=$(printf '%s\n' "$out" | sed -n 2p)
case "$newest" in 20*Z) printf '%s\n' "$newest" > "$seen" ;; esac
[ -n "$json" ] && printf '%s\n' "$json"
exit 0
