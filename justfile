set windows-shell := ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"]

# Try to use existing JAVA_HOME from the environment, otherwise let Gradle resolve it.
# To override, run: just java_home="C:\path\to\jdk" <recipe>
java_home := env_var_or_default("JAVA_HOME", "")
gradlew := ".\\gradlew.bat"

# Helper statement to set JAVA_HOME in pwsh only if java_home is not empty
set_java_home := if java_home == "" { "" } else { "$env:JAVA_HOME = '" + java_home + "';" }

default:
    just --list

build:
    {{set_java_home}} & '{{gradlew}}' assembleDebug

test:
    {{set_java_home}} & '{{gradlew}}' test

connected-test:
    {{set_java_home}} & '{{gradlew}}' connectedAndroidTest

clean:
    {{set_java_home}} & '{{gradlew}}' clean

deploy:
    {{set_java_home}} & '{{gradlew}}' installDebug

eval-smoke:
    {{set_java_home}} & '{{gradlew}}' evalSmoke

eval-full:
    {{set_java_home}} & '{{gradlew}}' evalFull

eval-smoke-strict:
    {{set_java_home}} & '{{gradlew}}' evalSmokeStrict

eval-full-strict:
    {{set_java_home}} & '{{gradlew}}' evalFullStrict

