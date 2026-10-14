# Branches and worktrees: where work goes before it ships

Read this before starting work meant for a later release, creating a branch or a worktree, merging into `main` or
cutting a hotfix while `main` holds unreleased work; the rules for several agents in one checkout are here too.

## Main ships

`main` holds only what can ship: every commit on it passes `./gradlew build`, and the next release takes all of it
([releasing.md](releasing.md)). A version is a tag on `main`, never a branch. A task that names no branch works on
`main`.

| Work | Where it goes |
|---|---|
| A fix, or anything finished for the next release | `main`, in the shared checkout |
| A feature for a later release, built until it is solid | `feature/<name>` off `main`, in a worktree of its own |
| A port to a new Minecraft version ([porting.md](porting.md)) | `port/<version>` (`port/26.3`) off `main`, in a worktree of its own, as a feature |
| A hotfix while `main` holds work not ready to ship | `release/<major.minor>` off the release's tag, in a worktree of its own |

Branch names are lower case with hyphens: `feature/digimon-tamers`.

## Several agents in one checkout

- Agents working on `main` share one checkout at the same time. Stage only your own files, by path
  (`git add <path>...`), never `git add -A`, `git add .` or `git commit -a`: another agent's unfinished work sits in
  the same tree. Before the commit, `git diff --cached --stat` lists only your change.
- Never switch the shared checkout's branch (`git switch`, `git checkout <branch>`): every other agent's files would
  change under it mid-task. Any other branch gets a worktree of its own, a second folder on the same repository,
  outside this checkout. Git checks a branch out in one worktree at a time, so agents on the same feature share its
  worktree and follow the rule above there.
- A worktree builds and runs on its own (its own `build/` and `runs/`, so it starts with no dev world); the Gradle and
  Loom caches are shared, so it builds as fast as the main checkout.

## A feature for a later release

1. Create the branch and its worktree from `main`:

   ```bash
   git worktree add -b feature/<name> <folder> main
   ```

2. Work and commit there, one concern per commit ([AGENTS.md](../AGENTS.md#5-commits)).
3. Bring `main` in often, at least after every release, so the final merge stays small: `git merge main` in the
   worktree. Merge, never rebase: a rebase rewrites commits another agent or the remote already has.
4. A fix the released game needs too goes onto `main` first, in the shared checkout, and reaches the branch with the
   next `git merge main`. Made only on the branch, it would wait for the whole feature.
5. The feature lands when it is solid and the user approves it. In the worktree, `git merge main`, then
   `./gradlew build` and the feature's scenarios pass. Then, in the shared checkout on `main`:

   ```bash
   git merge --no-ff feature/<name> -m "feat: merge feature/<name>"
   ```

   `--no-ff` keeps the branch's own commits, each one concern, and marks where the feature landed; never squash. Git
   refuses the merge while another agent's uncommitted change touches a merged file: wait for that commit. If `main`
   moved after the last `git merge main`, build again on `main`. Then drop the worktree and the branch:

   ```bash
   git worktree remove <folder>
   ```

   ```bash
   git branch -d feature/<name>
   ```

## A hotfix while main holds unreleased work

Start from the release's tag, in a worktree of its own:

```bash
git worktree add -b release/0.1 <folder> v0.1.1
```

Fix there, set the next number, build and tag there ([releasing.md](releasing.md#cutting-a-release)), then merge the
branch into `main` from the shared checkout (`git merge --no-ff release/0.1 -m "fix: merge release/0.1"`) and drop
its worktree and branch as a feature's; the tag keeps the release.
