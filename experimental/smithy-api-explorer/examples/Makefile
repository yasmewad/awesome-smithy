explorerPath = weather/build/smithy/source/api-explorer/index.html
gitExample = weather/build/git-example
gitExplorerPath = $(gitExample)/build/smithy/source/api-explorer/index.html
git = git -C $(gitExample) -c user.name="API Explorer Example" -c user.email=api-explorer@example.com -c commit.gpgsign=false
repos = file://$(HOME)/.m2/repository|https://repo.maven.apache.org/maven2

clean:
	@echo Cleaning build directories...
	rm -rf weather/build
	@echo Cleaning complete.

publish:
	@echo Publishing the plugin to the local Maven repository...
	cd ..; ./gradlew publishToMavenLocal

weather: clean publish
	@echo Building the weather example...
	cd weather; SMITHY_MAVEN_REPOS="$(repos)" smithy build
	@echo Weather example built successfully.

test-weather: weather
	@echo Checking the generated API explorer page...
	test -s $(explorerPath)
	! grep -q "__API_EXPLORER_DATA__" $(explorerPath)
	grep -q '"id":"example.weather#Weather"' $(explorerPath)
	grep -q '"name":"GetCityImage"' $(explorerPath)
	grep -q '"breaking":true' $(explorerPath)
	grep -q '"danger":true' $(explorerPath)
	@echo The API explorer page has the service, the added operation, and the breaking and danger changes.

# Commits the quick start model to a new repository, applies the example's edits without committing them, and builds
# with no baseline setting, so the plugin compares the working tree with git HEAD.
test-weather-git: publish
	@echo Building the weather example with the default git baseline...
	rm -rf $(gitExample)
	mkdir -p $(gitExample)/model
	cp weather/baseline/weather.smithy $(gitExample)/model/weather.smithy
	printf '%s\n' '{' '  "version": "1.0",' '  "sources": ["model"],' \
		'  "maven": {"dependencies": [' \
		'    "software.amazon.smithy:smithy-aws-traits:1.60.3",' \
		'    "software.amazon.smithy:smithy-api-explorer:0.1.0-SNAPSHOT"' \
		'  ]},' '  "plugins": {"api-explorer": {}}' '}' > $(gitExample)/smithy-build.json
	$(git) init --quiet
	$(git) add model/weather.smithy smithy-build.json
	$(git) commit --quiet --no-verify -m "Add the quick start model"
	cp weather/model/weather.smithy $(gitExample)/model/weather.smithy
	cd $(gitExample) && SMITHY_MAVEN_REPOS="$(repos)" smithy build
	grep -q '"kind":"git","revision":"HEAD"' $(gitExplorerPath)
	grep -q '"example.weather#GetCityImage":"added"' $(gitExplorerPath)
	@echo The API explorer page compares the uncommitted edits with git HEAD.

test-all: test-weather test-weather-git
