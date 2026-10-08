# checksum-skipper-maven-plugin

Skips an expensive build step, such as code generation from a database container, while its inputs are unchanged
since the last successful run.

- `check` (default phase `initialize`) hashes the inputs and sets a property to `true` if the step is up to date.
- The expensive plugin takes that property as its `skip` parameter.
- `record` (default phase `process-sources`) stores the checksum after the step succeeded. `check` discards the old
  record as soon as it decides to run the step, so a step that fails records nothing and the next build runs it
  again, even if the inputs are reverted meanwhile.

## Usage

```xml
<plugins>
    <plugin>
        <groupId>de.qaware.maven</groupId>
        <artifactId>checksum-skipper-maven-plugin</artifactId>
        <version>1.0.0</version>
        <executions>
            <execution>
                <id>check-expensive-plugin-inputs</id>
                <goals>
                    <goal>check</goal>
                </goals>
                <configuration>
                    <fileSets>
                        <fileSet>
                            <directory>src/main/expensive-plugin</directory>
                        </fileSet>
                    </fileSets>
                    <plugins>
                        <plugin>com.example:expensive-maven-plugin</plugin>
                    </plugins>
                    <outputs>
                        <output>${project.build.directory}/generated-sources/expensive-plugin</output>
                    </outputs>
                    <property>expensive-plugin.skip</property>
                    <checksumFile>${project.build.directory}/expensive-plugin-inputs.sha256</checksumFile>
                </configuration>
            </execution>
            <execution>
                <id>record-expensive-plugin-inputs</id>
                <goals>
                    <goal>record</goal>
                </goals>
                <configuration>
                    <checksumFile>${project.build.directory}/expensive-plugin-inputs.sha256</checksumFile>
                </configuration>
            </execution>
        </executions>
    </plugin>
    <plugin>
        <groupId>com.example</groupId>
        <artifactId>expensive-maven-plugin</artifactId>
        <version>1.0.0</version>
        <executions>
            <execution>
                <id>generate</id>
                <phase>generate-sources</phase>
                <goals>
                    <goal>generate</goal>
                </goals>
                <configuration>
                    <skip>${expensive-plugin.skip}</skip>
                    <inputDirectory>src/main/expensive-plugin</inputDirectory>
                    <outputDirectory>${project.build.directory}/generated-sources/expensive-plugin</outputDirectory>
                </configuration>
            </execution>
        </executions>
    </plugin>
</plugins>
```

`record` stores the checksum of the `check` execution with the same `checksumFile`. To skip several steps, declare
one such pair per step, each with its own `checksumFile` and `property`.

The expensive plugin takes the property as `<skip>${expensive-plugin.skip}</skip>`. Do not define the property
anywhere else: Maven substitutes a value from `<properties>` of this POM or a parent, or from a `settings.xml`
profile, before `check` runs, so `check` fails the build. A `-Dexpensive-plugin.skip=…` on the command line overrides
the computed value the same way; `check` only warns about it. If that plugin registers its output directory as a
source root only when it runs, add the directory with `build-helper-maven-plugin:add-source`.

## When is a step up to date?

All of these must hold:

1. `skipper.force` is not set (`mvn verify -Dskipper.force` forces a run).
2. `checksumFile` exists. It lives in `target/`, so `mvn clean` forces a run.
3. Every `output` exists, and directories are not empty.
4. The checksum equals the recorded one. It covers:
   - the path and content of every file in `fileSets`,
   - version, dependencies and configuration of every plugin in `plugins`.

   Plugin configuration values are interpolated, so a fingerprinted plugin whose configuration uses
   `${project.version}` does rerun on a version bump.

   The project version is not part of it, so a release bump does not rerun the step. Plugin configuration holds
   interpolated absolute paths, so moving the checkout reruns the step once.

`record` stores the checksum whether or not the step actually ran. If you skip the step by other means, for example
`-D<skip property of that plugin>=true` on the command line, while inputs changed, the new checksum is recorded and
the next build considers the step up to date. List such plugins under `plugins` or avoid doing that.

## Parameters

| Goal | Parameter | Required | Description |
|---|---|---|---|
| check | `fileSets` | one of fileSets/plugins | Ant-style file sets; relative directories resolve against the project base directory |
| check | `plugins` | one of fileSets/plugins | `groupId:artifactId` of plugins whose setup is an input |
| check | `outputs` | no | Files or directories that must exist |
| check | `property` | yes | Project property set to `true`/`false` |
| check | `force` | no | User property `skipper.force` |
| check, record | `checksumFile` | yes | Must be the same file for both goals |

Bind `record` after the expensive step. Its default phase `process-sources` fits steps in `generate-sources`; for
steps in `generate-resources` bind it to `process-resources`.

`check` deletes the old record whenever it decides to run the step, including when only an output is missing or the
run is forced. A build that stops before `record`, such as `mvn generate-sources`, therefore runs the step again next
time. To avoid that, bind `record` to the same phase as the step and declare this plugin after the step's plugin in
the POM: Maven runs executions of one phase in plugin declaration order.

## License

Copyright 2026 QAware GmbH. Licensed under the [Apache License, Version 2.0](LICENSE).
