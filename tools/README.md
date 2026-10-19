# tools

## `dev-client.ps1` — the dev RuneLite without IntelliJ

Starts, stops and inspects the **same** client IntelliJ's Run button starts:
the `run` task in `build.gradle`, a JavaExec of `com.ge.gevisualaid.GEVisualAidTest`
with `--developer-mode --debug --insecure-skip-tls-verification`. Same
classpath, same JVM args, same `~/.runelite/credentials.properties`. It is not
a second way to build the plugin — it is the same way, from a different button.

```powershell
tools\dev-client.ps1 status
tools\dev-client.ps1 start     # [-TimeoutSec 180] [-Pinned]
tools\dev-client.ps1 stop
tools\dev-client.ps1 restart
tools\dev-client.ps1 log       # [-Tail 60]
```

### Why it exists

Every plugin change needs the dev client restarted before it can be seen in
`127.0.0.1:8081`. That meant Josh pressing Run and then telling Claude it was
done — six times in one session on 2026-10-18, and twice the check that
followed was run against a client that had not actually been restarted.

### Three things it does that matter

**It cannot kill the wrong process.** There are normally four `java.exe` on
this PC: three Gradle daemons and the client. Only the client has this repo's
`build\classes` on its classpath; a daemon's command line has
`gradle-launcher.jar` and never the repo. `stop` **prints the command line it
matched before killing anything**, so a wrong match is visible in the log
rather than inferred later from what went missing. IntelliJ is `idea64.exe`
and is never a candidate.

**Ready means answering, not launched.** `start` polls `/state` until the
plugin replies and reports the version that answered. This suite has been
bitten by the difference before — a VM running month-old plugin source
publishes a perfectly healthy `/state`, so "I started it" is not evidence of
anything. Cold start is around 50 seconds.

**It refuses to start a second client.** Two plugins would fight over port
8081 and the loser fails quietly. `launch.bat` carries a long comment about a
stray retry starting a second client hours later; same hazard, same refusal.

### One bug it had, worth remembering

The first version treated *"no client process yet"* as failure. Five seconds
into a perfectly healthy launch, while Gradle was still printing
`Task :classes UP-TO-DATE`, it declared a compile error — having already
killed the previous client. **"Not there yet" and "not coming" are identical
from one sample.** It now holds `-PassThru` on the Gradle process and only
calls failure when **Gradle has exited** and no client exists. The same
confirm-the-outcome rule as `/plugin` in 2.73.

### Logs

Outside the repo, deliberately — a file written inside the working tree
dirties it and makes the next `git pull --ff-only` refuse. Same reason
`launch.bat`'s log lives in `%USERPROFILE%`.

```
%USERPROFILE%\ge-visual-aid-devclient.log       what this script did
%USERPROFILE%\ge-visual-aid-devclient.out.log   gradle + client stdout
%USERPROFILE%\ge-visual-aid-devclient.err.log   stderr
```

### What it does NOT do

**It cannot log in.** The client comes up at the login screen and a human has
to log in. Anything that needs a logged-in client — widget geometry, the
slayer task, the spellbook — still needs that one action.

### Relationship to `launch.bat`

`shared/launch.bat` in `osrs-suite` is what the **VMs** run: it pulls, compiles
with a fallback to a pinned RuneLite, then launches, and never stops the
client itself. This script is the **desktop dev** equivalent and deliberately
does less: no pull (the repo here is the source of truth, not a copy) and no
compile fallback, because a compile failure on this machine should stop and be
read, not be worked around. `-Pinned` is the manual escape hatch, and its
version is kept in step with `launch.bat`'s `PINNED`.
