# Releasing

Releases are published to Maven Central by the `Publish` workflow, which only runs when started by hand.

## One-time setup

Create a GitHub environment named `maven-central` with the maintainer as required reviewer and deployments limited to
`main`. Add these environment secrets:

- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`: a user token from the Sonatype Central Portal.
- `SIGNING_KEY`: an ASCII armored private key, preferably a signing subkey exported with `gpg --export-secret-subkeys`.
- `SIGNING_KEY_ID`: the last 8 hex characters of the ID of the key that signs, which is the subkey when one is exported.
- `SIGNING_PASSWORD`.

The public key must be available on a keyserver such as keys.openpgp.org.

## Publishing a version

1. Set the version in `kignore/build.gradle.kts`, move the notes in `CHANGELOG.md` to that version and merge to `main`
   once CI is green.
2. Run the `Publish` workflow on `main` with `dry_run` enabled. It checks the commit, builds every publication and
   inspects the result without uploading.
3. Run it again with `dry_run` disabled and approve the `maven-central` environment. It verifies everything again and
   uploads a deployment.
4. Review the deployment in the Central Portal and release it.
5. Once the version is on Maven Central, tag the commit shown in the job summary and create the GitHub Release from the
   matching section of `CHANGELOG.md`:

   ```shell
   git tag -a v0.1.0 <commit>
   git push origin v0.1.0
   gh release create v0.1.0 --title "kignore 0.1.0" --notes-file <section of CHANGELOG.md>
   ```

6. klibs.io finds new libraries on its own. Check
   `https://klibs.io/package/dev.cuervolu/kignore/<version>/status`. If the library is missing after the Google Maven
   mirror has the version, open an issue with the `index-request` label in the klibs.io repository.

## Publishing from a machine

The same Gradle configuration works locally. Set `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`
and `signingInMemoryKeyPassword` in `~/.gradle/gradle.properties`, then run `./gradlew publishToMavenCentral`. Without
the signing properties, `publishToMavenLocal` still works for checking the artifacts.
