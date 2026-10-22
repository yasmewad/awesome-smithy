# Smithy API Explorer

> **Experimental.** This plugin is a preview that lives in awesome-smithy while
> it takes shape. Its output, its settings, its Maven coordinates, and its name
> may change in any release.

Smithy API Explorer is a [Smithy](https://smithy.io) build plugin that renders
a service model as one self-contained HTML file: an interactive API reference
that you can open straight from disk, attach to a pull request, or publish as a
static page.

The plugin is named `api-explorer` in `smithy-build.json`. It writes
`index.html` to its output directory, for example
`build/smithy/source/api-explorer/index.html`.

## What the page shows

- **Service overview:** the protocol, version, auth schemes, resources, an
  operation table with methods and paths, and counts of operations, shapes,
  changes, and validation events.
- **Operations:** HTTP method, URI, and status code; each input and output
  member with its HTTP binding (label, query, header, payload), constraints,
  and documentation; errors; pagination; event streams; and examples.
- **Shapes:** members, targets, traits, constraints such as `@length`,
  `@range`, and `@pattern`, enum values, mixins, where the shape is used, and
  the file and line that define it.
- **Resources:** identifiers, properties, lifecycle operations, and child
  resources.
- **Search** across operations, resources, shapes, members, enum values, and
  HTTP URIs, with filters by kind and shape type.
- **Validation:** the events the build reported for the model.
- **Example request:** an editable request above a sample response, both as JSON. The
  request is checked against the model as you type, so a value that breaks a
  constraint such as `@length`, `@range`, or `@pattern` is flagged on its
  member. The page never sends requests.
- **Changes:** what was added, changed, and removed since the baseline, the
  compatibility events from
  [smithy-diff](https://smithy.io/2.0/guides/smithy-diff.html) labeled
  Breaking, Danger, Warning, or Note, and a line diff of the model files. By
  default the baseline is the last commit (git `HEAD`), so the page shows your
  uncommitted edits, as `smithy diff --mode git` does.

The page has light and dark themes, a responsive layout, and keyboard
shortcuts (press `?` to list them).

## Quick start

Add the plugin to `smithy-build.json`. It needs no settings:

```json
{
    "version": "1.0",
    "sources": ["model"],
    "maven": {
        "dependencies": [
            "software.amazon.smithy:smithy-api-explorer:0.1.0-SNAPSHOT"
        ]
    },
    "plugins": {
        "api-explorer": {}
    }
}
```

Then build the model and open the page:

```sh
smithy build
open build/smithy/source/api-explorer/index.html
```

When the model sources are committed in a git repository, the page also shows
what changed since the last commit: the working tree, including staged and
unstaged edits, compared with git `HEAD`. This is the comparison that
`smithy diff --mode git` makes, so you can edit the model, rebuild, and review
the change before you commit it. Outside a git repository, or before the
sources are committed, the page has no Changes view and the build logs why at
the INFO level.

### With Gradle

Projects that use the
[Smithy Gradle plugins](https://github.com/smithy-lang/smithy-gradle-plugin)
add the plugin to the `smithyBuild` configuration and list it in
`smithy-build.json` as above:

```kotlin
dependencies {
    smithyBuild("software.amazon.smithy:smithy-api-explorer:0.1.0-SNAPSHOT")
}
```

The page is written to the plugin's output directory under the build
directory, for example `build/smithyprojections/<project>/source/api-explorer/index.html`.

## Settings

Every setting is optional, and the defaults suit most projects:

| Setting    | Default | Description | Example |
|------------|---------|-------------|---------|
| `service`  | The only service in the model | The shape ID, or the bare name, of the service to render. When the model has several services, the plugin renders the first one by shape ID and logs a warning that lists the others. | `"example.weather#Weather"` |
| `title`    | The service name | The name shown in the top bar and in the page title. | `"Weather API"` |
| `baseline` | git `HEAD`, when the model sources are committed | What to compare the model with. Leave it out to compare with the last commit. Set it only to override that; see the forms below. | `{"git": "main"}` |

The `baseline` setting takes one of these forms:

| Value | Compares the model with |
|-------|-------------------------|
| not set | The model sources as git `HEAD` has them, when they are committed in a git repository. Otherwise there is no comparison, and the build logs why at the INFO level. |
| `{"git": "<revision>"}` | The model sources as that revision has them: a branch, a tag, a commit, or an expression such as `HEAD~1` or `origin/main`. |
| `true` | The same as `{"git": "HEAD"}`. |
| `"<path>"` | A model file, a directory of model files, a project directory with a `smithy-build.json`, or a JAR. A relative path is resolved against the working directory of the build, not against `smithy-build.json`. |
| `false` | Nothing. The page has no Changes view. |

When a configured baseline cannot be used, for example a path that does not
exist, a baseline that does not load, or sources that are not in a git
repository, the plugin logs a warning, the page says why, and the build still
succeeds. A git revision that does not name a commit is a configuration error,
so it fails the build.

Unknown settings and settings of the wrong type fail the build, so a typo does
not go unnoticed. A bare service name that more than one service has also fails
the build; use the full shape ID instead.

For example, to compare a feature branch with `main` and give the page a
title:

```json
"api-explorer": {
    "title": "Weather API",
    "baseline": {"git": "main"}
}
```

### Comparing with git

The git baseline follows `smithy diff --mode git`. The plugin reads each of the
build's model sources (the files and directories that `smithy-build.json`
declares) as the revision has them, with `git ls-tree` and `git show`, and
loads them as a model with the current build's dependencies. Files that were
deleted since the revision are included, so their shapes show as removed. The
plugin never changes the repository: it creates no worktree or branch and
writes the revision's files to a temporary directory that it deletes
afterwards.

The comparison depends on the state of the repository, not only on the model.
A build on a clean checkout, as in most CI jobs, compares `HEAD` with itself
and reports no changes. To review a pull request in CI, set `baseline` to the
branch it merges into, for example `{"git": "origin/main"}`, and make sure the
checkout fetches that branch. Set `baseline` to `false` when a build should
never compare.

The page records what it compared with in the Changes header and the overview
tile, for example "Compared with git HEAD (abc123def456)", and the build logs
the same at the INFO level.

### How a path baseline is loaded

For a project directory, the plugin loads only the sources and imports that its
`smithy-build.json` declares, or the conventional `model`, `models`, and
`src/main/smithy` directories when it declares none. Build outputs and other
models elsewhere in the directory are ignored.

For every baseline, shapes that the build does not count as sources, such as
those from JAR dependencies and top-level `imports`, are treated as
dependencies. When the baseline does not define one of them itself, the plugin
copies the current definition into the baseline, so those shapes are not
reported as changed. When the baseline defines a shape itself, its own
definition is compared.

When the plugin runs in a projection, the projection's transforms are applied
to the baseline too, so shapes that the projection filters out are not reported
as removed. A projection that uses the `apply` transform is not compared, and
the page says why.

## Generated output

The plugin writes one file, `index.html`, to its output directory, for example
`build/smithy/source/api-explorer/index.html` for the `source` projection. The
page embeds its data as JSON in a
`<script id="api-explorer-data" type="application/json">` element, so tools
can read it too. The data has these top-level members:

| Member | Description |
|--------|-------------|
| `service` | The rendered service: its ID, name, version, protocol, auth schemes, traits, documentation, and the file and line that define it. Absent when the model has no service. |
| `operations` | Each operation of the service with its input, output, errors, HTTP binding, traits, and examples. |
| `resources` | Each resource of the service with its identifiers, properties, lifecycle operations, and child resources. |
| `shapes` | Every shape the service reaches, by shape ID, with its members, traits, and constraints. |
| `validation` | Whether the model is valid, and the validation events the build reported. |
| `changes` | Present when the model was compared with a baseline. It has `entries` (what was added or changed), `removed`, `compatibility` (the smithy-diff events), `traits`, `targetChanged`, `resources`, `docsChanged`, `files` (the line diff), `diffMode` (`source`, or `normalized` when the baseline is not IDL source), and `baseline`, which says what was compared: `{"kind": "git", "revision": "HEAD", "commit": "abc123def456"}` or `{"kind": "path", "path": "baseline"}`, with the path as configured. |
| `changesSkipped` | Present instead of `changes` when a configured baseline could not be compared, with the reason. |
| `generator` | The plugin's name and version, and that it is experimental. |
| `title` | The `title` setting, when it is set. |

The shape of the data is experimental and may change between releases.

## Integrating the plugin

1. Add `software.amazon.smithy:smithy-api-explorer` to the build's
   dependencies: `maven.dependencies` in `smithy-build.json` for the Smithy
   CLI, or the `smithyBuild` configuration for Gradle. Until a release is
   published, use a local build (see below).
2. Add `"api-explorer": {}` to `plugins` in `smithy-build.json`, or to a
   projection's `plugins` to render that projection.
3. Set `service` if the model has more than one service, and `title` if the
   service name is not the name readers know.
4. Run `smithy build` (or `./gradlew build`) and open `index.html` from the
   plugin's output directory.
5. Edit the model and build again. The Changes view shows the edits since the
   last commit. Set `baseline` only if you want to compare with something
   else, such as a release branch or a published JAR.
6. In CI, publish `index.html` as a build artifact or a static page. To show
   the changes of a pull request, set `baseline` to `{"git": "origin/main"}`
   (or the target branch) and fetch that branch in the checkout.

### Using a local build

Until a release is published, build and install the plugin into your local
Maven repository:

```sh
./gradlew publishToMavenLocal
```

Gradle builds can then resolve it with `mavenLocal()` in `repositories`. The
Smithy CLI reads extra repositories from the `SMITHY_MAVEN_REPOS` environment
variable, for example
`SMITHY_MAVEN_REPOS="file://$HOME/.m2/repository|https://repo.maven.apache.org/maven2"`.

## Example

[`examples/weather`](examples/weather) is the Weather service from the Smithy
quick start with a few edits to compare against its baseline: an added
operation, reordered structure members, and a renamed query parameter. The
example sets `"baseline": "baseline"` to a copy of the original model, so it
shows changes even in a clean checkout; real projects normally leave
`baseline` out and compare with git. `./gradlew test` renders it to
`build/examples/weather/source/api-explorer/index.html`.

To test the plugin the way a user runs it, with the Smithy CLI, run the
example's integration test from the `examples` directory. It follows the
Makefile pattern that
[smithy-examples](https://github.com/smithy-lang/smithy-examples) uses: it
publishes the plugin to your local Maven repository, runs `smithy build` in
`examples/weather`, and checks that the generated page names the service, the
added operation, and the Breaking and Danger changes. It then commits the
quick start model to a new repository under `weather/build`, applies the
example's edits without committing them, and builds with no `baseline`
setting, to check that the page compares the edits with git `HEAD`.

```sh
cd examples
make test-all
open weather/build/smithy/source/api-explorer/index.html
```

## Security and privacy

The output is a static file. Its data, scripts, styles, and images are all
inline, and it makes no network requests: no fonts, analytics, or CDNs. A
Content-Security-Policy in the page (`default-src 'none'`) enforces this, so
the page works offline and from `file://`.

The page contains the model as the build saw it, including documentation and
the relative paths of the model files. Review it as you would the model
before you publish it.

## How it relates to smithy-docgen

[smithy-docgen](https://github.com/smithy-lang/smithy/tree/main/smithy-docgen)
generates documentation source (Markdown or reStructuredText) that a site
generator such as Sphinx turns into a documentation site. It suits long form,
customer facing documentation that lives alongside guides.

The API explorer complements it. It needs no site generator or toolchain,
produces one file, and is built for exploring a model: search, cross links,
HTTP bindings, a request preview, validation events, and a compatibility view
of what changed since a baseline. For uses such as previewing a model during
development, reviewing API changes, or publishing a simple interactive
reference, the explorer may be enough to replace docgen output.

## Status and limitations

- Experimental; expect changes to the output, the settings, and the data the
  page embeds.
- One service per page.
- The example request is never sent.
- Large models produce large pages, since the whole model is embedded.

## Building

The project needs JDK 17 or later and builds with the Gradle wrapper:

```sh
./gradlew build
```

The plugin builds against Smithy 1.60.3 (`smithyVersion` in
`gradle.properties`) on purpose. The Smithy CLI supplies its own copies of
`smithy-model`, `smithy-build`, and `smithy-diff`, and it refuses to run a
plugin whose dependencies resolve to a newer Smithy release than the CLI
itself. Building against an older release keeps the plugin usable with every
CLI from 1.60.3 on. Raise the version only when the plugin needs an API that
older releases do not have.

## Security

See [CONTRIBUTING](../../CONTRIBUTING.md#security-issue-notifications) for more
information.

## License

This project is licensed under the Apache 2.0 License. See [LICENSE](../../LICENSE)
and [NOTICE](../../NOTICE).
