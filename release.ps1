# Release script: verify, stamp the version, tag, publish the GitHub
# release with the shaded CLI JAR, then open the next development iteration.
#
# Usage (from the repository root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File release.ps1 -Version 0.2.0
#   powershell -NoProfile -ExecutionPolicy Bypass -File release.ps1 -Version 0.2.0 -Next 0.3.0-SNAPSHOT
#
# Requirements: git, gh (authenticated), JDK 21+, network access.
# Run on main after the release PR has merged. Nothing is pushed until
# every local step succeeds.

[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)]
	[string]$Version,

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

if ([string]::IsNullOrWhiteSpace($Next)) {
	$parts = $Version.Split('.')
	if ($parts.Length -ne 3) {
		throw "Version must look like 0.2.0, got '$Version'"
	}
	$Next = "$($parts[0]).$([int]$parts[1] + 1).0-SNAPSHOT"
	Write-Host "Next development version defaults to $Next"
}

$branch = (git --no-pager rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') {
	throw "Releases cut from main; currently on '$branch'"
}

$dirty = git --no-pager status --porcelain
if (-not [string]::IsNullOrWhiteSpace($dirty)) {
	throw "Working tree is not clean. Commit or stash first."
}

Invoke-Step 'gh authentication' { gh auth status }

$tag = "v$Version"
$mvnw = Join-Path $PSScriptRoot 'mvnw.cmd'

Invoke-Step 'full verify' { cmd /d /c "$mvnw -B -ntp verify" }

Invoke-Step "stamp version $Version" {
	cmd /d /c "$mvnw -B -ntp versions:set ""-DnewVersion=$Version"" -DgenerateBackupPoms=false"
}

Invoke-Step 'commit release' {
	git add -A
	git commit -m "release $tag"
}

Invoke-Step "create tag $tag" { git tag -a $tag -m $tag }

Invoke-Step 'push branch and tag' {
	git push origin HEAD
	git push origin $tag
}

$asset = Join-Path $PSScriptRoot "importtruth-cli/target/importtruth-cli-$Version.jar"
if (-not (Test-Path $asset)) {
	throw "Expected shaded JAR missing: $asset"
}

Invoke-Step "publish GitHub release $tag" {
	gh release create $tag --verify-tag --generate-notes $asset
}

Invoke-Step "open next iteration $Next" {
	cmd /d /c "$mvnw -B -ntp versions:set ""-DnewVersion=$Next"" -DgenerateBackupPoms=false"
	git add -A
	git commit -m 'prepare next development iteration'
	git push origin HEAD
}

Write-Host "Released $tag; development continues on $Next"
