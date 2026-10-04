$ErrorActionPreference = "Stop"
Write-Host "Minecraft 26.3 / Fabric tree physics build"
java -version
gradle --version
gradle clean build --no-daemon --stacktrace
Write-Host "`nBuild outputs:`n"
Get-ChildItem .\build\libs | Select-Object Name, Length
