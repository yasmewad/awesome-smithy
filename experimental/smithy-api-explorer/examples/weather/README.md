# Weather example

The Weather service from the [Smithy quick start](https://smithy.io/2.0/quickstart.html), rendered with the
`api-explorer` plugin.

- `model/` is the current model.
- `baseline/` is the quick start model before a few edits. The current model adds the `GetCityImage` operation,
  reorders the members of `CityCoordinates` (a Danger change), renames the `pageSize` query parameter (a Breaking
  change), and adds documentation.

`smithy-build.json` sets `"baseline": "baseline"`, so the page shows these changes in a clean checkout. Real projects
normally leave `baseline` out: the plugin then compares the model with git `HEAD`, so the page shows the edits made
since the last commit, as `smithy diff --mode git` does.

`./gradlew test` in the project root renders this example to
`build/examples/weather/source/api-explorer/index.html`.

To build it with the Smithy CLI instead, publish the plugin with `./gradlew publishToMavenLocal`, then run the build
from this directory, so the relative `baseline` path resolves here:

```sh
SMITHY_MAVEN_REPOS="file://$HOME/.m2/repository|https://repo.maven.apache.org/maven2" smithy build
open build/smithy/source/api-explorer/index.html
```

`make test-all` in the parent `examples` directory runs those steps and checks the generated page. It also builds a
copy of the example in a new git repository with no `baseline` setting, to check the default comparison with git
`HEAD`.
