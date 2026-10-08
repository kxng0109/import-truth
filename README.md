# importtruth

Your coding agent keeps importing classes that do not exist. importtruth is
the friend who has actually read the libraries and says so, politely but
firmly, before the build has to.

It reads your project's real dependency JARs, writes down every public class
they offer, and answers one question over and over: does this name exist?
Java first.

## How it works (big picture)

1. **Resolve.** Ask Maven which exact library (JAR) files your project uses.
   Remember the answer. Ask again only when your build files change. No
   guessing, no "probably this version".
2. **Index.** Open each library once and write down every public class it
   offers, like an index at the back of a book. Each library is filed under
   the fingerprint (SHA-256) of its contents, so the same file is never
   indexed twice and a stale snapshot cannot sneak in.
3. **Answer.** When the agent writes code, check each import against the
   index. A real import passes silently. A fake import gets one short
   correction line naming the right class.

The tool never changes your project. It only reads. It never runs library
code either. It only reads the shape of it.

## Concepts you will keep meeting

- **ASM.** A small library that reads Java `.class` files without running
  them. Like a scanner, it sees names, signatures, and deprecation marks
  without waking the patient.
- **Code skipping.** A class file has declarations (names and signatures)
  plus the actual step by step instructions. We tell ASM to jump over the
  instructions, so indexing is fast, small, and provably never executes
  anything.
- **Content fingerprints.** Files are filed by content fingerprint, not by
  name or version number. Same content means same fingerprint, so duplicates
  and stale snapshots stop being a problem.
- **Single flight.** If five checks ask for the same library at once, only
  one of them does the indexing work and the other four wait for the result.
  Teamwork, enforced by a hash map.
- **MCP server.** The way the tool talks to the agent: short questions and
  answers over a standard channel (standard input and output). Logs always go
  to the error channel so they never corrupt the conversation.
- **Shaded (fat) JAR.** The final deliverable: all our boxes plus their
  libraries packed into one file, so it runs anywhere with one command.
- **Daemon (later).** A background helper that keeps the warm index in
  memory, so repeated checks skip the startup cost. Thin clients talk to it
  over local network with a secret token file.
- **Policy packs.** Small rule files for big migrations, e.g. Jackson 2 to 3
  or old `javax` names to new `jakarta` names. Rules only fire after the
  existence check passes, so a missing class is never misreported as a style
  problem. Each rule proves its replacement target really exists in your
  actual libraries, or it disables itself rather than bluff.

## Boxes (modules)

- `importtruth-model`: data shapes only. Zero dependencies, by rule.
- `importtruth-policy`: migration rule packs and their validation.
- `importtruth-index`: library indexes filed by content fingerprint.
- `importtruth-adapter-java`: reads Java bytecode without running it.
- `importtruth-core`: the brain: resolve, index, lookup, check.
- `importtruth-mcp`: talks to the agent. Never touches libraries directly.
- `importtruth-cli`: startup and wiring. Builds the final one JAR.

## Build (needs JDK 21+)

```text
./mvnw -q -DskipTests package
```

On Windows use `mvnw.cmd` in place of `./mvnw`.

Slow tests (real Maven spawns, heavy fixtures) carry `@Tag("slow")`:
`mvn test` runs the fast loop only, `mvn verify` runs everything
including coverage gates.

A project with several modules needs its sibling snapshots
installed (`mvn install` once). Otherwise resolution fails loudly
rather than guessing. CI runs `install` for exactly this reason.

Every module enforces coverage gates on `verify`: 95% line, 90% branch
(the `Main` wiring class is excluded. Everything else is tested). The
gates have already caught real bugs, so they stay.

Warm checks answer in about 0.2s: resolution, indexes, parsed files,
and validated policy engines are all memoized per session. JDK answers
come from the project's target release (read from the running JDK's
`ct.sym`), never the running JVM alone, so projects that target other
releases get honest verdicts. Misses get ranked suggestions: exact
names first, then names from the same package, then small typos.

## Enforcement

After edits, an opencode plugin runs the check and reports findings back
into the session. Install it and point it at the shaded JAR:

```text
opencode plugin: opencode-plugin/importtruth.ts
IMPORT_TRUTH_JAR=<path>/importtruth-cli-0.3.0-SNAPSHOT.jar
```

For a small footprint, start the server with a capped heap and the
serial collector:

```text
java -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -jar <path>/importtruth-cli-0.3.0-SNAPSHOT.jar
```

Nested Maven runs inherit a 512m cap unless MAVEN_OPTS is already set.

Direct check of files (exit 1 on missing imports, silent when clean):

```text
java -jar importtruth-cli/target/importtruth-cli-0.3.0-SNAPSHOT.jar check <project> <file>...
```

A sample hook config lives in `.pre-commit-config.yaml`. The MCP server
additionally exposes `lookup_symbol`, `search_api`, and `check_file`
under `mcp.servers` for checks driven by the model, plus batch variants
`lookup_symbols` and `check_files` (up to 50 items per call) for
sweep phases where round trips dominate.

## Status

Shipped in 0.3.0: batch tools (`lookup_symbols`, `check_files`),
warm checks under a second, ranked suggestions, JDK answers for every
target release.
Coverage gates green on all 7 modules. Daemon deferred: sharing is
safe by construction, and answers in the same process are already fast.
