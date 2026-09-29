#!/bin/bash
# Build the GSL5 uber-jar with Maven and stage it as cli/gsl5.jar.
#
# Requirements: JDK 8 as JAVA_HOME (AGENTS.md §1.1); Maven 3.6+.
# If ~/.m2 is not writable (sandboxed env), set:
#   GSL5_MVN_ARGS="-Dmaven.repo.local=$PWD/.build-tmp/m2repo"
# One-time setup of the proven third-party jar (origin-jar/gsl5.jar, never committed):
#   see pom.xml header (extract + mvn install:install-file com.gsl5.thirdparty:origin-libs).
set -eu
cd "$(dirname "$0")/.."

# shellcheck disable=SC2086
mvn ${GSL5_MVN_ARGS:-} clean package

cp target/gsl5.jar cli/gsl5.jar
chmod +x cli/gsl5 2>/dev/null || true

echo "--- smoke: cli version ---"
java -jar cli/gsl5.jar cli version
echo "--- staged: cli/gsl5.jar ---"
ls -lh cli/gsl5.jar
