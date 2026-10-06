# Release script: GitHub releases via pull request (main is protected).
#
# Stage 1 - open the release PR (from a clean main):
#   powershell -NoProfile -ExecutionPolicy Bypass -File release.ps1 -Version 0.2.0
#   Verifies, stamps the version, rebuilds the shaded JAR, commits on
#   release-v0.2.0, pushes, and opens the PR. Then get it merged.
#
# Stage 2 - publish after the merge (from a clean, up-to-date main):
#   powershell -NoProfile -ExecutionPolicy Bypass -File release.ps1 -Version 0.2.0 -Finish
#   Tags v0.2.0, publishes the GitHub release with the shaded JAR,
#   and opens the next-iteration PR.
#
# Requirements: git, gh (authenticated), JDK 21+, network access.

[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[string]$Version,

	[switch]$Finish,

	[string]$Next = ''
)

$ErrorActionPreference = 'Stop'

function Invoke-Step([string]$Label, [scriptblock]$Command) {
	Write-Host "==> $Label"
	& $Command
	if ($LASTEXITCODE -ne 0) {
		throw "Step failed: $Label (exit $LASTEXITCODE)"
	}
}

function Assert-CleanTree {
	$dirty = git --no-pager status --porcelain
	if (-not [string]::IsNullOrWhiteSpace(($dirty | Out-String))) {
		throw 'Working tree is not clean. Commit or stash first.'
	}
}

function Get-Asset([string]$Version) {
	$asset = Join-Path $PSScriptRoot "importtruth-cli/target/importtruth-cli-$Version.jar"
	if (-not (Test-Path $asset)) {
		throw "Expected shaded JAR missing: $asset"
	}
	return $asset
}

$tag = "v$Version"
$mvnw = Join-Path $PSScriptRoot 'mvnw.cmd'
$branch = (git --no-pager rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') {
	throw "Run from main; currently on '$branch'"
}
Assert-CleanTree
Invoke-Step 'gh authentication' { gh auth status }

if (-not $Finish) {
	Invoke-Step 'full verify' { cmd /d /c "$mvnw -B -ntp verify" }
	Invoke-Step "stamp version $Version" {
		cmd /d /c "$mvnw -B -ntp versions:set ""-DnewVersion=$Version"" -DgenerateBackupPoms=false"
	}
	Invoke-Step 'rebuild release jars' { cmd /d /c "$mvnw -B -ntp -DskipTests package" }
	$asset = Get-Asset $Version
	Write-Host "Release asset ready: $asset"
	$releaseBranch = "release-$tag"
	Invoke-Step "commit on $releaseBranch" {
		git checkout -b $releaseBranch
		git add -A
		git commit -m "release $tag"
		git push origin HEAD
	}
	Invoke-Step 'open release PR' {
		gh pr create --title "release $tag" --body "Version stamp plus rebuilt artifacts for $tag. Merge, then publish with release.ps1 -Version $Version -Finish."
	}
	Write-Host "Merge the PR, then publish with release.ps1 -Version $Version -Finish"
	return
}

Invoke-Step 'sync main' { git pull --ff-only }
$stamped = Select-String -Path (Join-Path $PSScriptRoot 'pom.xml') -Pattern "<version>$Version</version>" -Quiet
if (-not $stamped) {
	throw "pom.xml on main does not say $Version. Merge the release PR first."
}
Invoke-Step "rebuild release jars" { cmd /d /c "$mvnw -B -ntp -DskipTests package" }
$asset = Get-Asset $Version
Invoke-Step "create tag $tag" { git tag -a $tag -m $tag }
Invoke-Step 'push tag' { git push origin $tag }
Invoke-Step "publish GitHub release $tag" {
	gh release create $tag --verify-tag --generate-notes $asset
}

if ([string]::IsNullOrWhiteSpace($Next)) {
	$parts = $Version.Split('.')
	if ($parts.Length -ne 3) {
		throw "Version must look like 0.2.0, got '$Version'"
	}
	$Next = "$($parts[0]).$([int]$parts[1] + 1).0-SNAPSHOT"
	Write-Host "Next development version defaults to $Next"
}
$bumpBranch = "bump-$Next"
Invoke-Step "open next iteration $Next" {
	cmd /d /c "$mvnw -B -ntp versions:set ""-DnewVersion=$Next"" -DgenerateBackupPoms=false"
	git checkout -b $bumpBranch
	git add -A
	git commit -m 'prepare next development iteration'
	git push origin HEAD
	gh pr create --title 'prepare next development iteration' --body "Version bump to $Next after $tag."
}

Write-Host "Released $tag; development continues on $Next"
