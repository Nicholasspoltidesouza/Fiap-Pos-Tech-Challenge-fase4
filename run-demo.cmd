@echo off
setlocal
cd /d "%~dp0"

if not exist "demo-runner\target\fase4-demo-runner.jar" (
  echo Building demo runner...
  pushd demo-runner
  call mvnw.cmd -q -DskipTests package
  if errorlevel 1 exit /b 1
  popd
)

echo Running all flows...
java -jar "demo-runner\target\fase4-demo-runner.jar" %*
endlocal
