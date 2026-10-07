# checksum-skipper-maven-plugin

Skips an expensive build step, such as code generation from a database container, while its inputs are unchanged
since the last successful run.

- `check` (default phase `initialize`) hashes the inputs and sets a property to `true` if the step is up to date.
- The expensive plugin takes that property as its `skip` parameter.
- `record` (default phase `process-sources`) stores the checksum after the step succeeded. A failed step records
  nothing, so the next build runs it again.

## Usage

```xml
<plugin>
    <groupId>de.qaware.maven</groupId>
    <artifactId>checksum-skipper-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <checksumFile>${project.build.directory}/jooq-inputs.sha256</checksumFile>
    </configuration>
    <executions>
        <execution>
            <id>check-jooq-inputs</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <fileSets>
                    <fileSet>
                        <directory>src/main/resources/db/migration</directory>
                    </fileSet>
                </fileSets>
                <plugins>
                    <plugin>org.testcontainers:testcontainers-jooq-codegen-maven-plugin</plugin>
                </plugins>
                <outputs>
                    <output>${project.build.directory}/generated-sources/jooq</output>
                </outputs>
                <property>jooq.codegen.skip</property>
            </configuration>
        </execution>
        <execution>
            <id>record-jooq-inputs</id>
            <goals>
                <goal>record</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

On the expensive plugin, set `<skip>${jooq.codegen.skip}</skip>`. If that plugin registers its output directory as a
source root only when it runs, add the directory with `build-helper-maven-plugin:add-source`.

## When is a step up to date?

All of these must hold:

1. `skipper.force` is not set (`mvn … -Dskipper.force` forces a run).
2. `checksumFile` exists. It lives in `target/`, so `mvn clean` forces a run.
3. Every `output` exists, and directories are not empty.
4. The checksum equals the recorded one. It covers:
   - the path and content of every file in `fileSets`,
   - version, dependencies and configuration of every plugin in `plugins`.

   The project version is not part of it, so a release bump does not rerun the step. Plugin configuration holds
   interpolated absolute paths, so moving the checkout reruns the step once.

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

## License

Copyright 2026 QAware GmbH. Licensed under the [Apache License, Version 2.0](LICENSE).
