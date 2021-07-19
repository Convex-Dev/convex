@echo off
java -jar cli/target/convex-cli-jar-with-dependencies.jar %*
exit /b %errorlevel%
