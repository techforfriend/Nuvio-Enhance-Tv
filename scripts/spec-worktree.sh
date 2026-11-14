#!/usr/bin/env bash
# Parallel specs (AGENTS.md § Conventions, "Parallel specs"): one git worktree + branch per coding spec.
#   scripts/spec-worktree.sh add <NNN>      -> ../Nuvio-s<NNN> on branch spec-<NNN>, from origin/nuvio-test
#   scripts/spec-worktree.sh sayl-back <NNN> -> at Close (PS-4/PS-7): copy the spec's sandbox work into main
#   scripts/spec-worktree.sh remove <NNN>   -> removes the worktree once its branch is in origin/nuvio-test
#                                              and its sandbox work is copied back
#   scripts/spec-worktree.sh seed-sayl <NNN> -> replace a linked .sayl (worktrees made before 2026-11-08) by a copy
#   scripts/spec-worktree.sh seed-reviews <NNN> -> replace a linked docs/reviews (worktrees made before 2026-11-10)
#                                              by a real folder
# The worktree links the main checkout's gitignored local files (signing, secrets, Shield config,
# research, subagent reports), so they keep one home and survive removal. .sayl/sandboxes is copied,
# not linked: Sayl refuses sandbox writes whose path resolves outside the chat's folder. Tracked .sayl
# files (lead.md) come with the checkout and stay. docs/reviews is a real folder because reviewers
# write no report through a link (Specs 55 and 72); remove copies its reports into main's docs/reviews.
set -euo pipefail

main="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
cmd="${1:-}"; num="${2:-}"
[[ "$cmd" =~ ^(add|remove|sayl-back|seed-sayl|seed-reviews)$ && "$num" =~ ^[0-9]{3}$ ]] \
  || { echo "usage: $0 add|remove|sayl-back|seed-sayl|seed-reviews <NNN>" >&2; exit 2; }
branch="spec-$num"
tree="$(dirname "$main")/Nuvio-s$num"

linked=(local.properties local.dev.properties addons.local.properties shield.local.properties nuviotv.jks
        builds tools/platform-tools docs/research docs/subagents docs/design/mockups)
reviews=docs/reviews                                          # real folder per worktree, copied back at remove

sayl_py="$main/scripts/spec-worktree-sayl.py"
# The baseline lives in the worktree's private git dir: neither Sayl nor git status sees it.
baseline() { echo "$(git -C "$tree" rev-parse --absolute-git-dir)/sayl-seed.json"; }

# A real .sayl/sandboxes copied from main (reflink on btrfs), plus the baseline sayl-back diffs against.
# A real .sayl may already exist: the checkout brings its tracked files.
seed_sayl() {
  if [[ -L "$tree/.sayl" ]]; then rm "$tree/.sayl"            # the link only, never main's folder behind it
  elif [[ -e "$tree/.sayl/sandboxes" ]]; then echo "$tree/.sayl/sandboxes already exists; not reseeding" >&2; exit 1; fi
  mkdir -p "$tree/.sayl"
  cp -a --reflink=auto "$main/.sayl/sandboxes" "$tree/.sayl/sandboxes"
  python3 "$sayl_py" seed "$main" "$tree" "$(baseline)"
}

case "$cmd" in
add)
  [[ -e "$tree" ]] && { echo "exists: $tree" >&2; exit 1; }
  git -C "$main" fetch -q origin nuvio-test
  git -C "$main" worktree add -q -b "$branch" "$tree" origin/nuvio-test
  # .gitignore's dir/ patterns miss a symlink; the shared info/exclude hides the links in every worktree.
  exclude="$(git -C "$main" rev-parse --git-common-dir)/info/exclude"
  for p in "${linked[@]}"; do grep -qxF "/$p" "$exclude" 2>/dev/null || echo "/$p" >> "$exclude"; done
  for p in "${linked[@]}"; do
    [[ -e "$main/$p" ]] || continue
    mkdir -p "$(dirname "$tree/$p")"
    ln -s "$main/$p" "$tree/$p"
  done
  mkdir -p "$tree/$reviews"
  seed_sayl
  echo "ready: $tree (branch $branch)"
  ;;
remove)
  git -C "$main" fetch -q origin nuvio-test
  git -C "$main" merge-base --is-ancestor "$branch" origin/nuvio-test \
    || { echo "$branch is not in origin/nuvio-test yet; not removing" >&2; exit 1; }
  [[ -L "$tree/.sayl" ]] || python3 "$sayl_py" check "$main" "$tree" "$(baseline)"
  # git worktree remove refuses an unclean tree only after the deletes below: check first, delete nothing.
  [[ -z "$(git -C "$tree" status --porcelain)" ]] \
    || { echo "$tree has uncommitted or untracked files; not removing" >&2; git -C "$tree" status --short >&2; exit 1; }
  # The links point into the main checkout: drop them first so nothing behind them is touched.
  for p in "${linked[@]}"; do [[ -L "$tree/$p" ]] && rm "$tree/$p"; done
  if [[ -L "$tree/$reviews" ]]; then rm "$tree/$reviews"     # an old worktree's link
  elif [[ -d "$tree/$reviews" ]]; then                       # run folders carry a timestamp + id: no clobber
    mkdir -p "$main/$reviews"
    cp -an "$tree/$reviews/." "$main/$reviews/"
    rm -rf "$tree/$reviews"
  fi
  if [[ -L "$tree/.sayl" ]]; then rm "$tree/.sayl"            # an old worktree's link, never main's folder behind it
  else rm -rf "$tree/.sayl/sandboxes"; fi                     # the real copy, checked above; tracked .sayl files stay
  git -C "$main" worktree remove "$tree"
  git -C "$main" branch -d "$branch"
  echo "removed: $tree"
  ;;
sayl-back)
  exec 9>/tmp/nuvio-sayl-back.lock                           # one spec copies back at a time (PS-7)
  flock 9
  python3 "$sayl_py" back "$main" "$tree" "$(baseline)"
  ;;
seed-sayl)
  [[ -d "$tree" ]] || { echo "no worktree: $tree" >&2; exit 1; }
  seed_sayl
  echo "seeded: $tree/.sayl"
  ;;
seed-reviews)
  [[ -L "$tree/$reviews" ]] || { echo "$tree/$reviews is not a link; nothing to do" >&2; exit 1; }
  rm "$tree/$reviews"                                         # the link only, never main's folder behind it
  mkdir "$tree/$reviews"
  echo "real folder: $tree/$reviews"
  ;;
esac
