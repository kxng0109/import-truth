# importtruth

Project-resolved API ground truth for coding agents. Java first.

Reads a project's real dependency JARs, indexes their public API, and answers
lookups so agents stop importing classes that do not exist.

## How it works (big picture)

1. **Resolve.** Ask Maven which exact library (JAR) files your project uses.
   Remember the answer; ask again only when your build files change.
2. **Index.** Open each library once and write down every public class it
   offers, like an index at the back of a book. Each library is stored under
   the fingerprint (SHA-256) of its contents, so the same file is never
   indexed twice.
3. **Answer.** When the agent writes code, check each import against the
   index. A real import passes silently. A fake import gets one short
   correction line naming the right class.

The tool never changes your project. It only reads. It never runs library
code either; it only reads the shape of it.

## Concepts you will keep meeting

- **ASM.** A small library that reads Java `.class` files without running
  them. An X-ray machine: it sees names, signatures, and deprecation marks
  without waking the patient.
- **Code-skipping.** A class file has declarations (names and signatures)
  plus the actual step-by-step instructions. We tell ASM to jump over the
  instructions, so indexing is fast, small, and provably never executes
  anything.
- **Content-addressed index.** Files are filed by content fingerprint, not by
  name or version number. Same content means same fingerprint, so duplicates
  and stale snapshots stop being a problem.
- **Single-flight.** If five checks ask for the same library at once, only
  one of them does the indexing work and the other four wait for the result.
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
  actual libraries, or it disables itself.

## Boxes (modules)

- `importtruth-model` — data shapes only. Zero dependencies, by rule.
- `importtruth-core` — the brain: resolve, index, lookup, check.
- `importtruth-mcp` — talks to the agent. Never touches libraries directly.
- `importtruth-cli` — startup and wiring. Builds the final single JAR.

## Build (needs JDK 21+)

```text
./mvnw -q -DskipTests package
```

On Windows use `mvnw.cmd` in place of `./mvnw`.

## Status

M0 done: `ping` answers over stdio (unit plus end-to-end tests green, shaded
JAR verified). Next is M1: resolve, index, lookup.
