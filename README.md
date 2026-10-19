# BetterSwaggerParser

A Burp Suite extension (Montoya API) that parses Swagger 2.0 and OpenAPI 3.0 / 3.1
definitions into ready-to-send HTTP requests, so you can drive an API's whole
surface through Repeater, Intruder, Organizer, the site map and the Scanner.

## Features

- **Load a spec** from a file, pasted text, a URL fetched through Burp's own
  networking, or the right-click context menu on any captured proxy/Repeater
  response that looks like a spec.
- **Swagger 2.0 and OpenAPI 3.0 / 3.1** — path- and operation-level parameters,
  request bodies (`body`/`formData`/`requestBody` + `content`), security schemes
  (API key header/query/cookie, HTTP basic/bearer, OAuth2 → bearer placeholder),
  and OpenAPI 3.1 `type` arrays.
- **Endpoint table** with a live filter, selectable base URL (from the spec's
  `servers` / `host`+`basePath`), and a custom-headers field applied to every
  request (e.g. `Authorization: Bearer ...`).
- **Request preview** and one-click **Send to Repeater / Intruder / Organizer**,
  **Add to Site map** (issued through Burp networking), and **Active scan**
  (Burp Suite Professional).
- **Passive detection** of exposed API definitions: responses that are Swagger /
  OpenAPI documents are flagged as an informational scan issue (no extra traffic).

## Security model

The extension treats every spec as untrusted content (PortSwigger BApp rule 1):

- **External `$ref`s are never fetched.** Only internal `#/...` JSON pointers are
  resolved, with cycle detection and a depth cap. External / relative / URL
  references are reported as warnings and left unresolved — so a hostile spec
  cannot drive an SSRF or local-file read, and nothing bypasses Burp networking.
- **Hardened loading.** Input is size-capped; Jackson `StreamReadConstraints`
  bound nesting depth, token and document length; SnakeYAML `LoaderOptions` bound
  code points and nesting and forbid recursive keys. Only the tree model is used —
  never POJO/polymorphic deserialization — so no gadget types are instantiated.
  (Jackson's YAML tree reader does not expand aliases, so "billion-laughs"
  amplification cannot explode the document.)
- **Example generation is budgeted** (depth, node count, array fan-out), so a
  pathological schema cannot blow up memory.
- **Request building sanitises** CR/LF out of header names and values and
  URL-encodes path/query data, preventing request smuggling via spec content.
- Synthesized credentials are **placeholders only**; supply real tokens via the
  custom-headers field (trusted GUI input).

All HTTP goes through `api.http()` (BApp rule 5). Slow work runs on a background
thread pool with per-task exception logging to the extension error stream
(rule 3), which is shut down on unload (rule 4). GUI dialogs are parented to the
Burp frame (rule 8). Dependencies are bundled into a single jar (rule 2); the
Montoya API is `compileOnly` and provided by Burp (rule 9).

## Build

Requires JDK 21.

```bash
./gradlew build
```

The self-contained extension jar is written to
`build/libs/BetterSwaggerParser-<version>.jar`. GitHub Actions builds and tests
it on every push and uploads the jar as a workflow artifact
(`.github/workflows/build.yml`).

## Releases

Tagged versions are published automatically to
[GitHub Releases](https://github.com/r0xll/BetterSwaggerParser/releases) with the
built jar attached (`.github/workflows/release.yml`). To cut a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release workflow builds `BetterSwaggerParser-1.0.0.jar` (the jar version
tracks the tag via `-PreleaseVersion`), creates the release, generates notes, and
attaches the jar. You can also trigger it manually from the **Actions** tab
(*release* → *Run workflow*) by entering a version.

## Install in Burp

1. Download the jar from the [latest release](https://github.com/r0xll/BetterSwaggerParser/releases/latest)
   (or build it, or grab it from a CI run's artifacts).
2. Burp → **Extensions** → **Add** → Extension type **Java** → select the jar.
3. Open the **BetterSwaggerParser** tab, load a spec, pick a base URL, and send
   endpoints wherever you need them.

## Tests

```bash
./gradlew test
```

The Burp-independent core (`io.github.r0xll.betterswagger.core`) is covered by
JUnit 5 tests, including fixtures for Swagger 2.0, OpenAPI 3.0 / 3.1, cyclic and
external `$ref`s, YAML alias bombs and deeply nested documents.
