@echo off
java -jar target/convex-jar-with-dependencies.jar %*
exit /b %errorlevel%
