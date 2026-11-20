# BetterSwaggerParser

A Burp Suite extension (Montoya API) that turns Swagger 2.0 / OpenAPI 3.0 / 3.1
definitions and Postman collections into ready-to-send HTTP requests, so you can
drive an API's whole surface through Repeater, Intruder, Organizer, the site map
and the Scanner.

## Features

- **Load a definition** from a file, pasted text, a URL fetched through Burp's
  own networking, or the right-click context menu on any captured Proxy/Repeater
  response that looks like a spec.
- **Swagger 2.0 and OpenAPI 3.0 / 3.1** (JSON or YAML): path- and
  operation-level parameters, request bodies, security schemes, and OpenAPI 3.1
  `type` arrays.
- **Postman collections** (v2.0 / v2.1) **with environment files**: folders,
  headers, query, raw / urlencoded / multipart / GraphQL bodies and auth
  (inherited from folders and the collection). `{{variables}}` resolve from the
  environment first, then the collection; Postman `:path` variables become path
  parameters. Scripts are never executed.
- **Endpoint table** with a live filter, a selectable base URL (from the spec's
  `servers` / `host`+`basePath`, or a collection's hosts), and a custom-headers
  field applied to every request.
- **Auth profiles**: Bearer / JWT, Basic, API key (header, query or cookie), and
  OAuth 2.0 client-credentials or password grant with one-click token fetch.
  Switch profiles to replay the same requests as different users.
- **Parameter values** for real IDs: `petId=1337` for every parameter with that
  name, or `/users/{id} id=42` for one path only. **Fill from Proxy** harvests
  real IDs from Proxy history; **Add path parameters** lists what needs a value;
  values can be **imported and exported**.
- **Request preview** and one-click **Send to Repeater / Intruder / Organizer**,
  **Add to Site map** (issued through Burp networking), and **Active scan**
  (Burp Suite Professional).
- **Export as OpenAPI**: the loaded spec or collection, or just the selected
  endpoints, as a normalized OpenAPI 3.0 JSON document for Burp's **Import API
  definition** scan. Parameters keep their real `type` / `format` / `enum` with
  typed examples (your values included); request bodies get a schema inferred
  from their example.
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
- **The request line cannot be split**: untrusted path templates are
  percent-encoded (spaces, CR/LF, non-ASCII, unsafe characters) and only plain
  token methods are accepted.
- **Postman collections are untrusted too**: pre-request and test scripts are
  never executed (only counted in a warning); variable expansion is bounded in
  depth and size; nesting and request counts are capped; warnings name variables
  but never print their values.
- **OAuth 2.0 token responses** come from the server under test, so they are
  parsed with the same hardened loader; the token is length-capped, must be a
  single line, and is never logged.
- Credentials synthesized from a spec are **placeholders only**; real ones come
  from an auth profile, custom headers or parameter values (trusted GUI input).

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

### Rolling `latest` build

Every push to `main` also republishes a single prerelease tagged
[`latest`](https://github.com/r0xll/BetterSwaggerParser/releases/tag/latest) with
the freshly built jar (`.github/workflows/latest.yml`), so there is always an
up-to-date development build to download. It is marked *pre-release* and its tag
moves with each push; use a `v*` tagged release for a stable, pinned version.

## Install in Burp

1. Download the jar from the [latest release](https://github.com/r0xll/BetterSwaggerParser/releases/latest)
   (or build it, or grab it from a CI run's artifacts).
2. Burp → **Extensions** → **Add** → Extension type **Java** → select the jar.
3. Open the **BetterSwaggerParser** tab, load a spec, pick a base URL, and send
   endpoints wherever you need them.

### Burp editions

Works on both **Community** and **Professional**. Parsing, the suite tab,
context-menu import, request preview, OpenAPI export, and sending to
Repeater / Intruder / Organizer / Site map, auth profiles, Postman import and
*Fill from Proxy* all work on Community (Intruder runs are throttled there, as
usual). The two Scanner-backed features — the **Active
scan** button and the passive *exposed spec* issue — require **Professional**;
on Community they are simply unavailable and the extension loads and runs
normally without them.

## Usage

### 1. Load a definition

Open the **BetterSwaggerParser** tab and pick a source:

- **File**: *Choose spec / collection...* takes Swagger 2.0, OpenAPI 3.x (JSON or
  YAML) or a Postman collection export.
- **Paste**: paste the document and click *Parse pasted spec*.
- **URL**: enter the spec's URL and click *Fetch via Burp* (it goes through
  Burp, so upstream proxies and session rules apply).
- **Context menu**: right-click a Proxy / Repeater message whose response is a
  spec, then *Send to BetterSwaggerParser*.

For a **Postman collection**, also load its environment with *Postman
environment...* (before or after the collection). Environment values override
the collection's variables; *Clear environment* drops them. Loading or clearing
an environment re-parses the collection.

Check the **Warnings** tab after loading: it lists external `$ref`s that were
not fetched, undefined Postman variables, ignored scripts, and base URLs on a
different host than the spec.

### 2. Point it at the target

- **Base URL**: pick one of the spec's servers / the collection's hosts, or type
  your own (e.g. `https://staging.example.com/v1`). Requests are built against it.
- **Custom headers**: one `Name: value` per line, added to every request and
  overriding everything else.
- **Auth**: click *Profiles...* to add one or more auth profiles, then pick the
  active one in the **Auth** box (*None* keeps the document's own auth). For
  OAuth 2.0, fill in the token URL and client details and click *Get token*
  (also available next to the Auth box when the token expires). Switching
  profiles is the quickest way to replay requests as another user when testing
  authorization (IDOR / BOLA).

### 3. Give IDs real values

Generated placeholders (`0`, an all-zero UUID, `string`) usually point at objects
that don't exist, so requests stop at a 404. Use **Parameter values**:

```
petId=1337                 # every parameter named petId
/users/{id} id=42          # only this path template; wins over a plain line
/orders/{id} id=9001
```

- *Add path parameters* appends a line for each path parameter that has no
  value yet (scoped per path when a name such as `id` is shared).
- *Fill from Proxy* reads Proxy history for the base URL's host and appends the
  most recent real IDs (and declared query values) for matching endpoints. Browse
  the application first so history has something to offer.
- *Import...* / *Export...* save the values to a text file and load them back;
  imported lines win over earlier ones.

The **Request preview** updates as you edit values, headers or the auth profile.

### 4. Send and scan

Select one or more endpoints (use the filter box to narrow the list) and:

- **Send to Repeater / Intruder / Organizer**: one tab or item per endpoint.
- **Add to Site map**: issues the requests through Burp and adds the responses.
- **Active scan (Pro)**: queues the requests in a Burp active audit.

### 5. Export for an API-only scan

**Export as OpenAPI** writes the selected endpoints (or all of them) as an
OpenAPI 3.0 JSON file with `servers` set to your base URL and your parameter
values as typed examples. In Burp, start a new scan with **Import API
definition** and choose that file. Configure authentication in the scan's own
settings: auth profiles apply to requests sent from the tab, not to the export.

## Tests

```bash
./gradlew test
```

The Burp-independent core (`io.github.r0xll.betterswagger.core`) is covered by
JUnit 5 tests: Swagger 2.0, OpenAPI 3.0 / 3.1 and Postman fixtures, cyclic and
external `$ref`s, YAML alias bombs, deeply nested documents, bounded variable
expansion, request-line and header injection, auth profiles and OAuth 2.0 token
handling, parameter-value scoping, Proxy-history matching, and the OpenAPI export.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

This extension bundles Jackson and SnakeYAML (both Apache-2.0) in its distributed
jar; their notices are reproduced in [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt)
and carried inside the jar's `META-INF`. The Montoya API is referenced at compile
time only and provided by Burp at runtime, so it is not redistributed here.
