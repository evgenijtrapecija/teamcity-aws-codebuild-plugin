teamcity-aws-codebuild-plugin
==============================

ORIGINAL REPO: https://github.com/JetBrains/teamcity-aws-codebuild-plugin

Modified AWS CodeBuild plugin for TeamCity Server

Tested and working on TeamCity Server 2025.07

Modifications:
1) Moved from system properties to environment variables
2) Build status fetch is correct (failed in CodeBuild = failed in TC and etc.)
3) Filtered all TC system properties/env variables to push in CodeBuild (need rework)
4) Pushing build logs from Cloudwatch to TC in realtime with debug mode for troubleshooting
5) Updated some depedencies, switched to openjdk-11, gradle 6.8.3
... etc.

Build tested and working on macOS and Linux

Requirements:
1) openjdk-11
2) TeamCity 2023.05 (https://download-cdn.jetbrains.com/teamcity/TeamCity-2023.05.6.tar.gz)

Build:
1) Untar TeamCity-2023.05.6.tar.gz in ~/TeamCity (still unknown if needed)
2) Run ./gradlew clean build
3) Run ./gradlew compileJava

Installation:
Install plugin located at aws-codebuild-server/build/distributions/aws-codebuild-server.zip and use AWS CodeBuild runner as build step

Made with help of Cursor IDE <3
