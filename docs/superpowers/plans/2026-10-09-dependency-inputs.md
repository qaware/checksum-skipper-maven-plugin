# Plan: dependencies as checksum inputs

## Context
`check` currently hashes `fileSets` and `plugins`. Steps like code generation often consume an artifact the module
depends on (e.g. a schema/migrations jar, possibly a reactor sibling or SNAPSHOT). Changing that artifact must rerun
the step. New `check` parameter `dependencies`: `groupId:artifactId` of entries in the project's own
`<dependencies>`, whose **resolved content** becomes part of the checksum.

## Design decisions
- **Selection**: `groupId:artifactId` matched against `project.getDependencies()` (effective model, so versions from
  `dependencyManagement` are already applied). All matching entries count (e.g. `jar` and `test-jar` of the same
  artifact). No match → `MojoExecutionException` naming the key (like `PluginFingerprint` for a missing plugin).
  Not transitive: only the selected artifact itself.
- **Resolution**: no `requiresDependencyResolution` on `CheckMojo`, because that would resolve everything for every user at
  `initialize` and can fail for reactor siblings. Instead, resolve only the selected artifacts via the
  injected `org.eclipse.aether.RepositorySystem.resolveArtifact(session.getRepositorySession(), request)` with
  `project.getRemoteProjectRepositories()`. The repository session includes Maven's reactor workspace reader, so a
  sibling built earlier in the same reactor resolves to its jar (or `target/classes`). `system` scope: use
  `getSystemPath()` directly. A resolution failure → `MojoExecutionException` naming the dependency.
- **What is hashed**: content only, not the version, so a release bump with identical content does not rerun.
  - zip/jar file: sorted entry names + SHA-256 of each entry's bytes. Entry timestamps are ignored, so a rebuilt but
    unchanged reactor jar or SNAPSHOT does not rerun the step.
  - directory (unpackaged reactor sibling): sorted relative paths + content hashes.
  - other files (e.g. `pom`, `.sql`): raw bytes. A file is treated as zip if `ZipFile` opens it; on `ZipException` it falls back to raw bytes.
- Goes into the existing `values` map as `dependency:<managementKey>` → content hash, next to `plugin:…`.

## Changes
1. **`pom.xml`**: add `org.apache.maven.resolver:maven-resolver-api` (version matching Maven 3.9.9, i.e. 1.9.22),
   `provided`.
2. **New `src/main/java/de/qaware/maven/skipper/DependencyFingerprint.java`**, modelled on `PluginFingerprint`:
   - `@FunctionalInterface interface Resolver { Path resolve(Dependency d) throws MojoExecutionException; }`
   - `static Map<String, String> of(MavenProject project, String key, Resolver resolver)` returns
     `"dependency:" + managementKey` → content hash for each matching dependency.
   - private content hashing (zip / directory / file as above).
3. **`ChecksumCalculator`**: make `hash(Path)`, `hex`, `sha256` package-private so they can be reused (no new copies).
4. **`CheckMojo`**:
   - `@Parameter List<String> dependencies = new ArrayList<>();` with Javadoc in the style of `plugins`.
   - `@Component RepositorySystem repositorySystem;` and `@Parameter(defaultValue = "${session}", readonly = true)
     MavenSession session;`
   - Empty-input check now covers all three kinds: "Configure at least one <fileSet>, <plugin> or <dependency> as input".
   - Loop `dependencies` → `values.putAll(DependencyFingerprint.of(project, key, this::resolve))`.
   - package-private `Path resolve(Dependency)` doing the resolver call (tests override it).
5. **README**: usage example gets a `<dependencies>` block; "When is a step up to date?" gains a bullet
   (content of every dependency in `dependencies`, with jar timestamps ignored); parameters table gets a row and the "one of" wording
   becomes "one of fileSets/plugins/dependencies". Note that the dependency must be resolvable at `check` time: a
   reactor sibling needs to be built earlier in the same reactor run or installed.

## Tests
- **`DependencyFingerprintTest`** (new, `@TempDir`): same content → same fingerprint; changed entry content →
  different; same entries written with different entry timestamps → same; directory artifact hashed by content;
  non-zip file hashed; two matching deps (jar + test-jar) both included; unknown key → exception naming it.
- **`CheckAndRecordMojoTest`**: `check(...)` returns an anonymous `CheckMojo` subclass whose `resolve` maps to temp
  files. New tests: dependencies-only config is valid; changed dependency content → `false`; unchanged → `true`.
  Update `checkWithoutInputsFails` message assertion if needed (still contains `<fileSet>`).
- **New IT `src/it/skip-when-dependency-unchanged/`**: reactor with `schema` (jar holding a resource) and `app`
  (depends on `schema`, `check` with only `<dependencies>`, antrun codegen as in the existing IT). Goals:
  `package`, `package`, `package -Dskipper.force=true`; `verify.groovy` asserts `runs == ['ran','ran']`. This
  proves resolution of a reactor sibling at `initialize` and that a rebuilt-but-unchanged jar is skipped.

## Verification
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B verify` (JDK 21 as in CI; JDK 25 breaks the invoker's
Groovy independently of this change). All unit tests and both ITs green.
