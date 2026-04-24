set windows-shell := ["pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"]

java_home := "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.10.7-hotspot"
gradlew := ".\\gradlew.bat"

default:
    just --list

build:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' assembleDebug

test:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' test

connected-test:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' connectedAndroidTest

clean:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' clean

install-debug:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' installDebug

eval-smoke:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' evalSmoke

eval-full:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' evalFull

eval-smoke-strict:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' evalSmokeStrict

eval-full-strict:
    $env:JAVA_HOME = '{{java_home}}'; & '{{gradlew}}' evalFullStrict
