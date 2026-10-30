#!/usr/bin/env bash
# Bootstrap script: generates the Gradle wrapper if it is missing.
# Usage: cd yeslauncher && bash tools/setup.sh

set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "gradle-wrapper.jar already present."
  exit 0
fi

if command -v gradle >/dev/null 2>&1; then
  echo "Generating Gradle wrapper (version 8.4)..."
  gradle wrapper --gradle-version 8.4 --distribution-type bin
  echo "Done. You can now run ./gradlew assembleDebug"
else
  cat <<'EOF'
Gradle is not installed on this machine.

To get a working wrapper, do ONE of the following:

1) Install Gradle (brew install gradle) and run this script again, OR
2) Copy gradle-wrapper.jar from any other Android project into:
     yeslauncher/gradle/wrapper/gradle-wrapper.jar
   (the matching .properties file is already written), OR
3) Download it directly:
     curl -L -o gradle/wrapper/gradle-wrapper.jar \
       https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar

After the jar is in place, make the wrapper scripts executable:
   chmod +x gradlew
EOF
  exit 1
fi
