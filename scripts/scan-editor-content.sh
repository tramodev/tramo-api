set -eu
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/scan-classpath
java -cp "target/classes:$(cat target/scan-classpath)" com.tramo.backend.common.LexicalContentScan
