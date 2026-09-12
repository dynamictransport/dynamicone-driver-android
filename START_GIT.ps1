param(
  [string]$RemoteUrl = ""
)

git init
git branch -M main
git add .
git commit -m "chore: initialize DynamicONE Driver specification"

if ($RemoteUrl -ne "") {
  git remote add origin $RemoteUrl
  git push -u origin main
}

Write-Host ""
Write-Host "Repository initialized."
if ($RemoteUrl -eq "") {
  Write-Host "Create a private remote repository, then run:"
  Write-Host "git remote add origin <REMOTE_URL>"
  Write-Host "git push -u origin main"
}
