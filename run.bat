@echo off
setlocal

set "JAVA=C:\Program Files\Java\jdk-26.0.2.1\bin\java.exe"
set "JLINE=%USERPROFILE%\.m2\repository\org\jline\jline\4.4.3\jline-4.4.3-jdk11.jar"

set "MAVEN_OPTS=--sun-misc-unsafe-memory-access=allow --enable-final-field-mutation=ALL-UNNAMED"

call mvn compile
if errorlevel 1 exit /b %errorlevel%

"%JAVA%" --enable-preview --enable-native-access=ALL-UNNAMED -cp "target\classes;%JLINE%" Main

exit /b %errorlevel%