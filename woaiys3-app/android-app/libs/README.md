# Bundled Android dependencies

`commons-lang3-3.18.0.jar` supplies the transitive dependency required by
Commons Compress 1.28.0 when reading KWS tar entries (`SystemProperties`).
It must be included in both the javac classpath and the D8 inputs; compilation
alone does not detect this missing runtime class.

- Source: https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar
- Version: 3.18.0, matching the dependency declared in the bundled Commons Compress POM.
- SHA-256: `4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720`
- License and notices: Apache-2.0, included in the JAR's `META-INF/` directory.

The KWS archive smoke check invokes the real `VoiceModelStore.extractKwsArchive`
on a bzip2-compressed tar fixture. Without Commons Lang it fails with
`NoClassDefFoundError: org/apache/commons/lang3/SystemProperties`; with this JAR
it extracts all four required entries. The installed APK is then checked by
starting wake-word monitoring on the device.
