# Public Release Record (WBS 7.1)

What was removed, corrected or decided before this repository was made public, and
why. Kept so the decisions are auditable later, and so a reader can see what was
scrubbed rather than having to infer it.

Prepared 2026-08-29. This branch merges **after** #246 (3.2.5.1) and #249 (3.1.2.7);
several items below are written against their merged state.

## 1. Internal documents removed

Ten documents were deleted and one was untracked but kept on disk. The test applied was
the card's own: *would a stranger running the harness want this?* Everything below is
project-management or academic process, names individuals, or records a third party's
words without their sign-off.

| Document | Why removed |
|---|---|
| `documentation/meetings/meeting-10-03-26.md` | Named teammates and roles, academic action items |
| `documentation/meetings/meeting-12-03-26.md` | Stakeholder meeting minutes, named individual |
| `documentation/meetings/meeting-19-03-26.md` | Stakeholder feedback on team presentations, NDA logistics |
| `documentation/needs-answering/john.md` | Named after an individual; a stakeholder's terse answers |
| `documentation/needs-answering/supervisor.md` | Academic assessment questions |
| `documentation/needs-answering/developers.md` | An FAF maintainer's informal replies, quoted without sign-off |
| `documentation/operations/cost.md` | Engineer-hour budget; coursework artifact |
| `documentation/operations/schedule.md` | 25-week academic phase plan. Deleted rather than corrected: it still claimed a 3 September delivery and had not been touched since 19 April, and a corrected version would carry no value to a consumer while needing public upkeep |
| `documentation/operations/team-roles.md` | Four first names with academic roles |
| `documentation/task-desc.md` | Phase-by-phase task breakdown; project management, not consumer documentation |

`documentation/project-spec.md` — the client's business brief, including commercial
resourcing language — is **untracked rather than deleted**. It stays on disk for the
team's reference and is gitignored, the same treatment `faf-uid` gets in §3: valuable
locally, not published.

**All of the paths above are gitignored**, so a copy restored locally can never be
re-committed by accident. `git add` refuses them without `-f`.

### These files remain in git history, deliberately

`git rm` cleans the tip of the branch, not the past. Every document above is still
readable from earlier commits — `project-spec.md` in 3, `john.md` in 14,
`meeting-19-03-26.md` in 5 — and `faf-uid`'s 3.7 MB blob is still in the pack, so
untracking it did not shrink the clone. On a public repository that means anyone can
recover them with `git show <commit>:<path>`.

This was raised before publication and **accepted knowingly**. The removal is for a
clean working tree, not for secrecy: nothing in these documents is a credential, and
the credential scan in §4 is separately clean. Purging the paths with `git filter-repo`
was considered and rejected as disproportionate — it rewrites all 423 commit SHAs,
forces every contributor to re-clone, breaks the open PR, and re-points the 0.1.0 tag,
all to hide material that is merely untidy.

Worth knowing for any *future* decision of this kind: a rewrite is fully effective only
while the repository is still private, because nothing has been exposed or crawled yet.
After publication, purging additionally needs GitHub Support, and anything already
fetched is beyond recall. If something genuinely sensitive is ever committed, it must be
dealt with before the repository is public, not after.

Three inbound links were repaired rather than left dangling:
`diagrams/sequence-full-session.md` and `diagrams/README.md` now point at
`research/subprocess-orchestration-spec.md` and `research/project-briefing.md`; the
prose reference in `research/subprocess-orchestration-spec.md` §5.3 now says "the
client's original brief" instead of naming the deleted file.

**Kept deliberately:** every research spec, `component-isolation.md`,
`ice-adapter-setup.md`, `harness-runbook.md`, `demos/`, `diagrams/`, `libraries.md`.
All pass the stranger test.

## 2. `.env` removed and gitignored

`.env` was tracked and would have shipped. It was **inert**: no dotenv library is on
the classpath, no code reads a file of that name, and none of its six variable names
(`LOBBY_HOST`, `LOBBY_PORT`, `ICE_ADAPTER_HOST`, `ICE_ADAPTER_RPC_PORT`,
`ICE_ADAPTER_GPGNET_PORT`, `GAME_UDP_PORT`) appears anywhere in Java, Gradle, YAML,
JSON, properties or shell. Configuration actually resolves through
`ConfigLoader` → `System.getenv()` with the `FAF_MOCK_CLIENT_*` prefix, which a file
on disk cannot reach. The contents were leftovers from the abandoned local-stack
model — `LOBBY_PORT=8001` is a docker-compose lobby that no longer exists.

Deleted, and `.env` plus `.env.*` added to `.gitignore`. The pattern is deliberately
broader than the file removed, so a stray `.env.local` holding a real token cannot be
committed either.

`research/lobby-protocol-spec.md` §"Credential Handling" claimed five `FAF_MOCK_*`
keys were "tracked in `.env`" when none were, and that a `.env.example` listed every
variable when no such file exists. Both corrected: the table's names are now marked as
the note's shorthand rather than the implemented ones, with a pointer to
`mock-client/README.md` as the authoritative key list, and the storage paragraph now
describes the real mechanism (JSON config / environment / CLI, with the refresh token
in its own untracked file).

## 3. `faf-uid` untracked

Not in the card's scope, found during the sweep. The 3.7 MB FAF UID binary was tracked
at `5c3e2f3` despite `.gitignore` carrying `/faf-uid*` — so publishing would have
redistributed a third-party binary. Removed from the index with `git rm --cached`; the
file stays on disk, the ignore rule already existed, and
`documentation/demos/README.md` already documents how to obtain it.

## 4. Credential history scan

Re-run in full on **2026-08-29** against 423 commits across all refs, immediately
before publication rather than trusting an earlier result.

- **Credential-shaped filenames ever added:** `.env` only, at `d64259c`. Its contents
  at that commit were hosts and ports (`localhost`, `8001`, `7236`, `7237`, `6112`) —
  no token, key or password. Now deleted (§2).
- **`.secrets/`:** appears in no commit on any ref. Gitignored throughout.
- **Content-level scan** across every blob in history for JWTs (`eyJ…`), GitHub tokens
  (`ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_`), AWS keys (`AKIA…`), Ory tokens, PEM private-key
  headers, and assigned `client_secret` / `refresh_token` values: **0 matches**.

**Nothing requires rotation.** This scan must be re-run immediately before the
repository is actually flipped to public, since work continues on the branch.

## 5. Issue tracker and pull requests

Scanned before publication, because making the repository public publishes every issue,
PR and review comment alongside the code. Covered: 195 issue titles and bodies, 77 PR
titles and bodies, 267 issue and inline review comments, and 208 review summaries.

**No credential, token or secret value was found anywhere.** The one apparent hit,
`CLIENT_SECRET` in #130, is a negative mention ("no `CLIENT_SECRET` is required").

Eighteen pre-planning issues were **deleted**: #1–#4, #23, #38–#50. They were academic
project-management artifacts — study contract, org chart with team photos, cost
analysis in semester hours, risk register, peer review with Team 2 — and failed the
same test that removed the documents in §1. Deleting them also keeps the tracker
coherent with those deletions, since #40, #41 and #42 described creating
`schedule.md`, `cost.md` and `team-roles.md`. Four apparent cross-references from
retained issues were checked and are all false positives (a URL anchor, "reason #3",
"AC #2", "AC #1"). Every remaining issue is technical.

**Contributor email addresses.** Two contributors used personal addresses; the other
three used GitHub noreply addresses. Publishing exposes both permanently via `git log`.
Removing them would mean rewriting all 423 commits, which was judged disproportionate.

At scan time both addresses also appeared in the bodies of issues #96 and #276. They
have since been redacted from #96, and #276 was deleted as a duplicate. Redaction is
not erasure: GitHub shows issue edit history to anyone with read access, so #96's
earlier revisions still carry them. This is moot for exposure purposes — the commit
history publishes the same addresses either way — but it is worth knowing that editing
an issue does not retract what it contained. Raised with the affected people rather
than decided unilaterally; see §9.

## 6. Landing page and release body

`README.md` was 602 bytes of links. Rewritten to say what the harness is, what the two
mocks stand in for, and to give one command that runs the whole path — the Mock Client
launching a real `faf-ice-adapter` and a real Mock Game, with the GPGNet handshake and
a full session. That command is `ClientGameLifecycleLiveTest` (3.1.2.7), which
self-skips rather than fails when the adapter binary or network is absent, so it is
safe to run unconditionally in a consumer's pipeline. Deeper narrative belongs to the
integration guide (7.5).

`.github/RELEASE_BODY.md` used `[README.md](README.md)`, which resolves to
`/releases/tag/README.md` and 404s from a release page. Replaced with an absolute URL.
A deep link to runbook §2 was added alongside it — the no-account path is what a
maintainer embedding the mocks actually needs, and linking the top of a document does
not serve that. The anchor was verified against the heading rather than assumed.

`release.yml` feeds this file through `bodyFile`, so future releases are fixed by the
file; the published 0.1.0 body was updated separately and now contains no relative
links. This completes item 2 of #254 (7.6-fix) in full, so that item can be struck
there rather than owned twice; the checksums half of #254 stays.

## 7. Code cleanup

**`ice-smoke` removed.** It shipped as a stub returning `NOT_IMPLEMENTED` (exit 64),
and its owning card — WBS 3.1.4.3, #151 — is closed, so no work was pending behind it.
Removed the command, its registration in `MockClientCli`, the `NOT_IMPLEMENTED`
constant, `MockClientCliExitCodeTest`'s assertion, `MockClientCliSubcommandHelpTest`'s
two references, and every mention across `mock-client/README.md`,
`MockClientConfig`'s javadoc, `component-isolation.md` and `ice-adapter-setup.md`. No
shipped command now returns `NOT_IMPLEMENTED`.

The value it was meant to provide is real but unbuilt, and is tracked separately (§9).

**`examples` package moved, not deleted.** The card called for removing it as dead
code. `SubprocessManagerExample` is a self-contained worked example of the
`SubprocessManager` API that re-invokes its own JVM as the child and exercises the
SIGTERM→SIGKILL path — genuinely useful to anyone reusing that class, and it passes the
stranger test. The actual defect was narrower: living in `src/main`, it compiled into
the released shadow jar, so a consumer of `mock-client-*.jar` received demo classes.
Moved to `mock-client/src/test/java/…/examples/` and `runSubprocessExample` retargeted
to `sourceSets.test.runtimeClasspath`. It no longer ships; it still runs. Verified.

**TODO audit.** Six TODO comments existed in Java. Three were inside `IceSmokeCommand`
and went with it. The remaining three are all in `MockGameLifecycle`, in the
peer-connection path:

| Comment | Disposition |
|---|---|
| `Initiate actual connection with peer` (JoinGame handler) | Pending **4.3.2** (#219). Verified still present on both #246 and #257, so it is *not* resolved by 4.3.1 as originally assumed |
| `Initiate actual connection with peer` (ConnectToPeer handler) | Same |
| `Configurable values` (game always reports player 1 victory, all others defeat) | Survived 3.2.4.3 (#231). Had no tracking issue; one was filed (§9) |

The rule these answer to lives in `.github/PULL_REQUEST_TEMPLATE.md`, not
`CONTRIBUTING.md`. All three sit in a file that #246 and #257 both modify, so the issue
references are applied after rebasing onto the merged state rather than being written
against line numbers that are about to move.

## 8. The LICENSE

Was MIT, `Copyright (c) 2026 Jai Dutta`. A single individual's name on a four-person
project being handed to a community is unusual, and it was not one person's call to
keep. Changed to:

```
Copyright (c) 2026 The faf-test-harness contributors
```

The licence remains MIT and its terms are byte-identical; only the holder line moved.

Naming all four in full was considered and rejected on a specific ground. Three of the
four have full legal names in git author metadata already, so those publish whether or
not the LICENSE repeats them. The fourth appears nowhere in the repository under
anything but a first name — not in author metadata, not in any tracked file in any
commit, including the meeting notes removed in §1. Writing a surname into the LICENSE
would therefore have created *new* exposure rather than restating existing exposure,
and into the most widely propagated file in the repository: MIT requires the copyright
notice to travel with every copy, so it is duplicated into every downstream
distribution, indexed by GitHub's licence detector, and read by SBOM and compliance
scanners. That is a decision for the person named, not for the team.

The collective form gives all four equal standing, needs no amendment as FAF
contributors arrive after handover, and leaves per-person attribution where people
actually look for it: the git history and the GitHub contributors page.

## 9. Raised, not decided

Two things remain for the team rather than this card.

1. **Contributor email addresses.** As in §5 — permanent on publication, and
   disproportionate to remove. The two affected people should decide rather than
   discover.
2. **Publication itself.** The deleted `needs-answering/john.md` recorded "Can we make
   the github repo public? / No", and `meeting-19-03-26.md` recorded that repositories
   stay private "until such a point as the supervisor goes over them and allows the
   release". Both predate the request that prompted this card. Re-confirm before
   flipping the switch.

Follow-up issues filed for work found but deliberately not done here: making
`launch-ice` dial the adapter's JSON-RPC port so `launch-ice` + `launch-game` can
compose; restoring `ice-smoke` as a working no-lobby reachability command; and making
the mock game's end-of-match results configurable.

## 10. Verified

`./gradlew spotlessApply check` passes. No behaviour changed in any shipped code path:
the only production removals are a stub command that returned `NOT_IMPLEMENTED` and its
now-unused exit-code constant.
